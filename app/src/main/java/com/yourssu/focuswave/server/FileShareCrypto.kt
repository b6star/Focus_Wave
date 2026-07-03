package com.yourssu.focuswave.server

import android.os.Build
import androidx.annotation.RequiresApi
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object FileShareCrypto {

    // X25519 고정 ASN.1 X.509 헤더 (12바이트)
    private val X25519_HEADER = byteArrayOf(
        0x30.toByte(), 0x2A.toByte(), 0x30.toByte(), 0x05.toByte(),
        0x06.toByte(), 0x03.toByte(), 0x2B.toByte(), 0x65.toByte(),
        0x6E.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte()
    )

    fun generateServerKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("X25519")
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
            info = "FocusWave ECDH AES-GCM v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )

        return SecretKeySpec(aesKeyBytes, "AES")
    }

    fun decryptAesGcm(encryptedBytes: ByteArray, nonceBytes: ByteArray, aesKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmParameterSpec = GCMParameterSpec(128, nonceBytes)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmParameterSpec)
        return cipher.doFinal(encryptedBytes)
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