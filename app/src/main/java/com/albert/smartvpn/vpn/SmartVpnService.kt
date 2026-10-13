package com.albert.smartvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.albert.smartvpn.MainActivity
import com.albert.smartvpn.data.AppDatabase
import com.albert.smartvpn.engine.PayloadGenerator
import com.albert.smartvpn.engine.SshEngine
import com.albert.smartvpn.engine.Tun2Socks
import com.albert.smartvpn.engine.XrayCore
import com.albert.smartvpn.utils.XrayConfigGenerator
import kotlinx.coroutines.*

class SmartVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private val tunAddress = "10.0.0.2"
    private val tunRoute = "0.0.0.0"
    private val socksPort = 10808
    private val socksAddress = "127.0.0.1"

    companion object {
        const val ACTION_START = "com.albert.smartvpn.START"
        const val ACTION_STOP = "com.albert.smartvpn.STOP"
        private const val CHANNEL_ID = "smart_vpn_status_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "SmartVpnService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpnPipeline()
            ACTION_STOP -> stopVpnPipeline()
        }
        return START_STICKY
    }

    private fun startVpnPipeline() {
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to secure tunnel..."))

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val activeProfile = db.profileDao().getSelectedProfile() 
                    ?: throw IllegalStateException("No active profile selected.")

                Log.d(TAG, "Selected profile: ${activeProfile.name} (${activeProfile.protocol})")

                // Step 1: Start targeted engine
                if (activeProfile.protocol.equals("ssh", ignoreCase = true)) {
                    val rawPayload = if (activeProfile.path.isNotEmpty()) {
                        PayloadGenerator.generatePayload(
                            template = activeProfile.path,
                            targetHost = activeProfile.server,
                            targetPort = activeProfile.port,
                            sni = activeProfile.sni
                        )
                    } else ""

                    val sshSuccess = SshEngine.startSshTunnel(
                        host = activeProfile.server,
                        port = activeProfile.port,
                        user = activeProfile.uuid,
                        pass = activeProfile.pbk, // stored in pbk field for SSH
                        localPort = socksPort,
                        payloadTemplate = rawPayload,
                        sni = activeProfile.sni
                    )
                    if (!sshSuccess) throw Exception("SSH Tunnel failed to establish.")
                } else {
                    // Protocol is VLESS, VMess, or Trojan -> Xray Core
                    val xrayConfig = XrayConfigGenerator.buildJson(activeProfile, socksPort)
                    val xraySuccess = XrayCore.RunXrayFromJSON(xrayConfig)
                    if (!xraySuccess) throw Exception("Xray Core failed to initialize.")
                }

                // Step 2: Establish TUN Interface
                val builder = Builder()
                    .setSession("Smart VPN - ${activeProfile.name}")
                    .addAddress(tunAddress, 24)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute(tunRoute, 0)
                    .setMtu(1500)
                    .addDisallowedApplication(packageName) // Prevent routing loop

                vpnInterface = builder.establish() ?: throw Exception("Failed to establish TUN interface.")
                val fd = vpnInterface!!.fd

                // Step 3: Attach TUN to SOCKS5 forwarder
                val tunSuccess = Tun2Socks.startTunnel(fd, socksAddress, socksPort)
                if (!tunSuccess) throw Exception("Packet forwarder failed to attach to TUN.")

                // Update notification state
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification("Connected: ${activeProfile.name}"))
                Log.d(TAG, "Smart VPN fully operational.")

            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error: ${e.message}")
                stopVpnPipeline()
            }
        }
    }

    private fun stopVpnPipeline() {
        serviceScope.cancel()

        Tun2Socks.stopTunnel()
        XrayCore.StopXray()
        SshEngine.stopSshTunnel()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN: ${e.message}")
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart VPN Connection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays persistent status when the VPN tunnel is running"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, SmartVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart VPN")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .build()
    }
}
