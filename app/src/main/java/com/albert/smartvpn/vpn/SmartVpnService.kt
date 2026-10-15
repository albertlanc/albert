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
        VpnStateBridge.addLog("Engine Error: ${throwable.message}")
        VpnStateBridge.updateState("DISCONNECTED", "Error")
        stopVpnPipeline(resetStatus = false)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {}
        }
    }

    private fun startVpnPipeline() {
        VpnStateBridge.hasShownSplash = true
        VpnStateBridge.updateState("CONNECTING", "Initializing...")
        promoteForeground("Smart VPN Ultra — Connecting", "Negotiating multi-protocol tunnel...")

        serviceScope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).profileDao()
                val profile = dao.getSelectedProfile()
                    ?: dao.getAllProfiles().firstOrNull()?.firstOrNull()

                if (profile == null) {
                    VpnStateBridge.addLog("Error: No configuration profile found.")
                    VpnStateBridge.updateState("DISCONNECTED", "No Profile")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                val myDeviceId = VpnStateBridge.getDeviceHardwareId(applicationContext)
                if (profile.lockedDeviceId.isNotBlank() &&
                    !profile.lockedDeviceId.trim().equals(myDeviceId, ignoreCase = true)
                ) {
                    VpnStateBridge.addLog("HWID Security Rejected! Config is locked to ${profile.lockedDeviceId}")
                    VpnStateBridge.updateState("DISCONNECTED", "HWID Locked")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                VpnStateBridge.addLog("Starting profile: ${profile.name} (${profile.protocol})")
                val localSocksPort = VpnStateBridge.localSocksPort.value.coerceIn(1025, 65534)
                val proto = profile.protocol.uppercase()

                val engineStarted = when {
                    proto == "SLOWDNS" -> {
                        val slowDnsPort = 7000
                        val dnsStarted = SlowDnsEngine.startSlowDns(
                            context = applicationContext,
                            dnsServerIp = profile.slowDnsServerIp.ifBlank { "8.8.8.8" },
                            dnsPort = 53,
                            nsDomain = profile.slowDnsDomain,
                            pubKey = profile.slowDnsPubKey.ifBlank { profile.pbk },
                            localPort = slowDnsPort
                        )
                        if (!dnsStarted) {
                            false
                        } else {
                            SshEngine.startSshTunnel(
                                protocolMode = "SLOWDNS",
                                host = "127.0.0.1",
                                port = slowDnsPort,
                                user = profile.sshUser.ifBlank { profile.uuid },
                                pass = profile.sshPass,
                                localPort = localSocksPort,
                                isSlowDns = true
                            )
                        }
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
                        Hysteria2Engine.startHysteria2(
                            context = applicationContext,
                            profile = profile,
                            localSocksPort = localSocksPort
                        )
                    }

                    proto == "WIREGUARD" -> {
                        if (profile.wgPrivateKey.isBlank() || profile.wgPublicKey.isBlank()) {
                            VpnStateBridge.addLog("WireGuard Error: Private Key or Peer Public Key is empty!")
                            false
                        } else {
                            val wgJson = buildWireGuardXrayJson(profile, localSocksPort)
                            XrayCore.RunXrayFromJSON(wgJson)
                        }
                    }

                    else -> {
                        if (profile.uuid.isBlank()) {
                            VpnStateBridge.addLog("Error: UUID / Password is empty for ${profile.protocol}!")
                            false
                        } else if (!XrayCore.verifyServerReachable(profile.server, profile.port)) {
                            false
                        } else {
                            val configJson = profile.rawJson.ifBlank { buildXrayJson(profile, localSocksPort) }
                            XrayCore.RunXrayFromJSON(configJson)
                        }
                    }
                }

                if (!engineStarted) {
                    VpnStateBridge.addLog("Connection failed: Verify credentials, SNI/Payload, or endpoint.")
                    VpnStateBridge.updateState("DISCONNECTED", "Auth / Conn Failed")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                if (VpnStateBridge.hotshareEnabled.value) {
                    HotshareServer.startHotshare(localSocksPort)
                }

                VpnStateBridge.updateState("CONNECTING", "Opening TUN...")
                val primaryDns = profile.customDns.trim().ifBlank { VpnStateBridge.customDns1.value }
                val secondaryDns = VpnStateBridge.customDns2.value.ifBlank { "8.8.8.8" }

                val builder = Builder()
                    .setSession("Smart VPN Ultra - ${profile.name}")
                    .setMtu(if (proto == "WIREGUARD") profile.wgMtu.coerceIn(1280, 1500) else 1500)
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer(primaryDns)
                    .addDnsServer(secondaryDns)
                    .addRoute("0.0.0.0", 0)

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Throwable) {}

                vpnInterface = builder.establish()
                val fd = vpnInterface?.fd
                if (fd == null) {
                    VpnStateBridge.addLog("Error: Could not open Android VPN TUN interface.")
                    VpnStateBridge.updateState("DISCONNECTED", "TUN Error")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                Tun2Socks.startTunnel(fd, "127.0.0.1", localSocksPort)

                VpnStateBridge.updateState("CONNECTED", "Protected")
                VpnStateBridge.addLog("VPN CONNECTED! Traffic routed via ${profile.name} (${profile.protocol}).")

                promoteForeground(
                    "Smart VPN Ultra • Connected",
                    "${profile.name} (${profile.protocol}) • Active Tunnel"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "VPN Pipeline error: ${t.message}", t)
                VpnStateBridge.addLog("Pipeline Error: ${t.message}")
                VpnStateBridge.updateState("DISCONNECTED", "Failed")
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

        val isReality = profile.security.equals("reality", ignoreCase = true) || profile.pbk.isNotBlank()
        val isTls = when {
            isReality -> false
            profile.port in listOf(80, 8080, 8880, 2052, 2082, 2086, 209
