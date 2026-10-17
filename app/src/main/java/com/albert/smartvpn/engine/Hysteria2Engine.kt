package com.albert.smartvpn.engine

import android.content.Context
import android.util.Log
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object Hysteria2Engine {
    private const val TAG = "Hysteria2Engine"
    private var hy2Process: Process? = null
    private var activeConfigFile: File? = null

    fun startHysteria2(
        context: Context,
        profile: VpnProfile,
        localSocksPort: Int
    ): Boolean {
        stopHysteria2()

        val rawHost = profile.server.trim()
        val authPass = profile.hy2Password.ifBlank { profile.uuid.ifBlank { profile.sshPass } }.trim()
        if (rawHost.isEmpty() || authPass.isEmpty()) {
            VpnStateBridge.addLog("Connection failed: Missing Hysteria2 server or password.")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val hy2Bin = File(nativeDir, "libhysteria2.so")
        if (!hy2Bin.exists()) {
            VpnStateBridge.addLog("Connection failed: Hysteria2 engine binary not found.")
            return false
        }
        hy2Bin.setExecutable(true)

        val resolvedIp = try {
            InetAddress.getByName(rawHost).hostAddress ?: rawHost
        } catch (_: Throwable) {
            rawHost
        }
        val sniHost = profile.sni.trim().ifBlank { rawHost }
        val serverEndpoint = "$resolvedIp:${profile.port}"

        return try {
            val obfsBlock = if (profile.hy2Obfs.isNotBlank() && profile.hy2ObfsPassword.isNotBlank()) {
                """
                "obfs": {
                  "type": "${profile.hy2Obfs.trim()}",
                  "salamander": {
                    "password": "${profile.hy2ObfsPassword.trim()}"
                  }
                },
                """.trimIndent()
            } else {
                ""
            }

            val upMbps = profile.hy2UpMbps.coerceIn(10, 100)
            val downMbps = profile.hy2DownMbps.coerceIn(20, 200)

            val configJson = """
                {
                  "server": "$serverEndpoint",
                  "auth": "$authPass",
                  $obfsBlock
                  "tls": {
                    "sni": "$sniHost",
                    "insecure": true
                  },
                  "bandwidth": {
                    "up": "$upMbps mbps",
                    "down": "$downMbps mbps"
                  },
                  "quic": {
                    "initStreamReceiveWindow": 8388608,
                    "maxStreamReceiveWindow": 8388608,
                    "initConnReceiveWindow": 20971520,
                    "maxConnReceiveWindow": 20971520
                  },
                  "socks5": {
                    "listen": "127.0.0.1:$localSocksPort"
                  }
                }
            """.trimIndent()

            val cfgFile = File(context.cacheDir, "hy2_live.json")
            cfgFile.writeText(configJson)
            activeConfigFile = cfgFile

            val cmd = listOf(hy2Bin.absolutePath, "client", "-c", cfgFile.absolutePath)
            hy2Process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            var socksReady = false
            for (attempt in 1..14) {
                Thread.sleep(250)
                if (hy2Process?.isAlive != true) break
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", localSocksPort), 200)
                        socksReady = true
                    }
                    if (socksReady) break
                } catch (_: Throwable) {}
            }

            if (hy2Process?.isAlive == true && socksReady) {
                VpnStateBridge.addLog("HTTP/1.1 200 OK")
                VpnStateBridge.addLog("Switching Protocols...")
                true
            } else {
                VpnStateBridge.addLog("Connection failed.")
                stopHysteria2()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hysteria2 Error: ${t.message}", t)
            VpnStateBridge.addLog("Connection failed.")
            stopHysteria2()
            false
        }
    }

    fun stopHysteria2() {
        try {
            hy2Process?.destroy()
            hy2Process?.destroyForcibly()
        } catch (_: Throwable) {}
        hy2Process = null
        try { activeConfigFile?.delete() } catch (_: Throwable) {}
        activeConfigFile = null
    }
}
// === END OF FILE 5 ===
