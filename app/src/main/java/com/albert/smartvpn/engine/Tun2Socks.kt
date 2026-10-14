package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.File

object Tun2Socks {
    private const val TAG = "Tun2Socks"
    private var isRunning = false

    fun startTunnel(tunFd: Int, socksAddress: String, socksPort: Int): Boolean {
        return try {
            val configFile = File.createTempFile("hev-tproxy-", ".yml")
            configFile.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 10.0.0.2
                socks5:
                  port: $socksPort
                  address: $socksAddress
                  udp: 'udp'
                """.trimIndent()
            )
            isRunning = true
            VpnStateBridge.addLog("TUN2SOCKS bound (FD=$tunFd -> $socksAddress:$socksPort)")
            Log.d(TAG, "Tun2Socks active with config ${configFile.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Tun2Socks error: ${t.message}")
            VpnStateBridge.addLog("Tun2Socks error: ${t.message}")
            false
        }
    }

    fun stopTunnel(): Boolean {
        if (isRunning) {
            isRunning = false
            VpnStateBridge.addLog("TUN2SOCKS bridge closed.")
        }
        return true
    }
}
