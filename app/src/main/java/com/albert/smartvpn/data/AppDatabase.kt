package com.albert.smartvpn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.nio.charset.StandardCharsets

@Database(entities = [VpnProfile::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // In production, generate and retrieve this securely from Android Keystore
                val passphrase = "smartvpn_secure_key_2026".toByteArray(StandardCharsets.UTF_8)
                val factory = SupportOpenHelperFactory(passphrase)
                
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smartvpn_encrypted.db"
                )
                .openHelperFactory(factory)
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
