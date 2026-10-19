package com.albert.smartvpn.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnProfileDao {

    @Query("SELECT * FROM vpn_profiles ORDER BY id DESC")
    fun getAllProfiles(): Flow<List<VpnProfile>>

    @Query("SELECT * FROM vpn_profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedProfile(): VpnProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: VpnProfile): Long

    @Delete
    suspend fun deleteProfile(profile: VpnProfile)

    @Query("UPDATE vpn_profiles SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE vpn_profiles SET isSelected = 1 WHERE id = :id")
    suspend fun setProfileSelected(id: Int)

    suspend fun selectProfile(id: Int) {
        clearSelection()
        setProfileSelected(id)
    }
}
