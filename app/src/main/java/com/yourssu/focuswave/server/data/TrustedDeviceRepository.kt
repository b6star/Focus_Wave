package com.yourssu.focuswave.server.data

import android.content.Context
import com.yourssu.focuswave.server.TrustedDeviceTokens
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class TrustedDeviceRepository private constructor(
    private val trustedDeviceDao: TrustedDeviceDao
) {
    val trustedDevices: Flow<List<TrustedDeviceEntity>> =
        trustedDeviceDao.observeTrustedDevices()

    suspend fun trustDevice(
        trustedToken: String,
        displayName: String,
        userAgent: String?,
        ipAddress: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): TrustedDeviceEntity {
        val device = TrustedDeviceEntity(
            id = UUID.randomUUID().toString(),
            tokenHash = TrustedDeviceTokens.hashToken(trustedToken),
            displayName = displayName,
            userAgent = userAgent,
            lastIpAddress = ipAddress,
            trustedAtMillis = nowMillis,
            lastSeenAtMillis = nowMillis
        )

        trustedDeviceDao.upsertTrustedDevice(device)
        return device
    }

    suspend fun findTrustedDeviceByToken(
        trustedToken: String,
        ipAddress: String?,
        userAgent: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): TrustedDeviceEntity? {
        val tokenHash = TrustedDeviceTokens.hashToken(trustedToken)
        val device = trustedDeviceDao.findByTokenHash(tokenHash) ?: return null

        trustedDeviceDao.updateLastSeen(
            id = device.id,
            lastSeenAtMillis = nowMillis,
            lastIpAddress = ipAddress,
            userAgent = userAgent
        )

        return device.copy(
            lastSeenAtMillis = nowMillis,
            lastIpAddress = ipAddress,
            userAgent = userAgent
        )
    }

    suspend fun deleteTrustedDevice(id: String) {
        trustedDeviceDao.deleteById(id)
    }

    companion object {
        @Volatile
        private var instance: TrustedDeviceRepository? = null

        fun getInstance(context: Context): TrustedDeviceRepository =
            instance ?: synchronized(this) {
                instance ?: TrustedDeviceRepository(
                    FocusWaveDatabase.getInstance(context).trustedDeviceDao()
                ).also { instance = it }
            }
    }
}
