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
        val candidateClasses = listOf("libXray.LibXray", "libxray.Libxray")
        for (className in candidateClasses) {
            try {
                val clazz = Class.forName(className)
                val method = clazz.methods.firstOrNull {
                    it.name.equals("invoke", ignoreCase = true) && it.parameterTypes.size == 1
                }
                if (method != null) {
                    return method.invoke(null, requestJson) as? String
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    fun startLocalPort80Relay(remoteHost: String, remotePort: Int): Int {
        stopLocalPort80Relay()
        return try {
            val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            port80RelayServer = server
            relayRunning = true
            val localRelayPort = server.localPort
            val cleanHost = remoteHost.trim()

            Log.d(TAG, "VLESS Port-80 Private Loopback Bridge active (127.0.0.1:$localRelayPort -> $cleanHost:$remotePort)")

            thread(start = true, name = "VLESS-Port80-Relay") {
                while (relayRunning) {
                    try {
                        val clientSock = server.accept() ?: break
                        clientSock.tcpNoDelay = true
                        relayPool.execute {
                            var remoteSock: Socket? = null
                            try {
                                remoteSock = Socket()
                                remoteSock.tcpNoDelay = true
                                remoteSock.sendBufferSize = 262144
                                remoteSock.receiveBufferSize = 262144
                                remoteSock.connect(InetSocketAddress(cleanHost, remotePort), 10000)

                                val cIn = clientSock.getInputStream()
                                val cOut = clientSock.getOutputStream()
                                val rIn = remoteSock.getInputStream()
                                val rOut = remoteSock.getOutputStream()

                                val upJob = relayPool.submit {
                                    try {
                                        val buf = ByteArray(65536)
                                        while (relayRunning) {
                                            val n = cIn.read(buf)
                                            if (n == -1) break
                                            rOut.write(buf, 0, n)
                                            rOut.flush()
                                        }
                                    } catch (_: Throwable) {
                                    } finally {
                                        try { remoteSock.close() } catch (_: Throwable) {}
                                    }
                                }

                                val buf = ByteArray(65536)
                                while (relayRunning) {
                                    val n = rIn.read(buf)
                                    if (n == -1) break
                                    cOut.write(buf, 0, n)
                                    cOut.flush()
                                }
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
            VpnStateBridge.addLog("Switching Protocols...")

            val sanitizedConfig = configJson
                .replace(Regex(""""allowInsecure"\s*:\s*(true|false)\s*,\s*"""), "")
                .replace(Regex(""",\s*"allowInsecure"\s*:\s*(true|false)"""), "")
                .replace(Regex(""""allowInsecure"\s*:\s*(true|false)"""), "")

            val payload = JsonObject().apply {
                addProperty("xrayJson", sanitizedConfig)
            }
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "runXray")
                add("payload", payload)
            }

            val responseStr = invokeLibXray(request.toString())
            if (responseStr != null) {
                val responseJson = JsonParser.parseString(responseStr).asJsonObject
                val ok = responseJson.get("success")?.asBoolean ?: false
                if (ok) {
                    VpnStateBridge.addLog("HTTP/1.1 200 OK")
                    return true
                } else {
                    Log.e(TAG, "Xray rejected config: $responseStr")
                    VpnStateBridge.addLog("Disconnected: Handshake rejected.")
                    return false
                }
            } else {
                VpnStateBridge.addLog("Disconnected: Engine unavailable.")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Xray: ${t.message}", t)
            VpnStateBridge.addLog("Disconnected: Connection failed.")
            false
        }
    }

    fun verifyServerReachable(host: String, port: Int): Boolean {
        return try {
            val cleanHost = host.trim()
            VpnStateBridge.addLog("Connecting...")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(cleanHost, port), 10000)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Endpoint unreachable ($host:$port): ${t.message}")
            VpnStateBridge.addLog("Disconnected: Server unreachable.")
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
