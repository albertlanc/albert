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
        VpnStateBridge.updateState("DISCONNECTED", "Error")
        stopVpnPipeline(resetStatus = false)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var activeConnectJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
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
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Throwable) {}
        }
    }

    private fun startVpnPipeline() {
        VpnStateBridge.hasShownSplash = true
        VpnStateBridge.updateState("CONNECTING", "Connecting...")
        promoteForeground("Smart VPN Ultra", "Connecting...")

        activeConnectJob?.cancel()
        activeConnectJob = serviceScope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).profileDao()
                val profile = dao.getSelectedProfile()
                    ?: dao.getAllProfiles().firstOrNull()?.firstOrNull()

                if (profile == null) {
                    VpnStateBridge.addLog("No profile selected.")
                    VpnStateBridge.updateState("DISCONNECTED", "No Profile")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                val myDeviceId = VpnStateBridge.getDeviceHardwareId(applicationContext)
                if (profile.lockedDeviceId.isNotBlank() &&
                    !profile.lockedDeviceId.trim().equals(myDeviceId, ignoreCase = true)
                ) {
                    VpnStateBridge.addLog("HWID Lock Rejected.")
                    VpnStateBridge.updateState("DISCONNECTED", "HWID Locked")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                val localSocksPort = VpnStateBridge.localSocksPort.value.coerceIn(1025, 65534)
                val proto = profile.protocol.uppercase()

                // Auto-enable SlowDNS Boost ONLY when a SLOWDNS profile is connecting
                VpnStateBridge.slowDnsBoostEnabled.value = (proto == "SLOWDNS")

                val engineStarted = when {
                    proto == "SLOWDNS" -> {
                        val slowDnsPort = 7000
                        val targetDnsPort = if (profile.port in 1..65535) profile.port else 53
                        val dnsStarted = SlowDnsEngine.startSlowDns(
                            context = applicationContext,
                            dnsServerIp = profile.slowDnsServerIp.ifBlank { "8.8.8.8" },
                            dnsPort = targetDnsPort,
                            nsDomain = profile.slowDnsDomain,
                            pubKey = profile.slowDnsPubKey.ifBlank { profile.pbk },
                            localPort = slowDnsPort
                        )
                        if (!dnsStarted) false else SshEngine.startSshTunnel(
                            protocolMode = "SLOWDNS",
                            host = "127.0.0.1",
                            port = slowDnsPort,
                            user = profile.sshUser.ifBlank { profile.uuid },
                            pass = profile.sshPass,
                            localPort = localSocksPort,
                            isSlowDns = true
                        )
                    }
                    proto.contains("SSH") -> {
                        SshEngine.startSshTunnel(
                            protocolMode = proto,
                            host = profile.server,
                            port = profile.port,
                            user = profile.sshUser.ifBlank { profile.uuid },
                            pass = profile.sshPass,
                            localPort = localSocksPort,
                            payloadTemplate = profile.payload,
                            sni = profile.sni,
                            proxyHost = profile.proxyHost,
                            proxyPort = profile.proxyPort,
                            isSlowDns = false
                        )
                    }
                    proto == "HYSTERIA2" -> {
                        VpnStateBridge.addLog("Connecting...")
                        Hysteria2Engine.startHysteria2(applicationContext, profile, localSocksPort)
                    }
                    proto == "WIREGUARD" -> {
                        if (profile.wgPrivateKey.isBlank() || profile.wgPublicKey.isBlank()) {
                            VpnStateBridge.addLog("Connection failed.")
                            false
                        } else {
                            VpnStateBridge.addLog("Connecting...")
                            XrayCore.RunXrayFromJSON(buildWireGuardXrayJson(profile, localSocksPort))
                        }
                    }
                    else -> {
                        if (profile.uuid.isBlank() || !XrayCore.verifyServerReachable(profile.server, profile.port)) {
                            false
                        } else {
                            VpnStateBridge.addLog("Connecting...")
                            val configJson = profile.rawJson.ifBlank { buildXrayJson(profile, localSocksPort) }
                            val ok = XrayCore.RunXrayFromJSON(configJson)
                            if (ok) VpnStateBridge.addLog("HTTP/1.1 101 Switching Protocols")
                            ok
                        }
                    }
                }

                if (!engineStarted) {
                    VpnStateBridge.slowDnsBoostEnabled.value = false
                    VpnStateBridge.updateState("DISCONNECTED", "Disconnected")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                if (VpnStateBridge.hotshareEnabled.value) {
                    HotshareServer.startHotshare(localSocksPort)
                }

                val primaryDns = profile.customDns.trim().ifBlank { VpnStateBridge.customDns1.value }
                val secondaryDns = VpnStateBridge.customDns2.value.ifBlank { "8.8.8.8" }

                val builder = Builder()
                    .setSession("Smart VPN Ultra")
                    .setMtu(if (proto == "WIREGUARD") profile.wgMtu.coerceIn(1280, 1500) else 1500)
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
                    VpnStateBridge.updateState("DISCONNECTED", "TUN Error")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                Tun2Socks.startTunnel(fd, "127.0.0.1", localSocksPort)
                VpnStateBridge.updateState("CONNECTED", "Connected (${profile.protocol} + BBR)")
                VpnStateBridge.addLog("Connected (BBR Speed Active).")
                promoteForeground("Smart VPN Ultra • Connected", "Tunnel Active (${profile.name})")
            } catch (t: Throwable) {
                Log.e(TAG, "VPN Pipeline error: ${t.message}", t)
                VpnStateBridge.slowDnsBoostEnabled.value = false
                VpnStateBridge.addLog("Disconnected.")
                VpnStateBridge.updateState("DISCONNECTED", "Disconnected")
                stopVpnPipeline(resetStatus = false)
            }
        }
    }

    private fun buildXrayJson(profile: VpnProfile, socksPort: Int): String {
        val protocolLower = profile.protocol.lowercase()
        val serverHost = profile.server.trim()
        val sniHost = profile.sni.trim().ifBlank { serverHost }
        val wsPath = profile.path.trim().ifBlank { "/" }
        val networkType = profile.network.trim().lowercase().ifBlank { "ws" }

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

        val (outboundAddress, outboundPort) = if (protocolLower == "vless" && securityMode == "none") {
            Pair("127.0.0.1", XrayCore.startLocalPort80Relay(serverHost, profile.port))
        } else {
            Pair(serverHost, profile.port)
        }

        val flowField = if (profile.flow.isNotBlank() && networkType == "tcp") ", \"flow\": \"${profile.flow.trim()}\"" else ""
        val outboundSettings = when (protocolLower) {
            "trojan" -> "\"servers\": [{\"address\": \"$outboundAddress\", \"port\": $outboundPort, \"password\": \"${profile.uuid.trim()}\"}]"
            "vmess" -> "\"vnext\": [{\"address\": \"$outboundAddress\", \"port\": $outboundPort, \"users\": [{\"id\": \"${profile.uuid.trim()}\", \"alterId\": 0, \"security\": \"zero\"}]}]"
            else -> "\"vnext\": [{\"address\": \"$outboundAddress\", \"port\": $outboundPort, \"users\": [{\"id\": \"${profile.uuid.trim()}\", \"encryption\": \"none\"$flowField}]}]"
        }

        val verifyNames = if (!sniHost.equals(serverHost, true)) "$sniHost,$serverHost" else sniHost
        val securityBlock = when {
            isReality -> "\"realitySettings\": {\"serverName\": \"$sniHost\", \"fingerprint\": \"chrome\", \"publicKey\": \"${profile.pbk.trim()}\", \"shortId\": \"${profile.sid.trim()}\"},"
            isTls -> "\"tlsSettings\": {\"serverName\": \"$sniHost\", \"verifyPeerCertByName\": \"$verifyNames\", \"alpn\": [\"http/1.1\"]},"
            else -> ""
        }
        val transportBlock = when (networkType) {
            "grpc" -> "\"grpcSettings\": {\"serviceName\": \"${wsPath.removePrefix("/")}\"}"
            "tcp" -> "\"tcpSettings\": {}"
            else -> "\"wsSettings\": {\"path\": \"$wsPath\", \"host\": \"$sniHost\"}"
        }

        return """{"log":{"loglevel":"none"},"policy":{"levels":{"0":{"handshake":4,"connIdle":300,"uplinkOnly":1,"downlinkOnly":1,"bufferSize":10240}}},"inbounds":[{"tag":"socks-in","port":$socksPort,"listen":"127.0.0.1","protocol":"socks","settings":{"auth":"noauth","udp":true}}],"outbounds":[{"tag":"proxy","protocol":"$protocolLower","settings":{$outboundSettings},"streamSettings":{"network":"$networkType","security":"$securityMode",$securityBlock$transportBlock,"sockopt":{"tcpNoDelay":true}}}]}"""
    }

    private fun buildWireGuardXrayJson(profile: VpnProfile, socksPort: Int): String {
        val cleanAddr = profile.wgAddress.trim().ifBlank { "10.0.0.2/32" }
        val pskField = if (profile.wgPresharedKey.isNotBlank()) ", \"preSharedKey\": \"${profile.wgPresharedKey.trim()}\"" else ""
        return """{"log":{"loglevel":"none"},"inbounds":[{"tag":"socks-in","port":$socksPort,"listen":"127.0.0.1","protocol":"socks","settings":{"auth":"noauth","udp":true}}],"outbounds":[{"tag":"proxy","protocol":"wireguard","settings":{"secretKey":"${profile.wgPrivateKey.trim()}","address":["$cleanAddr"],"peers":[{"publicKey":"${profile.wgPublicKey.trim()}","endpoint":"${profile.server.trim()}:${profile.port}"$pskField}],"mtu":${profile.wgMtu.coerceIn(1280, 1500)}}}]}"""
    }

    private fun stopVpnPipeline(resetStatus: Boolean) {
        activeConnectJob?.cancel()
        VpnStateBridge.slowDnsBoostEnabled.value = false
        VpnStateBridge.pingResultText.value = "Tap Ping to test active tunnel latency & speed"
        serviceScope.launch {
            try {
                Tun2Socks.stopTunnel()
                XrayCore.StopXray()
                SshEngine.stopSshTunnel()
                SlowDnsEngine.stopSlowDns()
                Hysteria2Engine.stopHysteria2()
                vpnInterface?.close()
                vpnInterface = null
                if (resetStatus) {
                    VpnStateBridge.updateState("DISCONNECTED", "Idle")
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart VPN Status",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
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
            this, 1, Intent(this, SmartVpnService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
// === END OF FILE 9 ===
