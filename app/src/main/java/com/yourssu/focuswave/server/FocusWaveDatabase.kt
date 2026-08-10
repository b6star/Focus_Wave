package com.yourssu.focuswave.server

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrustedDeviceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FocusWaveDatabase : RoomDatabase() {
    abstract fun trustedDeviceDao(): TrustedDeviceDao

    companion object {
        private const val DATABASE_NAME = "focus_wave.db"

        @Volatile
        private var instance: FocusWaveDatabase? = null

        fun getInstance(context: Context): FocusWaveDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FocusWaveDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
    }
}

