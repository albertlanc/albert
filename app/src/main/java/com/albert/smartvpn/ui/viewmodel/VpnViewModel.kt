package com.albert.smartvpn.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.utils.VpnConfigParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).profileDao()

    val profiles: StateFlow<List<VpnProfile>> = dao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("Core engine initialized. Ready."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun setConnectedState(connected: Boolean) {
        _isConnected.value = connected
        addLog(if (connected) "VPN service connected." else "VPN service disconnected.")
    }

    fun addLog(message: String) {
        _logs.value = _logs.value + message
    }

    fun selectProfile(id: Int) {
        viewModelScope.launch {
            dao.selectProfile(id)
            addLog("Active configuration changed (ID: $id)")
        }
    }

    fun deleteProfile(profile: VpnProfile) {
        viewModelScope.launch {
            dao.deleteProfile(profile)
            addLog("Removed configuration: ${profile.name}")
        }
    }

    fun importFromClipboard(clipboardContent: String): Boolean {
        val parsedProfile = VpnConfigParser.parseUri(clipboardContent.trim())
        return if (parsedProfile != null) {
            viewModelScope.launch {
                dao.insertProfile(parsedProfile)
                addLog("Imported profile: ${parsedProfile.name} (${parsedProfile.protocol})")
            }
            true
        } else {
            addLog("Failed to parse link: Invalid format.")
            false
        }
    }
}
