package com.albert.smartvpn.engine

import android.content.Context
import android.util.Log
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

object Hysteria2Engine {
    private const val TAG = "Hysteria2Engine"
    private var hy2Process: Process? = null
    @Volatile private var isRunning = false

    fun startHysteria2(
        context: Context,
        profile: VpnProfile,
        localSocksPort: Int
    ): Boolean {
        stopHysteria2()

        val serverHost = profile.server.trim()
        val serverPort = profile.port
        val authPass = profile.hy2Password.ifBlank { profile.uuid }.trim()
        val sniHost = profile.sni.trim().ifBlank { serverHost }

        if (serverHost.isEmpty() || authPass.isEmpty()) {
            VpnStateBridge.addLog("Hysteria2 Error: Server or Auth Password is empty!")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val hy2Bin = File(nativeDir, "libhysteria2.so")
        if (!hy2Bin.exists()) {
            VpnStateBridge.addLog("Hysteria2 Error: libhysteria2.so binary not found.")
            return false
        }
        hy2Bin.setExecutable(true)

        return try {
            VpnStateBridge.updateState("CONNECTING", "Starting Hysteria2 QUIC...")
            VpnStateBridge.addLog("Starting Hysteria2 -> $serverHost:$serverPort (SNI: $sniHost)")

            val obfsBlock = if (profile.hy2Obfs.equals("salamander", ignoreCase = true) &&
                profile.hy2ObfsPassword.isNotBlank()
            ) {
                """
                ,
                "obfs": {
                  "type": "salamander",
                  "salamander": {
                    "password": "${profile.hy2ObfsPassword}"
                  }
                }
                """.trimIndent()
            } else {
                ""
            }

            val configJson = """
            {
              "server": "$serverHost:$serverPort",
              "auth": "$authPass",
              "tls": {
                "sni": "$sniHost",
                "insecure": true
              },
              "bandwidth": {
                "up": "${profile.hy2UpMbps.coerceAtLeast(10)} mbps",
                "down": "${profile.hy2DownMbps.coerceAtLeast(20)} mbps"
              },
              "socks5": {
                "listen": "127.0.0.1:$localSocksPort"
              }$obfsBlock
            }
            """.trimIndent()

            val configFile = File(context.cacheDir, "hysteria2-client.json")
            configFile.writeText(configJson)

            val cmd = listOf(hy2Bin.absolutePath, "client", "-c", configFile.absolutePath)
            hy2Process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            isRunning = true

            thread(start = true, name = "Hy2-Logger") {
                try {
                    val proc = hy2Process ?: return@thread
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (isRunning) {
                        val line = reader.readLine() ?: break
                        val text = line.trim()
                        if (text.isNotEmpty()) {
                            Log.d(TAG, text)
                            VpnStateBridge.addLog("[HY2] $text")
                        }
                    }
                } catch (_: Throwable) {}
            }

            var listening = false
            for (i in 1..25) {
                if (hy2Process?.isAlive == false) break
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", localSocksPort), 200)
                        listening = true
                    }
                    if (listening) break
                } catch (_: Throwable) {
                    Thread.sleep(200)
                }
            }

            if (hy2Process?.isAlive == true && listening) {
                VpnStateBridge.addLog("Hysteria2 QUIC tunnel active on 127.0.0.1:$localSocksPort")
                true
            } else {
                VpnStateBridge.addLog("Hysteria2 failed to establish QUIC session.")
                stopHysteria2()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hysteria2 Error", t)
            VpnStateBridge.addLog("Hysteria2 Error: ${t.message}")
            stopHysteria2()
            false
        }
    }

    fun stopHysteria2() {
        isRunning = false
        try {
            hy2Process?.destroy()
            hy2Process?.destroyForcibly()
        } catch (_: Throwable) {}
        hy2Process = null
    }
}
