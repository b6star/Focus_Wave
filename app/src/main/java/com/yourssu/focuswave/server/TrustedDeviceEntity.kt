package com.yourssu.focuswave.server

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trusted_devices",
    indices = [
        Index(value = ["tokenHash"], unique = true)
    ]
)
data class TrustedDeviceEntity(
    @PrimaryKey val id: String,
    val tokenHash: String,
    val displayName: String,
    val userAgent: String?,
    val lastIpAddress: String?,
    val trustedAtMillis: Long,
    val lastSeenAtMillis: Long
)

