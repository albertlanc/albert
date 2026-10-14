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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).profileDao()

    val profiles: StateFlow<List<VpnProfile>> = dao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _logs = MutableStateFlow<List<String>>(listOf("Core engine initialized. Ready."))
    val logs: StateFlow<List<String>> = _logs

    fun setConnectedState(connected: Boolean) {
        _isConnected.value = connected
        addLog(if (connected) "Tunnel established." else "Tunnel disconnected.")
    }

    fun addLog(message: String) {
        _logs.value = _logs.value + message
    }

    fun selectProfile(id: Int) {
        viewModelScope.launch {
            dao.selectProfile(id)
            addLog("Selected profile ID: $id")
        }
    }

    fun deleteProfile(profile: VpnProfile) {
        viewModelScope.launch {
            dao.deleteProfile(profile)
            addLog("Deleted profile: ${profile.name}")
        }
    }

    fun saveManualProfile(profile: VpnProfile) {
        viewModelScope.launch {
            if (profile.id != 0) {
                dao.deleteProfile(profile)
            }
            dao.insertProfile(profile)
            addLog("Saved config: ${profile.name} [${profile.protocol}]")
        }
    }

    fun importFromClipboard(text: String) {
        viewModelScope.launch {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) {
                addLog("Clipboard is empty.")
                return@launch
            }
            val parsed = VpnConfigParser.parseUri(cleanText)
            if (parsed != null) {
                dao.insertProfile(parsed)
                addLog("Imported profile: ${parsed.name} (${parsed.protocol})")
            } else {
                addLog("Failed to parse link: Invalid format.")
            }
        }
    }
}
