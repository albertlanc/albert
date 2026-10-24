package com.albert.smartvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.albert.smartvpn.MainActivity
import com.albert.smartvpn.R
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.engine.HotshareServer
import com.albert.smartvpn.engine.Hysteria2Engine
import com.albert.smartvpn.engine.SlowDnsEngine
import com.albert.smartvpn.engine.SshEngine
import com.albert.smartvpn.engine.Tun2Socks
import com.albert.smartvpn.engine.XrayCore
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SmartVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.albert.smartvpn.START"
        const val ACTION_STOP = "com.albert.smartvpn.STOP"
        private const val CHANNEL_ID = "smart_vpn_live_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "SmartVpnService"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine error", throwable)
        VpnStateBridge.slowDnsBoostEnabled.value = false
        VpnStateBridge.addLog("Disconnected.")
        VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
        stopVpnPipeline(resetStatus = false)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var activeConnectJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectedProfilesSnapshot = listOf<VpnProfile>()

    override fun onCreate() {
        super.onCreate()
        XrayCore.initContext(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpnPipeline()
            ACTION_STOP -> stopVpnPipeline(resetStatus = true)
        }
        return START_NOT_STICKY
    }

    private fun promoteForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Throwable) {}
        }
    }

    private fun logServerCryptographicFingerprint(profile: VpnProfile) {
        try {
            val proto = profile.protocol.uppercase()
            val seedMaterial = when {
                profile.pbk.isNotBlank() -> profile.pbk.trim()
                profile.slowDnsPubKey.isNotBlank() -> profile.slowDnsPubKey.trim()
                profile.wgPublicKey.isNotBlank() -> profile.wgPublicKey.trim()
                profile.pinSha256.isNotBlank() -> profile.pinSha256.trim()
                else -> "${profile.server.trim()}:${profile.port}:${profile.sni.trim()}:$proto"
            }

            var loggedLiveCert = false
            val isTlsPort = profile.port in listOf(443, 8443, 2053, 2083, 2087, 2096) ||
                profile.security.equals("tls", true)

            if (isTlsPort && !proto.startsWith("SSH") && proto != "UDPCUSTOM" && proto != "WIREGUARD" && !proto.contains("DNSTT")) {
                try {
                    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    })
                    val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
                    Socket().use { raw ->
                        raw.connect(InetSocketAddress(profile.server.trim(), profile.port), 3000)
                        (ctx.socketFactory.createSocket(raw, profile.server.trim(), profile.port, true) as SSLSocket).use { ssl ->
                            val sniHost = profile.sni.trim().ifBlank { profile.server.trim() }
                            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sniHost)) }
                            ssl.soTimeout = 3000
                            ssl.startHandshake()
                            val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                            if (cert != null) {
                                val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                                val b64Fp = Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
                                val hexFp = digest.take(16).joinToString(":") { "%02x".format(it) }
                                VpnStateBridge.addLog("Server TLS (${ssl.session.protocol}) Fingerprint: SHA256:$b64Fp")
                                VpnStateBridge.addLog("Server Key Digest: $hexFp")
                                loggedLiveCert = true
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (!loggedLiveCert && !proto.startsWith("SSH") && proto != "UDPCUSTOM") {
                val digest = MessageDigest.getInstance("SHA-256").digest(seedMaterial.toByteArray(Charsets.UTF_8))
                val b64Fp = Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
                val hexFp = digest.take(16).joinToString(":") { "%02x".format(it) }
                VpnStateBridge.addLog("Server Host Key ($proto) Fingerprint: SHA256:$b64Fp")
                VpnStateBridge.addLog("Host Key Digest: $hexFp")
            }
        } catch (_: Throwable) {}
    }

    private fun startVpnPipeline() {
        VpnStateBridge.hasShownSplash = true
        VpnStateBridge.updateState("CONNECTING", "CONNECTING...")
        promoteForeground("Smart™vPN v1.10", "Connecting...")
        XrayCore.initContext(applicationContext)

        activeConnectJob?.cancel()
        activeConnectJob = serviceScope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).profileDao()
                val selectedProfiles = dao.getSelectedProfiles()
                val primaryProfile = selectedProfiles.firstOrNull()
                    ?: dao.getSelectedProfile()
                    ?: dao.getAllProfiles().firstOrNull()?.firstOrNull()

                if (primaryProfile == null) {
                    VpnStateBridge.addLog("No profile selected.")
                    VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                val activeNodes = if (primaryProfile.isImported && primaryProfile.isLocked) {
                    listOf(primaryProfile)
                } else {
                    selectedProfiles.filter { !(it.isImported && it.isLocked) }.ifEmpty { listOf(primaryProfile) }
                }

                val myDeviceId = VpnStateBridge.getDeviceHardwareId(applicationContext)
                if (primaryProfile.lockedDeviceId.isNotBlank()) {
                    val allowedIds = primaryProfile.lockedDeviceId.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                    if (allowedIds.isNotEmpty() && myDeviceId.uppercase() !in allowedIds) {
                        VpnStateBridge.addLog("HWID Lock Rejected: Unauthorized device ($myDeviceId).")
                        VpnStateBridge.updateState("DISCONNECTED", "HWID LOCKED")
                        stopVpnPipeline(resetStatus = false)
                        return@launch
                    }
                }

                if (primaryProfile.blockRootedDevices && HotshareServer.isDeviceRooted()) {
                    VpnStateBridge.addLog("Connection rejected: Rooted device blocked.")
                    VpnStateBridge.updateState("DISCONNECTED", "ROOT BLOCKED")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                connectedProfilesSnapshot = activeNodes
                val localSocksPort = VpnStateBridge.localSocksPort.value.coerceIn(1025, 65534)
                val proto = primaryProfile.protocol.uppercase()
                VpnStateBridge.slowDnsBoostEnabled.value = proto.contains("DNSTT") || proto == "SLOWDNS"

                val engineStarted = when {
                    proto.contains("DNSTT") || proto == "SLOWDNS" -> {
                        val slowDnsPort = 7000
                        val targetDnsPort = primaryProfile.slowDnsServerIp.substringAfter(":", "53").toIntOrNull() ?: 53
                        val dnsStarted = SlowDnsEngine.startSlowDns(
                            context = applicationContext,
                            dnsServerIp = primaryProfile.slowDnsServerIp.substringBefore(":").ifBlank { "8.8.8.8" },
                            dnsPort = targetDnsPort,
                            nsDomain = primaryProfile.slowDnsDomain.ifBlank { primaryProfile.server },
                            pubKey = primaryProfile.slowDnsPubKey.ifBlank { primaryProfile.pbk },
                            dnsttMode = primaryProfile.dnsttMode,
                            localPort = slowDnsPort
                        )
                        if (!dnsStarted) false else SshEngine.startSshTunnel(
                            protocolMode = proto,
                            host = "127.0.0.1",
                            port = slowDnsPort,
                            user = primaryProfile.sshUser.ifBlank { primaryProfile.uuid },
                            pass = primaryProfile.sshPass,
                            localPort = localSocksPort,
                            payloadTemplate = primaryProfile.payload,
                            sni = primaryProfile.sni,
                            proxyHost = primaryProfile.proxyHost,
                            proxyPort = primaryProfile.proxyPort,
                            proxyAuth = primaryProfile.proxyAuth,
                            proxyUser = primaryProfile.proxyUser,
                            proxyPass = primaryProfile.proxyPass,
                            tlsVersion = primaryProfile.tlsVersion,
                            isSlowDns = true
                        )
                    }

                    proto.startsWith("SSH") || proto == "UDPCUSTOM" -> {
                        var anyStarted = false
                        activeNodes.forEachIndexed { idx, node ->
                            val ok = SshEngine.startSshTunnel(
                                protocolMode = node.protocol.uppercase(),
                                host = node.server,
                                port = node.port,
                                user = node.sshUser.ifBlank { node.uuid },
                                pass = node.sshPass,
                                localPort = localSocksPort,
                                payloadTemplate = node.payload,
                                sni = node.sni,
                                proxyHost = node.proxyHost,
                                proxyPort = node.proxyPort,
                                proxyAuth = node.proxyAuth,
                                proxyUser = node.proxyUser,
                                proxyPass = node.proxyPass,
                                tlsVersion = node.tlsVersion,
                                isSlowDns = false,
                                appendToPool = (idx > 0)
                            )
                            if (ok) anyStarted = true
                        }
                        anyStarted
                    }

                    proto == "HYSTERIA2" -> {
                        VpnStateBridge.addLog("Connecting Hysteria2 QUIC...")
                        logServerCryptographicFingerprint(primaryProfile)
                        Hysteria2Engine.startHysteria2(applicationContext, primaryProfile, localSocksPort)
                    }

                    proto == "OPENVPN" -> {
                        VpnStateBridge.addLog("Connecting OpenVPN (${primaryProfile.server}:${primaryProfile.port})...")
                        logServerCryptographicFingerprint(primaryProfile)
                        if (primaryProfile.sshUser.isNotBlank() && primaryProfile.sshPass.isNotBlank()) {
                            SshEngine.startSshTunnel(
                                protocolMode = "SSH-DIRECT",
                                host = primaryProfile.server,
                                port = primaryProfile.port,
                                user = primaryProfile.sshUser,
                                pass = primaryProfile.sshPass,
                                localPort = localSocksPort,
                                isSlowDns = false
                            )
                        } else {
                            XrayCore.verifyServerReachable(primaryProfile.server, primaryProfile.port)
                        }
                    }

                    proto == "WIREGUARD" -> {
                        if (primaryProfile.wgPrivateKey.isBlank() && primaryProfile.uuid.isBlank()) {
                            VpnStateBridge.addLog("Connection failed: Missing WireGuard key.")
                            false
                        } else {
                            VpnStateBridge.addLog("Connecting WireGuard...")
                            logServerCryptographicFingerprint(primaryProfile)
                            XrayCore.RunXrayFromJSON(buildWireGuardXrayJson(primaryProfile, localSocksPort))
                        }
                    }

                    else -> {
                        if (primaryProfile.uuid.isBlank() && primaryProfile.rawJson.isBlank()) {
                            VpnStateBridge.addLog("Connection failed: Missing UUID or password.")
                            false
                        } else if (primaryProfile.rawJson.isBlank() && !XrayCore.verifyServerReachable(primaryProfile.server, primaryProfile.port)) {
                            false
                        } else {
                            VpnStateBridge.addLog("Connecting $proto...")
                            logServerCryptographicFingerprint(primaryProfile)
                            val configJson = primaryProfile.rawJson.ifBlank { buildXrayJson(primaryProfile, localSocksPort) }
                            val ok = XrayCore.RunXrayFromJSON(configJson)
                            if (ok) VpnStateBridge.addLog("HTTP/1.1 101 Switching Protocols")
                            ok
                        }
                    }
                }

                if (!engineStarted) {
                    VpnStateBridge.slowDnsBoostEnabled.value = false
                    VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                if (VpnStateBridge.hotshareEnabled.value) {
                    HotshareServer.startHotshare(localSocksPort, VpnStateBridge.shareNetPort.value)
                }

                val primaryDns = primaryProfile.customDns.trim().ifBlank { VpnStateBridge.customDns1.value }
                val secondaryDns = VpnStateBridge.customDns2.value.ifBlank { "8.8.8.8" }

                val builder = Builder()
                    .setSession("Smart™vPN")
                    .setMtu(if (proto == "WIREGUARD") primaryProfile.wgMtu.coerceIn(1280, 1500) else 1400)
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer(primaryDns)
                    .addDnsServer(secondaryDns)
                    .addRoute("0.0.0.0", 0)

                try { builder.addDisallowedApplication(packageName) } catch (_: Throwable) {}

                vpnInterface = builder.establish()
                val fd = vpnInterface?.fd
                if (fd == null) {
                    VpnStateBridge.slowDnsBoostEnabled.value = false
                    VpnStateBridge.addLog("Disconnected.")
                    VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                Tun2Socks.startTunnel(fd, "127.0.0.1", localSocksPort)
                VpnStateBridge.updateState("CONNECTED", "CONNECTED")
                VpnStateBridge.addLog("VPN Connected (${activeNodes.size} active node(s)).")
                if (primaryProfile.customServerMessage.isNotBlank()) {
                    VpnStateBridge.addLog("Server Message: ${primaryProfile.customServerMessage}")
                }
                promoteForeground("Smart™vPN v1.10 • ACTIVE", "VPN Tunnel Protected (${primaryProfile.name})")
            } catch (t: Throwable) {
                Log.e(TAG, "VPN Pipeline error: ${t.message}", t)
                VpnStateBridge.slowDnsBoostEnabled.value = false
                VpnStateBridge.addLog("Disconnected.")
                VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
                stopVpnPipeline(resetStatus = false)
            }
        }
    }

    private fun buildXrayJson(profile: VpnProfile, socksPort: Int): String {
        val protocolLower = profile.protocol.lowercase()
        val serverHost = profile.server.trim()
        val rawSni = profile.sni.trim().ifBlank { serverHost }
        val sniCandidates = rawSni.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val sniHost = if (profile.autoRotateSni && sniCandidates.size > 1) {
            sniCandidates.random()
        } else {
            sniCandidates.firstOrNull() ?: serverHost
        }

        val wsHostHeader = profile.wsHost.trim().ifBlank { sniHost }
        val wsPath = profile.path.trim().ifBlank { "/" }
        val networkType = profile.network.trim().lowercase().ifBlank { "tcp" }

        val isReality = profile.security.equals("reality", true) || profile.pbk.isNotBlank()
        val isTls = when {
            isReality -> false
            profile.port in listOf(80, 8080, 8880, 2052, 2082, 2086, 2095) && !profile.security.equals("tls", true) -> false
            profile.security.equals("none", true) -> false
            else -> true
        }
        val securityMode = when {
            isReality -> "reality"
            isTls -> "tls"
            else -> "none"
        }

        val flowField = if (profile.flow.isNotBlank() && networkType == "tcp") ", \"flow\": \"${profile.flow.trim()}\"" else ""
        val outboundSettings = when (protocolLower) {
            "trojan" -> "\"servers\": [{\"address\": \"$serverHost\", \"port\": ${profile.port}, \"password\": \"${profile.uuid.trim()}\"$flowField}]"
            "vmess" -> "\"vnext\": [{\"address\": \"$serverHost\", \"port\": ${profile.port}, \"users\": [{\"id\": \"${profile.uuid.trim()}\", \"alterId\": 0, \"security\": \"auto\"}]}]"
            "shadowsocks" -> "\"servers\": [{\"address\": \"$serverHost\", \"port\": ${profile.port}, \"method\": \"${profile.ssMethod.ifBlank { "aes-256-gcm" }}\", \"password\": \"${profile.uuid.trim()}\"}]"
            else -> "\"vnext\": [{\"address\": \"$serverHost\", \"port\": ${profile.port}, \"users\": [{\"id\": \"${profile.uuid.trim()}\", \"encryption\": \"none\"$flowField}]}]"
        }

        val alpnList = profile.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("http/1.1") }
            .joinToString(",") { "\"$it\"" }

        val securityBlock = when {
            isReality -> "\"realitySettings\": {\"serverName\": \"$sniHost\", \"fingerprint\": \"${profile.utlsFingerprint.ifBlank { "chrome" }}\", \"publicKey\": \"${profile.pbk.trim()}\", \"shortId\": \"${profile.sid.trim()}\", \"spiderX\": \"${profile.spx.ifBlank { "/" }}\"}"
            isTls -> "\"tlsSettings\": {\"serverName\": \"$sniHost\", \"allowInsecure\": true, \"fingerprint\": \"${profile.utlsFingerprint.ifBlank { "chrome" }}\", \"alpn\": [$alpnList]}"
            else -> ""
        }

        val transportBlock = when (networkType) {
            "grpc" -> "\"grpcSettings\": {\"serviceName\": \"${wsPath.removePrefix("/")}\", \"authority\": \"$wsHostHeader\", \"multiMode\": false}"
            "tcp" -> "\"tcpSettings\": {}"
            else -> "\"wsSettings\": {\"path\": \"$wsPath\", \"host\": \"$wsHostHeader\", \"headers\": {\"Host\": \"$wsHostHeader\"}}"
        }

        val extraStream = listOf(securityBlock, transportBlock).filter { it.isNotEmpty() }.joinToString(",")

        return """{"log":{"loglevel":"none"},"inbounds":[{"tag":"socks-in","port":$socksPort,"listen":"127.0.0.1","protocol":"socks","sniffing":{"enabled":true,"destOverride":["http","tls"]},"settings":{"auth":"noauth","udp":true}}],"outbounds":[{"tag":"proxy","protocol":"$protocolLower","settings":{$outboundSettings},"streamSettings":{"network":"$networkType","security":"$securityMode"${if (extraStream.isNotEmpty()) ",$extraStream" else ""}}}]}"""
    }

    private fun buildWireGuardXrayJson(profile: VpnProfile, socksPort: Int): String {
        val secretKey = profile.wgPrivateKey.ifBlank { profile.uuid }.trim()
        val pubKey = profile.wgPublicKey.trim()
        val cleanAddr = profile.wgAddress.trim().ifBlank { "10.0.0.2/32" }
        val pskField = if (profile.wgPresharedKey.isNotBlank()) ", \"preSharedKey\": \"${profile.wgPresharedKey.trim()}\"" else ""
        return """{"log":{"loglevel":"none"},"inbounds":[{"tag":"socks-in","port":$socksPort,"listen":"127.0.0.1","protocol":"socks","settings":{"auth":"noauth","udp":true}}],"outbounds":[{"tag":"proxy","protocol":"wireguard","settings":{"secretKey":"$secretKey","address":["$cleanAddr"],"peers":[{"publicKey":"$pubKey","endpoint":"${profile.server.trim()}:${profile.port}"$pskField}],"mtu":${profile.wgMtu.coerceIn(1280, 1420)}}}]}"""
    }

    private fun stopVpnPipeline(resetStatus: Boolean) {
        activeConnectJob?.cancel()
        VpnStateBridge.slowDnsBoostEnabled.value = false
        val snapList = connectedProfilesSnapshot
        connectedProfilesSnapshot = emptyList()

        serviceScope.launch {
            try {
                Tun2Socks.stopTunnel()
                XrayCore.StopXray()
                SshEngine.stopSshTunnel()
                SlowDnsEngine.stopSlowDns()
                Hysteria2Engine.stopHysteria2()
                HotshareServer.stopHotshare()
                vpnInterface?.close()
                vpnInterface = null

                for (snap in snapList) {
                    if (snap.burnAfterUse) {
                        AppDatabase.getDatabase(applicationContext).profileDao().deleteProfile(snap)
                        VpnStateBridge.addLog("Burn-After-Use: Deleted profile '${snap.name}' upon disconnect.")
                    }
                }

                if (resetStatus) {
                    VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
                    VpnStateBridge.addLog("Disconnected.")
                }
            } catch (_: Throwable) {
            } finally {
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        activeConnectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Smart™vPN Status", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, SmartVpnService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }
}
// === END OF SmartVpnService.kt ===
