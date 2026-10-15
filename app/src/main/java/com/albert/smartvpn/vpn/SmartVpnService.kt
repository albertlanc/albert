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
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {}
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
                val allProfiles = dao.getAllProfiles().firstOrNull().orEmpty()
                val checkedIds = VpnStateBridge.selectedProfileIds.value

                // Collect all multi-selected/cloned profiles
                val selectedCluster = allProfiles.filter { it.id in checkedIds || it.isSelected }
                    .ifEmpty { listOfNotNull(allProfiles.firstOrNull()) }

                val primaryProfile = selectedCluster.firstOrNull()
                if (primaryProfile == null) {
                    VpnStateBridge.addLog("No profile selected.")
                    VpnStateBridge.updateState("DISCONNECTED", "No Profile")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                val myDeviceId = VpnStateBridge.getDeviceHardwareId(applicationContext)
                if (primaryProfile.lockedDeviceId.isNotBlank() &&
                    !primaryProfile.lockedDeviceId.trim().equals(myDeviceId, ignoreCase = true)
                ) {
                    VpnStateBridge.addLog("HWID Lock Rejected.")
                    VpnStateBridge.updateState("DISCONNECTED", "HWID Locked")
                    stopVpnPipeline(resetStatus = false)
                    return@launch
                }

                val localSocksPort = VpnStateBridge.localSocksPort.value.coerceIn(1025, 65534)
                val proto = primaryProfile.protocol.uppercase()

                val engineStarted = when {
                    proto == "SLOWDNS" -> {
                        val slowDnsCluster = selectedCluster.filter { it.protocol.equals("SLOWDNS", ignoreCase = true) }
                            .ifEmpty { listOf(primaryProfile) }
                        val nodes = SlowDnsEngine.startSlowDnsCluster(applicationContext, slowDnsCluster)
                        if (nodes.isEmpty()) {
                            false
                        } else {
                            SshEngine.startSlowDnsSshCluster(nodes, localSocksPort)
                        }
                    }

                    proto.contains("SSH") -> {
                        val sshCluster = selectedCluster.filter { it.protocol.uppercase().contains("SSH") }
                            .ifEmpty { listOf(primaryProfile) }
                        SshEngine.startSshCluster(sshCluster, localSocksPort)
                    }

                    proto == "HYSTERIA2" -> {
                        VpnStateBridge.addLog("Connecting...")
                        Hysteria2Engine.startHysteria2(
                            context = applicationContext,
                            profile = primaryProfile,
                            localSocksPort = localSocksPort
                        )
                    }

                    proto == "WIREGUARD" -> {
                        if (primaryProfile.wgPrivateKey.isBlank() || primaryProfile.wgPublicKey.isBlank()) {
                            VpnStateBridge.addLog("Connection failed.")
                            false
                        } else {
                            VpnStateBridge.addLog("Connecting...")
                            val wgJson = buildWireGuardXrayJson(primaryProfile, localSocksPort)
                            XrayCore.RunXrayFromJSON(wgJson)
                        }
                    }

                    else -> {
                        if (primaryProfile.uuid.isBlank()) {
                            VpnStateBridge.addLog("Connection failed.")
                            false
                        } else {
                            VpnStateBridge.addLog("Connecting...")
                            val configJson = primaryProfile.rawJson.ifBlank { buildXrayJson(primaryProfile, localSocksPort) }
                            val ok = XrayCore.RunXrayFromJSON(configJson)
                            if (ok) {
                                VpnStateBridge.addLog("HTTP/1.1 101 Switching Protocols")
                            }
                            ok
                        }
                    }
                }

                if (!engineStarted) {
                    VpnStateBridge.updateState("DISCONNECTED", "Disconnected
