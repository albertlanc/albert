package com.albert.smartvpn.engine

import android.util.Log
import java.io.File

object Tun2Socks {
    private const val TAG = "Tun2Socks"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            isLoaded = true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load hev-socks5-tunnel: ${e.message}")
        }
    }

    fun startTunnel(tunFd: Int, socksAddress: String, socksPort: Int): Boolean {
        if (!isLoaded) return false
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
            Log.d(TAG, "Tun2Socks config prepared at ${configFile.absolutePath} for FD $tunFd")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Tun2Socks start error: ${e.message}")
            false
        }
    }

    fun stopTunnel(): Boolean {
        return true
    }
}
