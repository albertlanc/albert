package com.albert.smartvpn.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Applicationpackage com.albert.smartvpn.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.utils.VpnConfigParser
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VpnStateBridge {
    @Volatile var hasShownSplash: Boolean = false
    @Volatile private var cachedHwid: String? = null

    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _connectedSinceMs = MutableStateFlow(0L)
    val connectedSinceMs: StateFlow<Long> = _connectedSinceMs

    private val _logs = MutableStateFlow<List<String>>(listOf("Ready."))
    val logs: StateFlow<List<String>> = _logs

    val themeMode = MutableStateFlow("AUTO")
    val slowDnsBoostEnabled = MutableStateFlow(true)
    val sshCompression = MutableStateFlow(false)
    val autoReconnect = MutableStateFlow(true)
    val pingIntervalSec = MutableStateFlow(15)
    val udpgwPort = MutableStateFlow(7300)
    val customDns1 = MutableStateFlow("1.1.1.1")
    val customDns2 = MutableStateFlow("8.8.8.8")
    val localSocksPort = MutableStateFlow(10808)
    val cpuWakeLock = MutableStateFlow(true)
    val hotshareEnabled = MutableStateFlow(false)
    val hotshareIp = MutableStateFlow("192.168.43.1")

    fun updateState(state: String, status: String = state) {
        _connectionState.value = state
        val nowConnected = (state == "CONNECTED")
        if (nowConnected && !_isConnected.value) {
            _connectedSinceMs.value = System.currentTimeMillis()
        } else if (!nowConnected) {
            _connectedSinceMs.value = 0L
        }
        _isConnected.value = nowConnected
        _statusMessage.value = status
    }

    fun addDebugOnlyLog(message: String) {
        Log.d("SmartVPN-Internal", message)
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.value = (_logs.value + "[$time] $message").takeLast(150)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    @SuppressLint("HardwareIds")
    fun getDeviceHardwareId(context: Context): String {
        cachedHwid?.let { return it }
        val rawId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "SMARTVPN"
        } catch (_: Throwable) { "SMARTVPN" }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(12)
        return "SVPN-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}".also { cachedHwid = it }
    }
}

object ConfigCryptoHelper {
    private val gson = Gson()
    private const val PEPPER = "SmartVPN-Ultra-v5-Enclave-Vault-2026"

    private fun deriveBoundKey(hwid: String, passphrase: String): SecretKeySpec {
        var hash = "$PEPPER|HWID:${hwid.trim().uppercase()}|PASS:${passphrase.trim()}".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        repeat(64) {
            md.update(hash)
            md.update(PEPPER.toByteArray(Charsets.UTF_8))
            hash = md.digest()
        }
        return SecretKeySpec(hash, "AES")
    }

    fun exportProfileUri(profile: VpnProfile, lockConfig: Boolean, passphrase: String, targetDeviceId: String): String {
        val cleanHwid = targetDeviceId.trim().uppercase()
        val copy = profile.copy(id = 0, isSelected = true, isLocked = true, lockPassphrase = passphrase.trim(), lockedDeviceId = cleanHwid)
        val jsonBytes = gson.toJson(copy).toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveBoundKey(cleanHwid, passphrase), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(jsonBytes)
        val b64 = Base64.encodeToString(iv + cipherText, Base64.NO_WRAP or Base64.URL_SAFE)
        val tag = if (passphrase.isNotBlank()) "locked" else "hwid"
        return "smartvpn://$tag/$b64"
    }

