package com.albert.smartvpn.engine

import android.content.Context
import android.util.Log
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

object Hysteria2Engine {
    private const val TAG = "Hysteria2Engine"
    private var hy2Process: Process? = null
    @Volatile private var isRunning = false
    @Volatile private var hy2Connected = false

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
            VpnStateBridge.addLog("[SmartVPN] Hysteria2 Error: Missing Server or Auth Password")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val hy2Bin = File(nativeDir, "libhysteria2.so")
        if (!hy2Bin.exists() || hy2Bin.length() < 1024L) {
            VpnStateBridge.addLog("[SmartVPN] Hysteria2 binary missing")
            return false
        }
        hy2Bin.setExecutable(true)

        return try {
            hy2Connected = false
            val resolvedIp = try {
                InetAddress.getByName(serverHost).hostAddress ?: serverHost
            } catch (_: Throwable) {
                serverHost
            }

            // Support Port Hopping (e.g. 20000-50000) if provided
            val serverEndpoint = if (profile.hy2PortHopping.isNotBlank()) {
                "$resolvedIp:$serverPort,${profile.hy2PortHopping.trim()}"
            } else {
                "$resolvedIp:$serverPort"
            }

            val obfsBlock = if (profile.hy2ObfsPassword.isNotBlank()) {
                ""","obfs":{"type":"salamander","salamander":{"password":"${profile.hy2ObfsPassword.trim()}"}}"""
            } else ""

            val pinBlock = if (profile.pinSha256.isNotBlank()) {
                ""","pinSHA256":"${profile.pinSha256.trim()}""""
            } else ""

            val configJson = """
            {
              "server": "$serverEndpoint",
              "auth": "$authPass",
              "tls": {
                "sni": "$sniHost",
                "insecure": ${profile.allowInsecure}$pinBlock
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

            val configFile = File(context.cacheDir, "hy2-v2.json")
            configFile.writeText(configJson)

            val cmd = listOf(hy2Bin.absolutePath, "client", "-c", configFile.absolutePath, "--disable-update-check")
            hy2Process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            isRunning = true

            thread(start = true, name = "Hy2-Monitor") {
                try {
                    val proc = hy2Process ?: return@thread
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (isRunning) {
                        val line = reader.readLine() ?: break
                        Log.d(TAG, line)
                        if (line.contains("connected", true) || line.contains("SOCKS5", true)) {
                            hy2Connected = true
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Wait up to 6 seconds for local SOCKS5 port to bind
            var socksBound = false
            for (i in 1..30) {
                if (hy2Process?.isAlive == false) break
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", localSocksPort), 150)
                        socksBound = true
                    }
                    if (socksBound) break
                } catch (_: Throwable) {
                    Thread.sleep(200)
                }
            }

            if (hy2Process?.isAlive == true && (socksBound || hy2Connected)) {
                true
            } else {
                stopHysteria2()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hysteria2 Error", t)
            stopHysteria2()
            false
        }
    }

    fun stopHysteria2() {
        isRunning = false
        hy2Connected = false
        try {
            hy2Process?.destroy()
            hy2Process?.destroyForcibly()
        } catch (_: Throwable) {}
        hy2Process = null
    }
}
// === END OF FILE ===
