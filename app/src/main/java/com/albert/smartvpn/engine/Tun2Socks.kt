package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.File
import kotlin.concurrent.thread

object Tun2Socks {
    private const val TAG = "Tun2Socks"
    @Volatile private var isRunning = false
    @Volatile private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            nativeLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libhev-socks5-tunnel.so: ${t.message}")
        }
    }

    @JvmStatic
    private external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    private external fun TProxyStopService()

    @JvmStatic
    private external fun TProxyGetStats(): LongArray?

    fun startTunnel(fd: Int, socksHost: String = "127.0.0.1", socksPort: Int = 10808) {
        if (!nativeLoaded) {
            VpnStateBridge.addLog("ERROR: Native Tun2Socks library not loaded.")
            return
        }
        if (isRunning) {
            stopTunnel()
        }

        isRunning = true
        thread(start = true, name = "Tun2Socks-Worker") {
            try {
                val configFile = File.createTempFile("tproxy_", ".yml")
                val yaml = """
                    tunnel:
                      mtu: 1400
                      ipv4: 10.0.0.2
                    socks5:
                      port: $socksPort
                      address: $socksHost
                      udp: 'udp'
                    misc:
                      task-stack-size: 81920
                      connect-timeout: 10000
                      read-write-timeout: 60000
                      log-level: none
                """.trimIndent()
                configFile.writeText(yaml)
                TProxyStartService(configFile.absolutePath, fd)
            } catch (t: Throwable) {
                Log.e(TAG, "Tun2Socks error: ${t.message}")
            } finally {
                isRunning = false
            }
        }
    }

    fun stopTunnel() {
        if (!nativeLoaded || !isRunning) return
        try {
            TProxyStopService()
        } catch (t: Throwable) {
            Log.e(TAG, "Stop error: ${t.message}")
        } finally {
            isRunning = false
        }
    }
}
// === END OF FILE 2 ===
