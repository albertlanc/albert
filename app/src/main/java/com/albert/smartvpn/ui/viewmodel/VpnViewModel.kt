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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnStateBridge {
    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _logs = MutableStateFlow<List<String>>(
        listOf("[v2.1] Smart VPN Engine Ready. Waiting for connection...")
    )
    val logs: StateFlow<List<String>> = _logs

    fun updateState(state: String, status: String = state) {
        _connectionState.value = state
        _isConnected.value = (state == "CONNECTED")
        _statusMessage.value = status
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.value = (_logs.value + "[$time] $message").takeLast(300)
    }
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).profileDao()

    val profiles: StateFlow<List<VpnProfile>> = dao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isConnected: StateFlow<Boolean> = VpnStateBridge.isConnected
    val connectionState: StateFlow<String> = VpnStateBridge.connectionState
    val statusMessage: StateFlow<String> = VpnStateBridge.statusMessage
    val logs: StateFlow<List<String>> = VpnStateBridge.logs

    fun setConnectedState(connected: Boolean) {
        if (connected) {
            VpnStateBridge.updateState("CONNECTING", "Connecting...")
        } else {
            VpnStateBridge.updateState("DISCONNECTED", "Idle")
        }
    }

    fun addLog(message: String) {
        VpnStateBridge.addLog(message)
    }

    fun selectProfile(id: Int) {
        viewModelScope.launch {
            dao.selectProfile(id)
            VpnStateBridge.addLog("Selected profile ID: $id")
        }
    }

    fun deleteProfile(profile: VpnProfile) {
        viewModelScope.launch {
            dao.deleteProfile(profile)
            VpnStateBridge.addLog("Deleted profile: ${profile.name}")
        }
    }

    fun saveManualProfile(profile: VpnProfile) {
        viewModelScope.launch {
            if (profile.id != 0) {
                dao.deleteProfile(profile)
            }
            dao.clearSelection()
            dao.insertProfile(profile.copy(isSelected = true))
            VpnStateBridge.addLog("Saved & selected config: ${profile.name} [${profile.protocol}]")
        }
    }

    fun importFromClipboard(text: String) {
        viewModelScope.launch {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) {
                VpnStateBridge.addLog("Clipboard is empty.")
                return@launch
            }
            val parsed = VpnConfigParser.parseUri(cleanText)
            if (parsed != null) {
                dao.clearSelection()
                dao.insertProfile(parsed.copy(isSelected = true))
                VpnStateBridge.addLog("Imported & selected profile: ${parsed.name} (${parsed.protocol})")
            } else {
                VpnStateBridge.addLog("Failed to parse link: Invalid format.")
            }
        }
    }
}
