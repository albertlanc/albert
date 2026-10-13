package com.albert.smartvpn.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.albert.smartvpn.engine.Tun2Socks
import com.albert.smartvpn.engine.XrayCore
import kotlinx.coroutines.*
import java.io.File

class SmartVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    // Default internal routing
    private val tunAddress = "10.0.0.2"
    private val tunRoute = "0.0.0.0"
    private val socksPort = 10808 
    private val socksAddress = "127.0.0.1"

    companion object {
        const val ACTION_START = "com.albert.smartvpn.START"
        const val ACTION_STOP = "com.albert.smartvpn.STOP"
        private const val TAG = "SmartVpnService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // In production, the config string would be pulled from the Room DB
                val dummyConfig = "{ \"inbounds\": [{ \"port\": $socksPort, \"protocol\": \"socks\" }] }"
                startEngines(dummyConfig)
            }
            ACTION_STOP -> stopEngines()
        }
        return START_STICKY
    }

    private fun startEngines(xrayJsonConfig: String) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting Xray Core...")
                val xrayStarted = XrayCore.RunXrayFromJSON(xrayJsonConfig)
                if (!xrayStarted) throw Exception("Xray Core failed to start.")

                Log.d(TAG, "Establishing TUN interface...")
                val builder = Builder()
                    .setSession("Smart VPN")
                    .addAddress(tunAddress, 24)
                    .addDnsServer("1.1.1.1") // Custom DNS preset
                    .addDnsServer("8.8.8.8")
                    .addRoute(tunRoute, 0)
                    .setMtu(1500)
                    // .addDisallowedApplication("com.albert.smartvpn") // Prevent VPN loop

                vpnInterface = builder.establish()
                val fd = vpnInterface?.fd ?: throw Exception("Failed to get TUN file descriptor.")

                Log.d(TAG, "Starting hev-socks5-tunnel...")
                val tunnelStarted = Tun2Socks.startTunnel(fd, socksAddress, socksPort)
                if (!tunnelStarted) throw Exception("hev-socks5-tunnel failed to attach.")

                Log.d(TAG, "Smart VPN Engines running successfully.")

            } catch (e: Exception) {
                Log.e(TAG, "VPN Initialization Failed: ${e.message}")
                stopEngines()
            }
        }
    }

    private fun stopEngines() {
        Log.d(TAG, "Stopping all engines...")
        serviceScope.cancel()
        
        Tun2Socks.stopTunnel()
        XrayCore.StopXray()
        
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
        stopSelf()
    }
}
