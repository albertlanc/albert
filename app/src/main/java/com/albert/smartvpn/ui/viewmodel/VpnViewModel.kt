package com.albert.smartvpn.ui.viewmodel

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.utils.VpnConfigParser
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object VpnStateBridge {
    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _logs = MutableStateFlow<List<String>>(
        listOf("[v2.2] Smart VPN Engine Ready.")
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

    fun clearLogs() {
        _logs.value = emptyList()
    }
}

object ConfigCryptoHelper {
    private val gson = Gson()
    private const val DEFAULT_KEY_SEED = "SmartVPN-Default-2026-LockKey"

    private fun deriveAesKey(passphrase: String): SecretKeySpec {
        val seed = passphrase.ifBlank { DEFAULT_KEY_SEED }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun exportProfileUri(profile: VpnProfile, lockConfig: Boolean, passphrase: String): String {
        val exportCopy = profile.copy(
            id = 0,
            isSelected = true,
            isLocked = lockConfig,
            lockPassphrase = passphrase
        )
        val json = gson.toJson(exportCopy)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16) { (it * 7 + 3).toByte() }
        cipher.init(Cipher.ENCRYPT_MODE, deriveAesKey(passphrase), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(encrypted, Base64.NO_WRAP or Base64.URL_SAFE)
        val prefix = if (passphrase.isNotBlank()) "smartvpn://locked/" else "smartvpn://open/"
        return prefix + b64
    }

    fun importProfileUri(rawText: String, passphrase: String = ""): VpnProfile? {
        val clean = rawText.trim()
        val b64 = when {
            clean.startsWith("smartvpn://locked/") -> clean.removePrefix("smartvpn://locked/")
            clean.startsWith("smartvpn://open/") -> clean.removePrefix("smartvpn://open/")
            clean.startsWith("SVPN-CLOUD:") -> clean.removePrefix("SVPN-CLOUD:")
            else -> return null
        }
        return try {
            val encrypted = Base64.decode(b64.trim(), Base64.NO_WRAP or Base64.URL_SAFE)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) { (it * 7 + 3).toByte() }
            cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(passphrase), IvParameterSpec(iv))
            val json = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            gson.fromJson(json, VpnProfile::class.java)?.copy(id = 0, isSelected = true)
        } catch (_: Throwable) {
            null
        }
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

    fun importFromClipboard(text: String, passphrase: String = "") {
        viewModelScope.launch {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) {
                VpnStateBridge.addLog("Clipboard is empty.")
                return@launch
            }
            if (cleanText.startsWith("smartvpn://locked/") && passphrase.isBlank()) {
                VpnStateBridge.addLog("Config is passphrase-protected! Tap the Key/Cloud icon on Configs to enter passphrase.")
                return@launch
            }
            val parsed = ConfigCryptoHelper.importProfileUri(cleanText, passphrase)
                ?: VpnConfigParser.parseUri(cleanText)

            if (parsed != null) {
                dao.clearSelection()
                dao.insertProfile(parsed.copy(isSelected = true))
                VpnStateBridge.addLog("Imported & selected profile: ${parsed.name} (${parsed.protocol})")
            } else {
                VpnStateBridge.addLog("Failed to import config: Invalid format or wrong passphrase.")
            }
        }
    }
}
