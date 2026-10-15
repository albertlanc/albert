package com.albert.smartvpn.engine

import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

object HotshareServer {
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false
    const val HOTSHARE_PORT = 8080

    fun detectHotspotIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize hotspot / tethering interfaces (ap0, wlan1, swlan0, rndis0, wlan0)
            val priorityNames = listOf("ap", "wlan1", "swlan", "softap", "rndis", "tether", "wlan0")
            for (pref in priorityNames) {
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    if (nif.name.lowercase().contains(pref)) {
                        for (addr in Collections.list(nif.inetAddresses)) {
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val ip = addr.hostAddress ?: continue
                                if (ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")) {
                                    return ip
                                }
                            }
                        }
                    }
                }
            }
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback || nif.name.startsWith("tun")) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.")) return ip
                    }
                }
            }
        } catch (_: Throwable) {}
        return "192.168.43.1"
    }

    fun startHotshare(localVpnSocksPort: Int = 10808): Boolean {
        stopHotshare()
        return try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress("0.0.0.0", HOTSHARE_PORT), 128)
            isRunning = true

            val hotspotIp = detectHotspotIpAddress()
            VpnStateBridge.hotshareEnabled.value = true
            VpnStateBridge.hotshareIp.value = hotspotIp
            VpnStateBridge.addLog("Wi-Fi Hotshare ACTIVE on $hotspotIp:$HOTSHARE_PORT (HTTP & SOCKS5)")

            thread(start = true, name = "WiFi-Hotshare-Server") {
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        client.tcpNoDelay = true
                        threadPool.execute {
                            handleTetheredClient(client, localVpnSocksPort)
                        }
                    } catch (_: Throwable) {
                        if (!isRunning) break
                    }
                }
            }
            true
        } catch (t: Throwable) {
            VpnStateBridge.addLog("Hotshare Error: ${t.message}")
            stopHotshare()
            false
        }
    }

    private fun handleTetheredClient(client: Socket, localSocksPort: Int) {
        var socksBridge: Socket? = null
        try {
            val ins = client.getInputStream()
            val outs = client.getOutputStream()

            val firstByte = ins.read()
            if (firstByte == -1) {
                client.close()
                return
            }

            // Case 1: Connected Hotspot device is using SOCKS5
            if (firstByte == 0x05) {
                socksBridge = Socket()
                socksBridge.tcpNoDelay = true
                socksBridge.connect(InetSocketAddress("127.0.0.1", localSocksPort), 5000)
                val sOut = socksBridge.getOutputStream()
                val sIn = socksBridge.getInputStream()
                sOut.write(firstByte)
                sOut.flush()
                relayStreams(ins, outs, sIn, sOut)
                return
            }

            // Case 2: Connected Hotspot device is using Android/iOS/Windows Wi-Fi HTTP/HTTPS Proxy
            val headerSb = StringBuilder()
            headerSb.append(firstByte.toChar())
            val b = ByteArray(1)
            while (headerSb.length < 8192 && ins.read(b) != -1) {
                headerSb.append(b[0].toInt().toChar())
                if (headerSb.endsWith("\r\n\r\n")) break
            }

            val requestHeader = headerSb.toString()
            val firstLine = requestHeader.lineSequence().firstOrNull()?.trim() ?: ""
            if (firstLine.isEmpty()) {
                client.close()
                return
            }

            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]

            val targetHost: String
            val targetPort: Int

            if (method == "CONNECT") {
                targetHost = rawTarget.substringBefore(":")
                targetPort = rawTarget.substringAfter(":", "443").toIntOrNull() ?: 443
            } else {
                val withoutProto = rawTarget.removePrefix("http://").removePrefix("https://")
                val hostPort = withoutProto.substringBefore("/")
                targetHost = hostPort.substringBefore(":")
                targetPort = hostPort.substringAfter(":", "80").toIntOrNull() ?: 80
            }

            // Connect to the VPN's local SOCKS5 engine on 127.0.0.1:localSocksPort
            socksBridge = Socket()
            socksBridge.tcpNoDelay = true
            socksBridge.connect(InetSocketAddress("127.0.0.1", localSocksPort), 8000)
            val sIn = socksBridge.getInputStream()
            val sOut = socksBridge.getOutputStream()

            // Perform SOCKS5 handshake with local VPN engine
            sOut.write(byteArrayOf(0x05, 0x01, 0x00))
            sOut.flush()
            if (sIn.read() != 0x05 || sIn.read() != 0x00) {
                client.close()
                socksBridge.close()
                return
            }

            val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
            val req = ByteArray(4 + 1 + domainBytes.size + 2)
            req[0] = 0x05
            req[1] = 0x01 // CONNECT
            req[2] = 0x00
            req[3] = 0x03 // Domain name
            req[4] = domainBytes.size.toByte()
            System.arraycopy(domainBytes, 0, req, 5, domainBytes.size)
            req[5 + domainBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
            req[6 + domainBytes.size] = (targetPort and 0xFF).toByte()
            sOut.write(req)
            sOut.flush()

            val replyHeader = ByteArray(4)
            if (sIn.read(replyHeader) < 4 || replyHeader[1].toInt() != 0x00) {
                client.close()
                socksBridge.close()
                return
            }
            when (replyHeader[3].toInt()) {
                1 -> sIn.skip(6)
                3 -> {
                    val l = sIn.read()
                    sIn.skip((l + 2).toLong())
                }
                4 -> sIn.skip(18)
            }

            if (method == "CONNECT") {
                outs.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8))
                outs.flush()
            } else {
                // Forward standard HTTP request
                sOut.write(requestHeader.toByteArray(Charsets.UTF_8))
                sOut.flush()
            }

            relayStreams(ins, outs, sIn, sOut)
        } catch (_: Throwable) {
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { socksBridge?.close() } catch (_: Throwable) {}
        }
    }

    private fun relayStreams(cIn: InputStream, cOut: OutputStream, sIn: InputStream, sOut: OutputStream) {
        val job = threadPool.submit {
            try {
                val buf = ByteArray(65536)
                while (isRunning) {
                    val n = cIn.read(buf)
                    if (n == -1) break
                    sOut.write(buf, 0, n)
                    sOut.flush()
                }
            } catch (_: Throwable) {}
        }
        try {
            val buf = ByteArray(65536)
            while (isRunning) {
                val n = sIn.read(buf)
                if (n == -1) break
                cOut.write(buf, 0, n)
                cOut.flush()
            }
        } catch (_: Throwable) {}
        job.cancel(true)
    }

    fun stopHotshare() {
        isRunning = false
        VpnStateBridge.hotshareEnabled.value = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }
}
