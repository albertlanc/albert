package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object Tun2Socks {
    private const val TAG = "Tun2Socks"
    private val isRunning = AtomicBoolean(false)
    private var nativeLoaded = false

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

    fun startTunnel(fd: Int, socksHost: String, socksPort: Int): Boolean {
        if (fd < 0) return false
        if (isRunning.get()) stopTunnel()

        return try {
            val configContent = """
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
            """.trimIndent()

            val configFile = File.createTempFile("smartvpn_tproxy_", ".yml")
            configFile.writeText(configContent)

            isRunning.set(true)
            Thread({
                try {
                    if (nativeLoaded) {
                        TProxyStartService(configFile.absolutePath, fd)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "TProxy runtime error: ${t.message}")
                } finally {
                    isRunning.set(false)
                }
            }, "SmartVPN-Tun2Socks").apply {
                isDaemon = true
                start()
            }

            VpnStateBridge.addLog("TUN interface bridged to SOCKS5 ($socksHost:$socksPort)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Tun2Socks: ${t.message}")
            isRunning.set(false)
            false
        }
    }

    fun stopTunnel() {
        if (!isRunning.getAndSet(false)) return
        try {
            if (nativeLoaded) {
                TProxyStopService()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping Tun2Socks: ${t.message}")
        }
    }
}
// === END OF Tun2Socks.kt ===
