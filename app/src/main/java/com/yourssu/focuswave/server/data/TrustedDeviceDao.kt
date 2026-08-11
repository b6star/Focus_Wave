package com.yourssu.focuswave.server.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices ORDER BY lastSeenAtMillis DESC")
    fun observeTrustedDevices(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE tokenHash = :tokenHash LIMIT 1")
    suspend fun findByTokenHash(tokenHash: String): TrustedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrustedDevice(device: TrustedDeviceEntity)

    @Query(
        """
        UPDATE trusted_devices
        SET lastSeenAtMillis = :lastSeenAtMillis,
            lastIpAddress = :lastIpAddress,
            userAgent = :userAgent
        WHERE id = :id
        """
    )
    suspend fun updateLastSeen(
        id: String,
        lastSeenAtMillis: Long,
        lastIpAddress: String?,
        userAgent: String?
    )

    @Delete
    suspend fun deleteTrustedDevice(device: TrustedDeviceEntity)

    @Query("DELETE FROM trusted_devices WHERE id = :id")
    suspend fun deleteById(id: String)
}

