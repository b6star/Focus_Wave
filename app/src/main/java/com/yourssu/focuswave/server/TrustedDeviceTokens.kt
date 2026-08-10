package com.yourssu.focuswave.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object TrustedDeviceTokens {
    private const val TOKEN_BYTE_LENGTH = 32

    fun generateToken(secureRandom: SecureRandom = SecureRandom()): String {
        val tokenBytes = ByteArray(TOKEN_BYTE_LENGTH)
        secureRandom.nextBytes(tokenBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
    }

    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

