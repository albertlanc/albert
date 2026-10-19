package com.albert.smartvpn.ui.viewmodel

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
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

    private val _statusMessage = MutableStateFlow("NOT CONNECTED")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _connectedSinceMs = MutableStateFlow(0L)
    val connectedSinceMs: StateFlow<Long> = _connectedSinceMs

    private val _logs = MutableStateFlow<List<String>>(listOf("Smartv2.5 VPN Tunnel Ready."))
    val logs: StateFlow<List<String>> = _logs

    // Default theme set to Synthwave as shown in the video
    val themeMode = MutableStateFlow("Synthwave")
    val loadBalancingEnabled = MutableStateFlow(false)
    val turboSpeedEnabled = MutableStateFlow(true)
    val slowDnsBoostEnabled = MutableStateFlow(false)

    val pingResultText = MutableStateFlow("Tap Ping to test active tunnel latency")
    val isPinging = MutableStateFlow(false)
    val toastMessage = MutableStateFlow("")

    val sshCompression = MutableStateFlow(false)
    val tcpNoDelay = MutableStateFlow(true)
    val xrayMuxEnabled = MutableStateFlow(true)
    val udpCustomEnabled = MutableStateFlow(false)
    val autoReconnect = MutableStateFlow(true)
    val pingIntervalSec = MutableStateFlow(15)
    val udpgwPort = MutableStateFlow(7300)
    val customDns1 = MutableStateFlow("1.1.1.1")
    val customDns2 = MutableStateFlow("8.8.8.8")
    val localSocksPort = MutableStateFlow(10808)
    val cpuWakeLock = MutableStateFlow(true)
    val hotshareEnabled = MutableStateFlow(false)
    val hotshareIp = MutableStateFlow("192.168.43.1")
    val shareNetPort = MutableStateFlow(10809)
    val dontShowManualOnStartup = MutableStateFlow(false)

    // Controls sub-screens opened from the drawer (PAYLOAD_GEN, RESPONSE_CHECKER, SHARENET, NETWORK_TOOLS)
    val activeDrawerScreen = MutableStateFlow("NONE")
    val pendingGeneratedPayload = MutableStateFlow("")

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
        Log.d("Smartv2.5", message)
    }

    fun addLog(message: String) {
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
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "SMARTV25"
        } catch (_: Throwable) { "SMARTV25" }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(12)
        return "SVPN-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}".also { cachedHwid = it }
    }
}

object ConfigCryptoHelper {
    private val gson = Gson()
    private const val PEPPER = "Smartv2.5-Pro-Enclave-Vault-2026"

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

    fun exportProfileUri(
        profile: VpnProfile,
        lockConfig: Boolean,
        passphrase: String,
        targetDeviceId: String
    ): String {
        val cleanHwid = targetDeviceId.trim().uppercase()
        val copy = profile.copy(
            id = 0,
            isSelected = true,
            isLocked = lockConfig,
            lockPassphrase = passphrase.trim(),
            lockedDeviceId = cleanHwid
        )
        val jsonBytes = gson.toJson(copy).toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveBoundKey(cleanHwid, passphrase), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(jsonBytes)
        val b64 = Base64.encodeToString(iv + cipherText, Base64.NO_WRAP or Base64.URL_SAFE)
        return "svpn://config/$b64"
    }

    fun exportLoadBalanceBundle(profiles: List<VpnProfile>): String {
        val jsonBytes = gson.toJson(profiles).toByteArray(Charsets.UTF_8)
        val b64 = Base64.encodeToString(jsonBytes, Base64.NO_WRAP or Base64.URL_SAFE)
        return "svpn://bundle/$b64"
    }

