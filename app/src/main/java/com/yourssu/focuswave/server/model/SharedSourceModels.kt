package com.yourssu.focuswave.server.model

enum class SharedSourceKind {
    TRUSTED_DEVICE,
    SESSION_ONLY,
    LOCAL_DEVICE,
    UNKNOWN
}


data class SharedSourceIdentity(
    val kind: SharedSourceKind,
    val sessionToken: String?,
    val trustedDeviceId: String?,
    val displayName: String,
    val ipAddress: String?,
    val userAgent: String?
)

data class SharedSourceUi(
    val kind: SharedSourceKind,
    val displayName: String,
    val ipAddress: String?
)

data class SharedFileOwner(
    val source: SharedSourceIdentity,
    val receivedAtMillis: Long
)

