package com.yourssu.focuswave.server

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SafeCipherInputStream(
    private val source: java.io.InputStream,
    private val cipher: javax.crypto.Cipher
) : java.io.InputStream() {

    private val buffer = ByteArray(256 * 1024)
    private var outBuffer: ByteArray? = null
    private var outIndex = 0
    private var isEof = false

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            if (outBuffer != null && outIndex < outBuffer!!.size) {
                val available = outBuffer!!.size - outIndex
                val toCopy = available.coerceAtMost(len)
                System.arraycopy(outBuffer!!, outIndex, b, off, toCopy)
                outIndex += toCopy
                return toCopy
            }
            if (isEof) return -1
            val readCount = source.read(buffer)
            if (readCount == -1) {
                isEof = true
                outBuffer = cipher.doFinal() // 💡 여기서 마지막 패딩을 강제로 끄집어냅니다.
                outIndex = 0
                continue
            }
            val updated = cipher.update(buffer, 0, readCount)
            if (updated != null && updated.isNotEmpty()) {
                outBuffer = updated
                outIndex = 0
            }
        }
    }
}

object FileShareCrypto {

    // X25519 고정 ASN.1 X.509 헤더 (12바이트)
    private val X25519_HEADER = intArrayOf(
        0x30, 0x2A, 0x30, 0x05,
        0x06, 0x03, 0x2B, 0x65,
        0x6E, 0x03, 0x21, 0x00
    ).map { it.toByte() }.toByteArray()

    fun generateServerKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("X25519")  //ECDH Key Exchange Algorithm
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * 서버 공개키(Java PublicKey) -> 브라우저용 Raw 32바이트 Base64 변환
     */
    fun publicKeyToBase64(publicKey: PublicKey): String {
        val encoded = publicKey.encoded // 정식 X.509 포맷 (44바이트)

        // 앞의 12바이트 헤더를 떼어내고 순수 32바이트 키만 추출 (엔디안 신경 쓸 필요 없음)
        val rawBytes = ByteArray(32)
        System.arraycopy(encoded, encoded.size - 32, rawBytes, 0, 32)

        return Base64.getEncoder().encodeToString(rawBytes)
    }

    /**
     * 브라우저가 보낸 Raw 32바이트 Base64 -> 서버용 Java PublicKey 변환
     */
    fun decodeClientPublicKey(publicKeyBase64: String): PublicKey {
        val rawBytes = Base64.getDecoder().decode(publicKeyBase64)

        if (rawBytes.size != 32) {
            throw IllegalArgumentException("X25519 public key must be 32 bytes, actual=${rawBytes.size}")
        }

        // 고정 헤더(12바이트)와 브라우저의 키(32바이트)를 붙여서 44바이트 자바 표준 스펙으로 빌드
        val x509KeyBytes = ByteArray(X25519_HEADER.size + rawBytes.size)
        System.arraycopy(X25519_HEADER, 0, x509KeyBytes, 0, X25519_HEADER.size)
        System.arraycopy(rawBytes, 0, x509KeyBytes, X25519_HEADER.size, rawBytes.size)

        val keySpec = X509EncodedKeySpec(x509KeyBytes)
        return KeyFactory.getInstance("X25519").generatePublic(keySpec)
    }

    fun deriveAesKey(serverKeyPair: KeyPair, clientPublicKey: PublicKey): SecretKey {
        val keyAgreement = KeyAgreement.getInstance("XDH")
        keyAgreement.init(serverKeyPair.private)
        keyAgreement.doPhase(clientPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val aesKeyBytes = hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = "FocusWave FileShare Salt".toByteArray(Charsets.UTF_8),
            info = "FocusWave ECDH AES-CBC v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        return SecretKeySpec(aesKeyBytes, "AES")
    }

    fun encryptAesCbcStringWith256Padding(
        plainText: String,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ): String {
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)

        require(plainBytes.size <= 256) {"File name is too long (max 256 bytes)" }

        val paddedBytes = ByteArray(256)
        System.arraycopy(plainBytes, 0, paddedBytes, 0, plainBytes.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(nonceBytes))
        val encryptedBytes = cipher.doFinal(paddedBytes)
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    fun decryptAesCbcString(
        encryptedBase64: String,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ) : String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(nonceBytes))
        val decodedBytes = Base64.getDecoder().decode(encryptedBase64)
        val decryptedBytes = cipher.doFinal(decodedBytes)

        val actualLength = decryptedBytes.indexOfFirst { it == 0.toByte() }.let {
            if (it == -1) decryptedBytes.size else it
        }
        return String(decryptedBytes, 0, actualLength, Charsets.UTF_8)
    }

    fun encryptAesCbcStream(
        plainInputStream: InputStream,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ): InputStream {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(nonceBytes))
        return SafeCipherInputStream(plainInputStream, cipher)
    }
    fun decryptAesCbcStream(
        encryptedInputStream: InputStream,
        decryptedOutputStream: OutputStream,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivSpec = IvParameterSpec(nonceBytes)

        cipher.init(Cipher.DECRYPT_MODE, aesKey, ivSpec)

        // 256KB씩 청크 단위로 나누어 처리
        CipherInputStream(encryptedInputStream, cipher).use { cipherStream ->
            val buffer = ByteArray(256* 1024)
            var read: Int
            while (cipherStream.read(buffer).also { read = it } != -1) {
                decryptedOutputStream.write(buffer, 0, read)
            }
        }
    }

    private fun hkdfSha256(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val pseudoRandomKey = hmacSha256(key = salt, data = inputKeyMaterial)
        val result = ByteArray(outputLength)
        var previousBlock = ByteArray(0)
        var generatedLength = 0
        var counter = 1

        while (generatedLength < outputLength) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())

            val currentBlock = mac.doFinal()
            val bytesToCopy = minOf(currentBlock.size, outputLength - generatedLength)
            System.arraycopy(currentBlock, 0, result, generatedLength, bytesToCopy)

            generatedLength += bytesToCopy
            previousBlock = currentBlock
            counter++
        }

        return result
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }


}