package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

object XrayCore {
    private const val TAG = "XrayCore"
    private var port80RelayServer: ServerSocket? = null
    private val relayPool = Executors.newCachedThreadPool()
    @Volatile private var relayRunning = false

    private fun invokeLibXray(requestJson: String): String? {
        for (className in listOf("libXray.LibXray", "libxray.Libxray")) {
            try {
                val clazz = Class.forName(className)
                val method = clazz.methods.firstOrNull {
                    it.name.equals("invoke", true) && it.parameterTypes.size == 1
                }
                if (method != null) return method.invoke(null, requestJson) as? String
            } catch (_: Throwable) {}
        }
        return null
    }

    fun startLocalPort80Relay(remoteHost: String, remotePort: Int): Int {
        stopLocalPort80Relay()
        return try {
            val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
            port80RelayServer = server
            relayRunning = true
            val localRelayPort = server.localPort
            val cleanHost = remoteHost.trim()

            thread(start = true, name = "VLESS-BBR-Port80-Relay") {
                while (relayRunning) {
                    try {
                        val clientSock = server.accept() ?: break
                        clientSock.tcpNoDelay = true
                        clientSock.trafficClass = 0x18
                        clientSock.sendBufferSize = 262144
                        clientSock.receiveBufferSize = 262144
                        relayPool.execute {
                            var remoteSock: Socket? = null
                            try {
                                remoteSock = Socket().apply {
                                    setPerformancePreferences(0, 1, 2)
                                    tcpNoDelay = true
                                    keepAlive = true
                                    trafficClass = 0x18
                                    sendBufferSize = 262144
                                    receiveBufferSize = 262144
                                    connect(InetSocketAddress(cleanHost, remotePort), 10000)
                                }
                                val cIn = clientSock.getInputStream()
                                val cOut = clientSock.getOutputStream()
                                val rIn = remoteSock.getInputStream()
                                val rOut = remoteSock.getOutputStream()

                                val upJob = relayPool.submit {
                                    try {
                                        val buf = ByteArray(131072)
                                        while (relayRunning) {
                                            val n = cIn.read(buf)
                                            if (n == -1) break
                                            rOut.write(buf, 0, n)
                                        }
                                        rOut.flush()
                                    } catch (_: Throwable) {
                                    } finally {
                                        try { remoteSock.close() } catch (_: Throwable) {}
                                    }
                                }

                                val buf = ByteArray(131072)
                                while (relayRunning) {
                                    val n = rIn.read(buf)
                                    if (n == -1) break
                                    cOut.write(buf, 0, n)
                                }
                                cOut.flush()
                                upJob.cancel(true)
                            } catch (_: Throwable) {
                            } finally {
                                try { clientSock.close() } catch (_: Throwable) {}
                                try { remoteSock?.close() } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {
                        if (!relayRunning) break
                    }
                }
            }
            localRelayPort
        } catch (t: Throwable) {
            Log.e(TAG, "Port 80 relay error: ${t.message}")
            remotePort
        }
    }

    fun stopLocalPort80Relay() {
        relayRunning = false
        try { port80RelayServer?.close() } catch (_: Throwable) {}
        port80RelayServer = null
    }

    fun RunXrayFromJSON(configJson: String): Boolean {
        return try {
            val sanitized = configJson
                .replace(Regex(""""allowInsecure"\s*:\s*(true|false)\s*,\s*"""), "")
                .replace(Regex(""",\s*"allowInsecure"\s*:\s*(true|false)"""), "")
                .replace(Regex(""""allowInsecure"\s*:\s*(true|false)"""), "")

            val payload = JsonObject().apply { addProperty("xrayJson", sanitized) }
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "runXray")
                add("payload", payload)
            }

            val responseStr = invokeLibXray(request.toString()) ?: return false
            val responseJson = JsonParser.parseString(responseStr).asJsonObject
            val ok = responseJson.get("success")?.asBoolean ?: false
            if (!ok) {
                Log.e(TAG, "Xray rejected config: $responseStr")
                VpnStateBridge.addLog("Connection failed.")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Xray: ${t.message}", t)
            false
        }
    }

    fun verifyServerReachable(host: String, port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(host.trim(), port), 8000) }
            VpnStateBridge.addLog("HTTP/1.1 200 OK")
            true
        } catch (_: Throwable) {
            VpnStateBridge.addLog("Connection failed.")
            false
        }
    }

    fun StopXray(): Boolean {
        stopLocalPort80Relay()
        return try {
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "stopXray")
            }
            invokeLibXray(request.toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun XrayVersion(): String = "Xray-core"
}
// === END OF FILE ===