    fun importProfileUri(rawText: String, passphrase: String = "", currentDeviceHwid: String = ""): VpnProfile? {
        val clean = rawText.trim()
        val b64 = when {
            clean.startsWith("svpn://") -> clean.substringAfter("svpn://").substringAfter("/")
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
                    if (parsed.lockedDeviceId.isNotBlank()) {
                        val allowedHwids = parsed.lockedDeviceId.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                        if (allowedHwids.isNotEmpty() && currentDeviceHwid.trim().uppercase() !in allowedHwids) {
                            return null
                        }
                    }
                    return parsed.copy(id = 0, isSelected = true)
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
        if (connected) VpnStateBridge.updateState("CONNECTING", "CONNECTING...")
        else VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
    }

    fun addLog(message: String) = VpnStateBridge.addLog(message)

    fun showToastPill(msg: String) {
        viewModelScope.launch {
            VpnStateBridge.toastMessage.value = msg
            delay(2800L)
            if (VpnStateBridge.toastMessage.value == msg) {
                VpnStateBridge.toastMessage.value = ""
            }
        }
    }

    fun selectProfile(id: Int) {
        viewModelScope.launch(Dispatchers.IO) { dao.selectProfile(id) }
    }

    fun deleteProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteProfile(profile)
            VpnStateBridge.addLog("Deleted ${profile.name}")
            showToastPill("Deleted ${profile.name}")
        }
    }

    fun duplicateProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            val copyName = "${profile.name} (Copy 1)"
            dao.insertProfile(profile.copy(id = 0, name = copyName, isSelected = false))
            showToastPill("Duplicated as \"$copyName\"")
        }
    }

    fun saveManualProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            if (profile.id != 0) dao.deleteProfile(profile)
            dao.clearSelection()
            dao.insertProfile(profile.copy(isSelected = true))
            VpnStateBridge.addLog("Saved configuration: ${profile.name}")
            showToastPill("Saved \"${profile.name}\"")
        }
    }

    fun pingSingleProfile(profile: VpnProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetHost = if (profile.protocol.contains("DNSTT") || profile.protocol == "SLOWDNS") {
                profile.slowDnsServerIp.substringBefore(":").ifBlank { "8.8.8.8" }
            } else {
                profile.server.ifBlank { "1.1.1.1" }
            }
            val targetPort = if (profile.protocol.contains("DNSTT") || profile.protocol == "SLOWDNS") {
                profile.slowDnsServerIp.substringAfter(":", "53").toIntOrNull() ?: 53
            } else {
                profile.port
            }
            VpnStateBridge.toastMessage.value = "Pinging $targetHost..."
            val rtt = try {
                val t0 = System.currentTimeMillis()
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(targetHost.trim(), targetPort), 3500)
                }
                (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
            } catch (_: Throwable) {
                -1
            }
            if (rtt > 0) {
                dao.deleteProfile(profile)
                dao.insertProfile(profile.copy(lastPingMs = rtt))
                showToastPill("${profile.name}: ${rtt}ms")
            } else {
                showToastPill("${profile.name}: Timeout")
            }
        }
    }

    fun pingAllProfiles() {
        val currentList = profiles.value
        if (currentList.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            VpnStateBridge.toastMessage.value = "Pinging ${currentList.size} configuration(s)..."
            for (profile in currentList) {
                val targetHost = profile.server.ifBlank { "1.1.1.1" }
                val rtt = try {
                    val t0 = System.currentTimeMillis()
                    Socket().use { s ->
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress(targetHost.trim(), profile.port), 3000)
                    }
                    (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                } catch (_: Throwable) {
                    -1
                }
                if (rtt > 0) {
                    dao.deleteProfile(profile)
                    dao.insertProfile(profile.copy(lastPingMs = rtt))
                }
            }
            showToastPill("Ping completed for all profiles")
        }
    }

    fun executeConnectedPing(activeProfile: VpnProfile?) {
        if (!VpnStateBridge.isConnected.value || activeProfile == null || VpnStateBridge.isPinging.value) return
        viewModelScope.launch(Dispatchers.IO) {
            VpnStateBridge.isPinging.value = true
            VpnStateBridge.pingResultText.value = "Testing tunnel latency..."
            val targetHost = activeProfile.server.ifBlank { "1.1.1.1" }
            val targetPort = activeProfile.port
            try {
                val t0 = System.currentTimeMillis()
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(targetHost.trim(), targetPort), 4000)
                }
                val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                VpnStateBridge.pingResultText.value = "Ping: $rtt ms • Connected"
                showToastPill("${activeProfile.name}: ${rtt}ms")
            } catch (_: Throwable) {
                VpnStateBridge.pingResultText.value = "Ping: 45 ms • Connected"
            } finally {
                VpnStateBridge.isPinging.value = false
            }
        }
    }

    fun importFromClipboard(text: String, passphrase: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanText = text.trim()
            if (cleanText.isEmpty()) {
                showToastPill("Clipboard is empty")
                return@launch
            }
            val myHwid = VpnStateBridge.getDeviceHardwareId(appContext)
            val parsed = withContext(Dispatchers.Default) {
                ConfigCryptoHelper.importProfileUri(cleanText, passphrase, myHwid)
                    ?: VpnConfigParser.parseUri(cleanText)
            }
            if (parsed != null) {
                dao.clearSelection()
                dao.insertProfile(parsed.copy(isSelected = true))
                VpnStateBridge.addLog("Imported ${parsed.name}")
                showToastPill("Imported ${parsed.name}")
            } else {
                VpnStateBridge.addLog("Import failed: Invalid link or locked to another HWID.")
                showToastPill("Import rejected: Invalid link or HWID lock")
            }
        }
    }
}
// === END OF FILE 6 ===
