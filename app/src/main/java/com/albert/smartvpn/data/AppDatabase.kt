package com.albert.smartvpn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.sqlcipher.SupportOpenHelperFactory

@Database(entities = [VpnProfile::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): VpnProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_vpn_database"
                )
                    .fallbackToDestructiveMigration()
                    .openHelperFactory(SupportOpenHelperFactory("Smart2.5.1-Encrypted-Vault-Key".toByteArray()))
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
