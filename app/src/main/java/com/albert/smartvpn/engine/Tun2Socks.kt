package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import hev.htproxy.TProxyService
import java.io.File

object Tun2Socks {
    private const val TAG = "Tun2Socks"

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
                  address: '$socksAddress'
                  udp: 'udp'
                """.trimIndent()
            )
            val started = TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            VpnStateBridge.addLog("Tun2Socks active (FD=$tunFd -> $socksAddress:$socksPort)")
            started
        } catch (t: Throwable) {
            Log.e(TAG, "Tun2Socks error: ${t.message}", t)
            VpnStateBridge.addLog("Tun2Socks error: ${t.message}")
            false
        }
    }

    fun stopTunnel(): Boolean {
        return try {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Tun2Socks stop error: ${t.message}")
            false
        }
    }
}