    fun importProfileUri(rawText: String, passphrase: String = "", currentDeviceHwid: String = ""): VpnProfile? {
        val clean = rawText.trim()
        val b64 = when {
            clean.startsWith("smartvpn://") -> clean.substringAfter("smartvpn://").substringAfter("/")
            clean.startsWith("SVPN-CLOUD:") -> clean.removePrefix("SVPN-CLOUD:")
            else -> return null
        }
        val combined = try { Base64.decode(b64.trim(), Base64.NO_WRAP or Base64.URL_SAFE) } catch (_: Throwable) { return null }
        if (combined.size <= 12) return null
        val iv = combined.copyOfRange(0, 12)
        val cipherText = combined.copyOfRange(12, combined.size)

        for (hwid in listOf(currentDeviceHwid.trim().uppercase(), "")) {
            for (pass in listOf(passphrase.trim(), "")) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, deriveBoundKey(hwid, pass), GCMParameterSpec(128, iv))
                    val json = String(cipher.doFinal(cipherText), Charsets.UTF_8)
                    val parsed = gson.fromJson(json, VpnProfile::class.java) ?: continue
                    if (parsed.lockedDeviceId.isNotBlank() && !parsed.lockedDeviceId.trim().equals(currentDeviceHwid.trim(), true)) {
                        return null
                    }
                    return parsed.copy(id = 0, isSelected = true, isLocked = true)
                } catch (_: Throwable) {}
            }
        }
        return null
    }
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val dao by lazy { AppDatabase.getDatabase(appContext).profileDao() }

    val profiles: StateFlow<List<VpnProfile>> = dao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isConnected: StateFlow<Boolean> = VpnStateBridge.isConnected
    val connectionState: StateFlow<String> = VpnStateBridge.connectionState
    val statusMessage: StateFlow<String> = VpnStateBridge.statusMessage
    val logs: StateFlow<List<String>> = VpnStateBridge.logs

    fun setConnectedState(connected: Boolean) {
        if (connected) VpnStateBridge.updateState("CONNECTING", "Connecting...")
        else VpnStateBridge.updateState("DISCONNECTED", "Idle")
    }

    fun addLog(message: String) = VpnStateBridge.addLog(message)

    fun selectProfile(id: Int) {
        viewModelScope.launch(Dispatchers.IO) { dao.selectProfile(id) }
    }

    fun deleteProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteProfile(profile)
            VpnStateBridge.addLog("Configuration removed.")
        }
    }

    fun saveManualProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            if (profile.id != 0) dao.deleteProfile(profile)
            dao.clearSelection()
            dao.insertProfile(profile.copy(isSelected = true))
            VpnStateBridge.addLog("Configuration saved.")
        }
    }

    fun importFromClipboard(text: String, passphrase: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) return@launch
            if (cleanText.startsWith("smartvpn://locked/") && passphrase.isBlank()) {
                VpnStateBridge.addLog("Passphrase required to unlock configuration.")
                return@launch
            }
            val myHwid = VpnStateBridge.getDeviceHardwareId(appContext)
            val parsed = withContext(Dispatchers.Default) {
                ConfigCryptoHelper.importProfileUri(cleanText, passphrase, myHwid)
                    ?: VpnConfigParser.parseUri(cleanText)
            }
            if (parsed != null) {
                if (parsed.lockedDeviceId.isNotBlank() && !parsed.lockedDeviceId.trim().equals(myHwid, true)) {
                    VpnStateBridge.addLog("HWID Lock Rejected: Unauthorized device.")
                    return@launch
                }
                dao.clearSelection()
                dao.insertProfile(parsed.copy(isSelected = true))
                VpnStateBridge.addLog("Configuration imported: ${parsed.name}")
            } else {
                VpnStateBridge.addLog("Import rejected: Invalid Hardware ID or passphrase.")
            }
        }
    }
}
// === END OF FILE ===
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.utils.VpnConfigParser
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VpnStateBridge {
    @Volatile var hasShownSplash: Boolean = false
    @Volatile private var cachedHwid: String? = null

    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _connectedSinceMs = MutableStateFlow(0L)
    val connectedSinceMs: StateFlow<Long> = _connectedSinceMs

    private val _logs = MutableStateFlow<List<String>>(listOf("Ready."))
    val logs: StateFlow<List<String>> = _logs

    // Tracks all checked/cloned profiles for Multi-Clone Cluster Bonding
    val selectedProfileIds = MutableStateFlow<Set<Int>>(emptySet())

    val themeMode = MutableStateFlow("AUTO")
    val slowDnsBoostEnabled = MutableStateFlow(true)

    val sshCompression = MutableStateFlow(false)
    val autoReconnect = MutableStateFlow(true)
    val pingIntervalSec = MutableStateFlow(15)
    val udpgwPort = MutableStateFlow(7300)
    val customDns1 = MutableStateFlow("1.1.1.1")
    val customDns2 = MutableStateFlow("8.8.8.8")
    val localSocksPort = MutableStateFlow(10808)
    val cpuWakeLock = MutableStateFlow(true)
    val hotshareEnabled = MutableStateFlow(false)
    val hotshareIp = MutableStateFlow("192.168.43.1")

    fun updateState(state: String, status: String = state) {
        _connectionState.value = state
        val nowConnected = (state == "CONNECTED")
        if (nowConnected && !_isConnected.value) {
            _connectedSinceMs.value = System.currentTimeMillis()
        } else if (!nowConnected) {
            _connectedSinceMs.value = 0L
        }
        _isConnected.value = nowConnected
        _statusMessage.value = status
    }

    fun addDebugOnlyLog(message: String) {
        Log.d("SmartVPN-Internal", message)
    }

    fun addLog(message: String) {
        Log.d("SmartVPN-Log", message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.value = (_logs.value + "[$time] $message").takeLast(200)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    @SuppressLint("HardwareIds")
    fun getDeviceHardwareId(context: Context): String {
        cachedHwid?.let { return it }
        val rawId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "SMARTVPN"
        } catch (_: Throwable) {
            "SMARTVPN"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(12)
        val computed = "SVPN-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
        cachedHwid = computed
        return computed
    }
}

object ConfigCryptoHelper {
    private val gson = Gson()
    private const val VAULT_MASTER_PEPPER = "SmartVPN-Ultra-v5-Enclave-Vault-2026"

    private fun deriveBoundAesGcmKey(hwid: String, passphrase: String): SecretKeySpec {
        val normalizedHwid = hwid.trim().uppercase()
        val normalizedPass = passphrase.trim()
        val material = "$VAULT_MASTER_PEPPER|HWID:$normalizedHwid|PASS:$normalizedPass"
        var hash = material.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        repeat(64) {
            md.update(hash)
            md.update(VAULT_MASTER_PEPPER.toByteArray(Charsets.UTF_8))
            hash = md.digest()
        }
        return SecretKeySpec(hash, "AES")
    }

    fun exportProfileUri(
        profile: VpnProfile,
        lockConfig: Boolean,
        passphrase: String,
        targetDeviceId: String
    ): String {
        val cleanHwid = targetDeviceId.trim().uppercase()
        val exportCopy = profile.copy(
            id = 0,
            isSelected = true,
            isLocked = true,
            lockPassphrase = passphrase.trim(),
            lockedDeviceId = cleanHwid
        )
        val jsonBytes = gson.toJson(exportCopy).toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveBoundAesGcmKey(cleanHwid, passphrase)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(jsonBytes)

        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        val b64 = Base64.encodeToString(combined, Base64.NO_WRAP or Base64.URL_SAFE)
        val modeTag = when {
            cleanHwid.isNotEmpty() && passphrase.isNotBlank() -> "hwidpass"
            cleanHwid.isNotEmpty() -> "hwid"
            passphrase.isNotBlank() -> "locked"
            else -> "open"
        }
        return "smartvpn://$modeTag/$b64"
    }

    fun importProfileUri(
        rawText: String,
        passphrase: String = "",
        currentDeviceHwid: String = ""
    ): VpnProfile? {
        val clean = rawText.trim()
        val b64 = when {
            clean.startsWith("smartvpn://") -> clean.substringAfter("smartvpn://").substringAfter("/")
            clean.startsWith("SVPN-CLOUD:") -> clean.removePrefix("SVPN-CLOUD:")
            else -> return null
        }

        val combined = try {
            Base64.decode(b64.trim(), Base64.NO_WRAP or Base64.URL_SAFE)
        } catch (_: Throwable) {
            return null
        }
        if (combined.size <= 12) return null

        val iv = combined.copyOfRange(0, 12)
        val cipherText = combined.copyOfRange(12, combined.size)

        val candidateHwids = listOf(currentDeviceHwid.trim().uppercase(), "")
        val candidatePasses = listOf(passphrase.trim(), "")

        for (hwidCandidate in candidateHwids) {
            for (passCandidate in candidatePasses) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val key = deriveBoundAesGcmKey(hwidCandidate, passCandidate)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    val decryptedBytes = cipher.doFinal(cipherText)
                    val json = String(decryptedBytes, Charsets.UTF_8)
                    val parsed = gson.fromJson(json, VpnProfile::class.java) ?: continue

                    if (parsed.lockedDeviceId.isNotBlank() &&
                        !parsed.lockedDeviceId.trim().equals(currentDeviceHwid.trim(), ignoreCase = true)
                    ) {
                        return null
                    }
                    return parsed.copy(id = 0, isSelected = true, isLocked = true)
                } catch (_: Throwable) {}
            }
        }
        return null
    }
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val dao by lazy { AppDatabase.getDatabase(appContext).profileDao() }

    val profiles: StateFlow<List<VpnProfile>> = dao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        viewModelScope.launch(Dispatchers.IO) {
            dao.selectProfile(id)
            val current = VpnStateBridge.selectedProfileIds.value.toMutableSet()
            current.add(id)
            VpnStateBridge.selectedProfileIds.value = current
        }
    }

    fun toggleClusterSelection(profile: VpnProfile, checked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = VpnStateBridge.selectedProfileIds.value.toMutableSet()
            if (checked) {
                current.add(profile.id)
                dao.setSelection(profile.id)
            } else {
                current.remove(profile.id)
            }
            VpnStateBridge.selectedProfileIds.value = current
        }
    }

    fun cloneProfileCluster(target: VpnProfile, cloneCount: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            for (i in 1..cloneCount) {
                val cloneName = if (cloneCount == 1) "${target.name} (Clone)" else "${target.name} #${i + 1}"
                dao.insertProfile(target.copy(id = 0, name = cloneName, isSelected = true))
            }
            val allAfter = dao.getAllProfiles().firstOrNull().orEmpty()
            val matchingIds = allAfter
                .filter { it.server == target.server && it.protocol == target.protocol }
                .map { it.id }
                .toSet()
            VpnStateBridge.selectedProfileIds.value = VpnStateBridge.selectedProfileIds.value + matchingIds
            if (target.protocol.equals("SLOWDNS", ignoreCase = true)) {
                VpnStateBridge.slowDnsBoostEnabled.value = true
            }
            VpnStateBridge.addLog("Created $cloneCount turbo clone(s) for ${target.name} (${matchingIds.size} bonded).")
        }
    }

    fun deleteProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            if (VpnStateBridge.connectionState.value != "DISCONNECTED") {
                VpnStateBridge.addLog("Action blocked: Disconnect VPN before deleting configurations.")
                return@launch
            }
            dao.deleteProfile(profile)
            VpnStateBridge.selectedProfileIds.value = VpnStateBridge.selectedProfileIds.value - profile.id
            VpnStateBridge.addLog("Configuration removed.")
        }
    }

    fun saveManualProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            if (VpnStateBridge.connectionState.value != "DISCONNECTED") {
                VpnStateBridge.addLog("Action blocked: Disconnect VPN before editing configurations.")
                return@launch
            }
            if (profile.id != 0) {
                dao.deleteProfile(profile)
            }
            dao.insertProfile(profile.copy(isSelected = true))
            val latest = dao.getAllProfiles().firstOrNull().orEmpty()
            latest.lastOrNull()?.let { saved ->
                VpnStateBridge.selectedProfileIds.value = VpnStateBridge.selectedProfileIds.value + saved.id
            }
            VpnStateBridge.addLog("Configuration saved: ${profile.name}")
        }
    }

    fun importFromClipboard(text: String, passphrase: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) {
                VpnStateBridge.addLog("Clipboard is empty.")
                return@launch
            }
            if ((cleanText.startsWith("smartvpn://locked/") || cleanText.startsWith("smartvpn://hwidpass/")) &&
                passphrase.isBlank()
            ) {
                VpnStateBridge.addLog("Passphrase required to unlock configuration.")
                return@launch
            }

            val myDeviceId = VpnStateBridge.getDeviceHardwareId(appContext)
            val parsed = withContext(Dispatchers.Default) {
                ConfigCryptoHelper.importProfileUri(cleanText, passphrase, myDeviceId)
                    ?: VpnConfigParser.parseUri(cleanText)
            }

            if (parsed != null) {
                if (parsed.lockedDeviceId.isNotBlank() &&
                    !parsed.lockedDeviceId.trim().equals(myDeviceId, ignoreCase = true)
                ) {
                    VpnStateBridge.addLog("HWID Lock Rejected: Unauthorized device.")
                    return@launch
                }
                dao.insertProfile(parsed.copy(isSelected = true))
                val latest = dao.getAllProfiles().firstOrNull().orEmpty()
                latest.lastOrNull()?.let { saved ->
                    VpnStateBridge.selectedProfileIds.value = setOf(saved.id)
                }
                VpnStateBridge.addLog("Configuration imported: ${parsed.name}")
            } else {
                VpnStateBridge.addLog("Import rejected: Invalid Hardware ID or passphrase.")
            }
        }
    }
}
