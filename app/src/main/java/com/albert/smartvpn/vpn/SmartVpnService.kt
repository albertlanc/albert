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
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.data.VpnProfile
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
        private const val CHANNEL_ID = "smart_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SmartVpnService"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine error", throwable)
        VpnStateBridge.addLog("Fatal Engine Error: ${throwable.message}")
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

    private fun startVpnPipeline() {
        VpnStateBridge.updateState("CONNECTING", "Initializing...")
        val notification = buildNotification("Connecting Smart VPN...")
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
        } catch (t: Throwable) {
            Log.e(TAG, "Foreground start fallback: ${t.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {}
        }

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

                VpnStateBridge.addLog("Loaded profile: ${profile.name} (${profile.protocol})")
                val localSocksPort = 10808
                val proto = profile.protocol.uppercase()

                val engineStarted = if (proto.contains("SSH") || proto == "SLOWDNS") {
                    val targetHost = if (proto == "SLOWDNS" && profile.slowDnsServerIp.isNotBlank()) {
                        profile.slowDnsServerIp
                    } else {
                        profile.server
                    }
                    SshEngine.startSshTunnel(
                        host = targetHost,
                        port = profile.port,
                        user = profile.sshUser.ifBlank { profile.uuid },
                        pass = profile.sshPass,
                        localPort = localSocksPort,
                        payloadTemplate = profile.payload,
                        sni = profile.sni,
                        proxyHost = profile.proxyHost,
                        proxyPort = profile.proxyPort
                    )
                } else {
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

                // Strictly abort if login or engine handshake failed!
                if (!engineStarted) {
                    VpnStateBridge.addLog("Connection aborted: Authentication or engine handshake failed.")
                    VpnStateBridge.updateState("DISCONNECTED", "Auth / Conn Failed")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                VpnStateBridge.updateState("CONNECTING", "Opening TUN...")
                val builder = Builder()
                    .setSession(profile.name)
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer(profile.customDns.ifBlank { "1.1.1.1" })
                    .addRoute("0.0.0.0", 0)

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Throwable) {}

                vpnInterface = builder.establish()
                val fd = vpnInterface?.fd
                if (fd == null) {
                    VpnStateBridge.addLog("Error: Android OS failed to establish TUN interface.")
                    VpnStateBridge.updateState("DISCONNECTED", "TUN Failed")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                Tun2Socks.startTunnel(fd, "127.0.0.1", localSocksPort)

                VpnStateBridge.updateState("CONNECTED", "Protected")
                VpnStateBridge.addLog("Tunnel established! Connected to ${profile.name}.")

                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("Connected to ${profile.name}"))
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
        val sniHost = profile.sni.ifBlank { profile.server }
        val wsPath = profile.path.ifBlank { "/" }
        return """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [{
            "tag": "socks-in",
            "port": $socksPort,
            "listen": "127.0.0.1",
            "protocol": "socks",
            "settings": { "auth": "noauth", "udp": true }
          }],
          "outbounds": [{
            "tag": "proxy",
            "protocol": "$protocolLower",
            "settings": {
              "vnext": [{
                "address": "${profile.server}",
                "port": ${profile.port},
                "users": [{
                  "id": "${profile.uuid}",
                  "encryption": "none"
                }]
              }]
            },
            "streamSettings": {
              "network": "${profile.network.ifBlank { "ws" }}",
              "security": "${profile.security.ifBlank { "tls" }}",
              "tlsSettings": {
                "serverName": "$sniHost",
                "allowInsecure": true
              },
              "wsSettings": {
                "path": "$wsPath",
                "headers": { "Host": "$sniHost" }
              }
            }
          }]
        }
        """.trimIndent()
    }

    private fun stopVpnPipeline(resetStatus: Boolean) {
        serviceScope.launch {
            try {
                Tun2Socks.stopTunnel()
                XrayCore.StopXray()
                SshEngine.stopSshTunnel()
                vpnInterface?.close()
                vpnInterface = null
                if (resetStatus) {
                    VpnStateBridge.updateState("DISCONNECTED", "Idle")
                    VpnStateBridge.addLog("Tunnel disconnected.")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error stopping VPN: ${t.message}")
            } finally {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Throwable) {}
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart VPN")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
