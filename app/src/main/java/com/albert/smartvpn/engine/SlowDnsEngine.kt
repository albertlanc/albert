package com.albert.smartvpn.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object SlowDnsEngine {
    private const val TAG = "SlowDnsEngine"
    private const val BOOST_UDP_CHANNELS = 15
    private var dnsttProcess: Process? = null
    private var localUdpProxy: DatagramSocket? = null
    private val upstreamUdpPool = CopyOnWriteArrayList<DatagramSocket>()
    private val poolExecutor = Executors.newCachedThreadPool()
    private val channelSelector = AtomicInteger(0)
    @Volatile private var isRunning = false
    @Volatile private var lastClientAddr: SocketAddress? = null

    private fun normalizePubKeyToHex(rawKey: String): String {
        val trimmed = rawKey.replace(Regex("\\s+"), "")
        if (trimmed.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            return trimmed.lowercase()
        }
        return try {
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            if (decoded.size == 32) {
                decoded.joinToString("") { "%02x".format(it) }
            } else {
                trimmed
            }
        } catch (_: Throwable) {
            trimmed
        }
    }

    /**
     * Starts an internal 15-socket High-Priority UDP DNS Accelerator for SlowDNS Boost.
     * Fans out DNS tunnel packets across 15 parallel low-delay UDP sockets with 1 MB buffers
     * and dual-packet pacing so downstream DNSTT polling never stalls.
     */
    private fun startUdpDnsBooster(remoteDnsIp: String, remoteDnsPort: Int): Int {
        val localProxy = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        localProxy.receiveBufferSize = 1048576
        localProxy.sendBufferSize = 1048576
        localUdpProxy = localProxy
        val proxyPort = localProxy.localPort
        val remoteInet = InetAddress.getByName(remoteDnsIp)

        // Open 15 parallel high-priority UDP sockets to the DNS server
        for (i in 0 until BOOST_UDP_CHANNELS) {
            val upSock = DatagramSocket()
            try {
                upSock.trafficClass = 0x10 // IPTOS_LOWDELAY
                upSock.receiveBufferSize = 524288
                upSock.sendBufferSize = 524288
            } catch (_: Throwable) {}
            upstreamUdpPool.add(upSock)

            // Dedicated receiver per upstream socket -> forwards DNS responses immediately back to dnstt-client
            poolExecutor.execute {
                val buf = ByteArray(4096)
                while (isRunning && !upSock.isClosed) {
                    try {
                        val respPkt = DatagramPacket(buf, buf.size)
                        upSock.receive(respPkt)
                        val clientTarget = lastClientAddr ?: continue
                        val forwardPkt = DatagramPacket(
                            respPkt.data,
                            respPkt.offset,
                            respPkt.length,
                            clientTarget
                        )
                        localProxy.send(forwardPkt)
                    } catch (_: Throwable) {
                        if (!isRunning) break
                    }
                }
            }
        }

        // Upstream dispatcher: stripes dnstt-client queries across all 15 UDP channels
        thread(start = true, name = "SlowDNS-UDP-Booster") {
            val buf = ByteArray(4096)
            while (isRunning && !localProxy.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    localProxy.receive(pkt)
                    lastClientAddr = pkt.socketAddress

                    val payload = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    if (upstreamUdpPool.isEmpty()) continue

                    val idx1 = (channelSelector.getAndIncrement() and Int.MAX_VALUE) % upstreamUdpPool.size
                    val primarySock = upstreamUdpPool[idx1]
                    val outPkt = DatagramPacket(payload, payload.size, remoteInet, remoteDnsPort)
                    primarySock.send(outPkt)

                    // Send a paced secondary channel copy on parallel socket to eliminate UDP packet loss drops
                    val idx2 = (idx1 + 7) % upstreamUdpPool.size
                    val secondarySock = upstreamUdpPool[idx2]
                    poolExecutor.execute {
                        try {
                            Thread.sleep(12)
                            if (isRunning && !secondarySock.isClosed) {
                                secondarySock.send(DatagramPacket(payload, payload.size, remoteInet, remoteDnsPort))
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    if (!isRunning) break
                }
            }
        }

        return proxyPort
    }

    fun startSlowDns(
        context: Context,
        dnsServerIp: String,
        dnsPort: Int = 53,
        nsDomain: String,
        pubKey: String,
        localPort: Int = 7000
    ): Boolean {
        stopSlowDns()

        val rawDnsHost = dnsServerIp.trim().ifEmpty { "8.8.8.8" }.substringBefore(":")
        val cleanNs = nsDomain.trim()
        val cleanPubKey = normalizePubKeyToHex(pubKey)

        if (cleanNs.isEmpty() || cleanPubKey.length != 64) {
            VpnStateBridge.addLog("Disconnected: Invalid SlowDNS configuration.")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val dnsttBin = File(nativeDir, "libdnstt.so")
        if (!dnsttBin.exists()) {
            VpnStateBridge.addLog("Disconnected: SlowDNS engine unavailable.")
            return false
        }
        dnsttBin.setExecutable(true)

        val resolvedDnsIp = if (rawDnsHost.startsWith("https://", ignoreCase = true) ||
            rawDnsHost.startsWith("tls://", ignoreCase = true)
        ) {
            dnsServerIp.trim()
        } else {
            try {
                InetAddress.getByName(rawDnsHost).hostAddress ?: rawDnsHost
            } catch (_: Throwable) {
                rawDnsHost
            }
        }

        val listenAddr = "127.0.0.1:$localPort"

        return try {
            isRunning = true
            VpnStateBridge.updateState("CONNECTING", "Connecting...")
            VpnStateBridge.addLog("Connecting...")

            val useBoost = VpnStateBridge.slowDnsBoostEnabled.value &&
                !resolvedDnsIp.startsWith("https://", ignoreCase = true) &&
                !resolvedDnsIp.startsWith("tls://", ignoreCase = true)

            val effectiveUdpTarget = if (useBoost) {
                val boostPort = startUdpDnsBooster(resolvedDnsIp, dnsPort)
                Log.d(TAG, "SlowDNS Boost UDP Accelerator bound on 127.0.0.1:$boostPort -> $resolvedDnsIp:$dnsPort")
                "127.0.0.1:$boostPort"
            } else {
                "$resolvedDnsIp:$dnsPort"
            }

            val cmd = when {
                resolvedDnsIp.startsWith("https://", ignoreCase = true) ->
                    listOf(dnsttBin.absolutePath, "-doh", resolvedDnsIp, "-pubkey", cleanPubKey, cleanNs, listenAddr)
                resolvedDnsIp.startsWith("tls://", ignoreCase = true) ->
                    listOf(dnsttBin.absolutePath, "-dot", resolvedDnsIp.removePrefix("tls://"), "-pubkey", cleanPubKey, cleanNs, listenAddr)
                else ->
                    listOf(dnsttBin.absolutePath, "-udp", effectiveUdpTarget, "-pubkey", cleanPubKey, cleanNs, listenAddr)
            }

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            dnsttProcess = pb.start()

            thread(start = true, name = "SlowDNS-Logger") {
                try {
                    val proc = dnsttProcess ?: return@thread
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (isRunning) {
                        val line = reader.readLine() ?: break
                        val text = line.trim()
                        if (text.isNotEmpty()) {
                            // Keep raw DNSTT logs in background logcat only
                            Log.d(TAG, "[DNSTT] $text")
                            if (text.contains("begin session", ignoreCase = true)) {
                                VpnStateBridge.addLog("Switching Protocols...")
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            Thread.sleep(1100)

            if (dnsttProcess?.isAlive == true) {
                true
            } else {
                VpnStateBridge.addLog("Disconnected: SlowDNS handshake failed.")
                stopSlowDns()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SlowDNS Error", t)
            VpnStateBridge.addLog("Disconnected: Connection failed.")
            stopSlowDns()
            false
        }
    }

    fun stopSlowDns() {
        isRunning = false
        lastClientAddr = null
        try { localUdpProxy?.close() } catch (_: Throwable) {}
        localUdpProxy = null

        upstreamUdpPool.forEach { sock ->
            try { sock.close() } catch (_: Throwable) {}
        }
        upstreamUdpPool.clear()

        try {
            dnsttProcess?.destroy()
            dnsttProcess?.destroyForcibly()
        } catch (_: Throwable) {}
        dnsttProcess = null
    }
}
