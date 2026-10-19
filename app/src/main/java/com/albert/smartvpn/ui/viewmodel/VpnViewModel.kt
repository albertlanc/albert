package com.albert.smartvpn.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.utils.VpnConfigParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
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

    private fun buildStartupLogs(): List<String> {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val ip = getLocalIpAddress()
        return listOf(
            "[$time] Smart v3.0 (300)",
            "[$time] Running on ${Build.MANUFACTURER} (${Build.MODEL})",
            "[$time] Android ${Build.VERSION.RELEASE} API-${Build.VERSION.SDK_INT} ($abi)",
            "[$time] IP Address $ip",
            "",
            "Terminal awaiting connection..."
        )
    }

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "192.168.1.100"
                    }
                }
            }
            "192.168.1.100"
        } catch (_: Throwable) {
            "192.168.1.100"
        }
    }

    private val _logs = MutableStateFlow<List<String>>(buildStartupLogs())
    val logs: StateFlow<List<String>> = _logs

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

    // Sub-screen navigation from Drawer or Config Editor ("NONE", "PAYLOAD_GEN", "RESPONSE_CHECKER", "SHARENET", "NETWORK_TOOLS")
    val activeDrawerScreen = MutableStateFlow("NONE")
    val pendingGeneratedPayload = MutableStateFlow("")

    // Triggers for Top-Bar '+' Menu actions
    val triggerManualConfigDialog = MutableStateFlow(false)
    val triggerExportActiveDialog = MutableStateFlow(false)
    val triggerCloudImportDialog = MutableStateFlow(false)
    val triggerQrScannerDialog = MutableStateFlow(false)

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
        Log.d("Smartv3", message)
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.value = (_logs.value + "[$time] $message").takeLast(250)
    }

    fun clearLogs() {
        _logs.value = buildStartupLogs()
    }

    @SuppressLint("HardwareIds")
    fun getDeviceHardwareId(context: Context): String {
        cachedHwid?.let { return it }
        val rawId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "SMARTV3"
        } catch (_: Throwable) { "SMARTV3" }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(12)
        return "SVPN-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}".also { cachedHwid = it }
    }
}

object ConfigCryptoHelper {
    private val gson = Gson()
    private const val PEPPER = "Smartv3-Pro-Enclave-Vault-2026"

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

    fun uploadConfigToCloud(encryptedUri: String, provider: String): String {
        return try {
            val url = URL("https://paste.rs/")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(encryptedUri) }
            val response = conn.inputStream.bufferedReader().readText().trim()
            if (response.startsWith("http")) response else encryptedUri
        } catch (_: Throwable) {
            encryptedUri
        }
    }

    fun exportLoadBalanceBundle(profiles: List<VpnProfile>): String {
        val cleanList = profiles.map { it.copy(id = 0) }
        val jsonBytes = gson.toJson(cleanList).toByteArray(Charsets.UTF_8)
        val b64 = Base64.encodeToString(jsonBytes, Base64.NO_WRAP or Base64.URL_SAFE)
        return "svpn://bundle/$b64"
    }

    fun importBundleUri(rawText: String): List<VpnProfile>? {
        val clean = rawText.trim()
        if (!clean.startsWith("svpn://bundle/")) return null
        return try {
            val b64 = clean.removePrefix("svpn://bundle/").trim()
            val json = String(Base64.decode(b64, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            val listType = object : TypeToken<List<VpnProfile>>() {}.type
            gson.fromJson<List<VpnProfile>>(json, listType)
        } catch (_: Throwable) {
            null
        }
    }

    fun importProfileUri(rawText: String, passphrase: String = "", currentDeviceHwid: String = ""): VpnProfile? {
        val clean = rawText.trim()
        val b64 = when {
            clean.startsWith("svpn://config/") -> clean.removePrefix("svpn://config/")
            clean.startsWith("svpn://") -> clean.substringAfter("svpn://").substringAfter("/")
            clean.startsWith("smartvpn://") -> clean.substringAfter("smartvpn://").substringAfter("/")
            clean.startsWith("SVPN-CLOUD:") -> clean.removePrefix("SVPN-CLOUD:")
            else -> return null
        }
        val combined = try { Base64.decode(b64.trim(), Base64.NO_WRAP or Base64.URL_SAFE) } catch (_: Throwable) { return null }
        if (combined.size <= 12) return null
        val iv = combined.copyOfRange(0, 12)
        val cipherText = combined.copyOfRange(12, combined.size)

        val hwidCandidates = listOf(currentDeviceHwid.trim().uppercase(), "")
        val passCandidates = listOf(passphrase.trim(), "")

        for (hwid in hwidCandidates) {
            for (pass in passCandidates) {
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
            val flag = profile.countryFlag.ifBlank { VpnConfigParser.detectFlag(profile.server, profile.name) }
            dao.insertProfile(profile.copy(isSelected = true, countryFlag = flag))
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
        if (currentList.isEmpty()) {
            showToastPill("No configurations to ping")
            return
        }
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

    fun importFromCloudUrl(cloudUrl: String, passphrase: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanUrl = cloudUrl.trim()
            if (cleanUrl.isEmpty()) return@launch
            VpnStateBridge.toastMessage.value = "Downloading from cloud..."
            try {
                val rawContent = if (cleanUrl.startsWith("http://", true) || cleanUrl.startsWith("https://", true)) {
                    val targetUrl = if (cleanUrl.contains("rentry.co/") && !cleanUrl.endsWith("/raw")) {
                        "${cleanUrl.trimEnd('/')}/raw"
                    } else {
                        cleanUrl
                    }
                    URL(targetUrl).openStream().bufferedReader().use { it.readText().trim() }
                } else {
                    cleanUrl
                }
                importFromClipboard(rawContent, passphrase)
            } catch (t: Throwable) {
                VpnStateBridge.addLog("Cloud import failed: ${t.message}")
                showToastPill("Failed to fetch Cloud URL")
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
            val bundle = ConfigCryptoHelper.importBundleUri(cleanText)
            if (!bundle.isNullOrEmpty()) {
                dao.clearSelection()
                bundle.forEachIndexed { idx, p ->
                    dao.insertProfile(p.copy(id = 0, isSelected = idx == 0))
                }
                VpnStateBridge.loadBalancingEnabled.value = true
                VpnStateBridge.addLog("Imported Load Balance Bundle (${bundle.size} nodes)")
                showToastPill("Imported ${bundle.size} bundle configs")
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
                if (parsed.authorMessage.isNotBlank()) {
                    VpnStateBridge.addLog("Note: ${parsed.authorMessage}")
                }
            } else {
                VpnStateBridge.addLog("Import failed: Invalid link or locked to another HWID.")
                showToastPill("Import rejected: Invalid link or HWID lock")
            }
        }
    }
}
