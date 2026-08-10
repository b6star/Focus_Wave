package com.yourssu.focuswave.server

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
import javax.crypto.spec.SecretKeySpec

object FileShareCrypto {

    private val X25519_HEADER = intArrayOf(
        0x30, 0x2A, 0x30, 0x05,
        0x06, 0x03, 0x2B, 0x65,
        0x6E, 0x03, 0x21, 0x00
    ).map { it.toByte() }.toByteArray()

    fun generateServerKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("X25519")
        return keyPairGenerator.generateKeyPair()
    }

    fun publicKeyToBase64(publicKey: PublicKey): String {
        val encoded = publicKey.encoded
        val rawBytes = ByteArray(32)
        System.arraycopy(encoded, encoded.size - 32, rawBytes, 0, 32)
        return Base64.getEncoder().encodeToString(rawBytes)
    }

    fun decodeClientPublicKey(publicKeyBase64: String): PublicKey {
        val rawBytes = Base64.getDecoder().decode(publicKeyBase64)

        if (rawBytes.size != 32) {
            throw IllegalArgumentException("X25519 public key must be 32 bytes, actual=${rawBytes.size}")
        }

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
            info = "FocusWave ECDH AES-GCM v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        return SecretKeySpec(aesKeyBytes, "AES")
    }

    fun encryptAesGcmStringWith256Padding(
        plainText: String,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ): String {
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        require(plainBytes.size <= 256) { "File name is too long (max 256 bytes)" }

        val paddedBytes = ByteArray(256)
        System.arraycopy(plainBytes, 0, paddedBytes, 0, plainBytes.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonceBytes))
        val encryptedBytes = cipher.doFinal(paddedBytes)
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    fun decryptAesGcmString(
        encryptedBase64: String,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, nonceBytes))
        val decodedBytes = Base64.getDecoder().decode(encryptedBase64)
        val decryptedBytes = cipher.doFinal(decodedBytes)

        val actualLength = decryptedBytes.indexOfFirst { it == 0.toByte() }.let {
            if (it == -1) decryptedBytes.size else it
        }
        return String(decryptedBytes, 0, actualLength, Charsets.UTF_8)
    }

    fun encryptAesGcmStream(
        plainInputStream: InputStream,
        encryptedOutputStream: OutputStream,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonceBytes))

        // 256KB 씩 암호화
        val buffer = ByteArray(256 * 1024)
        var read: Int
        while (plainInputStream.read(buffer).also { read = it } != -1) {
            cipher.update(buffer, 0, read)
                ?.takeIf { it.isNotEmpty() }
                ?.let(encryptedOutputStream::write)
        }

        // 태그처리
        cipher.doFinal()
            ?.takeIf { it.isNotEmpty() }
            ?.let(encryptedOutputStream::write)

        // 버퍼에 남아있을 수 있는 데이터 처리
        encryptedOutputStream.flush()

    }

    fun decryptAesGcmStream(
        encryptedInputStream: InputStream,
        decryptedOutputStream: OutputStream,
        nonceBytes: ByteArray,
        aesKey: SecretKey
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, nonceBytes))

        CipherInputStream(encryptedInputStream, cipher).use { cipherStream ->
            val buffer = ByteArray(256 * 1024)
            var read: Int
            while (cipherStream.read(buffer).also { read = it } != -1) {
                decryptedOutputStream.write(buffer, 0, read)
            }
        }
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray {
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
