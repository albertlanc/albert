package com.albert.smartvpn.engine

import android.util.Log
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
                misc:
                  task-stack-size: 81920
                  tcp-buffer-size: 65536
                  connect-timeout: 5000
                  read-write-timeout: 60000
                """.trimIndent()
            )
            TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
        } catch (t: Throwable) {
            Log.e(TAG, "Tun2Socks error: ${t.message}", t)
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
// === END OF FILE ===
