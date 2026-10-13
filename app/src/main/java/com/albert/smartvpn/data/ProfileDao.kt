package com.albert.smartvpn.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM vpn_profiles ORDER BY id DESC")
    fun getAllProfiles(): Flow<List<VpnProfile>>

    @Query("SELECT * FROM vpn_profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedProfile(): VpnProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: VpnProfile)

    @Delete
    suspend fun deleteProfile(profile: VpnProfile)

    @Transaction
    suspend fun selectProfile(profileId: Int) {
        clearSelection()
        setSelection(profileId)
    }

    @Query("UPDATE vpn_profiles SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE vpn_profiles SET isSelected = 1 WHERE id = :profileId")
    suspend fun setSelection(profileId: Int)
}
