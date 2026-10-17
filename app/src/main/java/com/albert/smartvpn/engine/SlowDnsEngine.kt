package com.albert.smartvpn.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object SlowDnsEngine {
    private const val TAG = "SlowDnsEngine"
    private const val BOOST_INSTANCES = 15
    private const val BASE_WORKER_PORT = 7001

    private val workerProcesses = CopyOnWriteArrayList<Process>()
    private var balancerServer: ServerSocket? = null
    private val balancerPool = Executors.newCachedThreadPool()
    private val roundRobinIndex = AtomicInteger(0)
    @Volatile private var isRunning = false

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

    fun startSlowDns(
        context: Context,
        dnsServerIp: String,
        dnsPort: Int = 53,
        nsDomain: String,
        pubKey: String,
        localPort: Int = 7000
    ): Boolean {
        stopSlowDns()

        val rawInput = dnsServerIp.trim().ifEmpty { "8.8.8.8" }
        val rawDnsHost = rawInput.substringBefore(":")
        val customInlinePort = rawInput.substringAfter(":", "").toIntOrNull()
        val effectivePort = customInlinePort ?: if (dnsPort in 1..65535) dnsPort else 53

        val cleanNs = nsDomain.trim()
        val cleanPubKey = normalizePubKeyToHex(pubKey)

        if (cleanNs.isEmpty() || cleanPubKey.length != 64) {
            VpnStateBridge.addLog("Connection failed: Invalid SlowDNS configuration.")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val dnsttBin = File(nativeDir, "libdnstt.so")
        if (!dnsttBin.exists()) {
            VpnStateBridge.addLog("Connection failed: SlowDNS engine unavailable.")
            return false
        }
        dnsttBin.setExecutable(true)

        val primaryDnsIp = try {
            InetAddress.getByName(rawDnsHost).hostAddress ?: rawDnsHost
        } catch (_: Throwable) {
            rawDnsHost
        }

        // Automatically turn ON SlowDNS Boost when a SlowDNS profile connects
        VpnStateBridge.slowDnsBoostEnabled.value = true

        return try {
            isRunning = true
            VpnStateBridge.updateState("CONNECTING", "Connecting...")
            VpnStateBridge.addLog("Connecting...")

            val activeWorkerPorts = CopyOnWriteArrayList<Int>()
            val resolverAddr = "$primaryDnsIp:$effectivePort"

            for (idx in 0 until BOOST_INSTANCES) {
                val workerPort = BASE_WORKER_PORT + idx
                val workerListen = "127.0.0.1:$workerPort"

                val cmd = when {
                    rawInput.startsWith("https://", true) ->
                        listOf(dnsttBin.absolutePath, "-doh", rawInput, "-pubkey", cleanPubKey, cleanNs, workerListen)
                    rawInput.startsWith("tls://", true) ->
                        listOf(dnsttBin.absolutePath, "-dot", rawInput.removePrefix("tls://"), "-pubkey", cleanPubKey, cleanNs, workerListen)
                    else ->
                        listOf(dnsttBin.absolutePath, "-udp", resolverAddr, "-pubkey", cleanPubKey, cleanNs, workerListen)
                }

                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                workerProcesses.add(proc)
                activeWorkerPorts.add(workerPort)

                balancerPool.execute {
                    try {
                        val ins = proc.inputStream
                        val buf = ByteArray(512)
                        while (isRunning && ins.read(buf) != -1) {}
                    } catch (_: Throwable) {}
                }
            }

            balancerServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", localPort), 128)
            }

            thread(start = true, name = "SlowDNS-BBR-Balancer") {
                while (isRunning) {
                    try {
                        val clientSock = balancerServer?.accept() ?: break
                        clientSock.tcpNoDelay = true
                        clientSock.trafficClass = 0x18
                        clientSock.sendBufferSize = 262144
                        clientSock.receiveBufferSize = 262144

                        balancerPool.execute {
                            bridgeToBoostWorker(clientSock, activeWorkerPorts)
                        }
                    } catch (_: Throwable) {
                        if (!isRunning) break
                    }
                }
            }

            Thread.sleep(900)

            val aliveCount = workerProcesses.count { it.isAlive }
            if (aliveCount > 0) {
                VpnStateBridge.addLog("HTTP/1.1 200 OK")
                VpnStateBridge.addLog("Switching Protocols...")
                true
            } else {
                VpnStateBridge.addLog("Connection failed.")
                stopSlowDns()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SlowDNS Error: ${t.message}", t)
            VpnStateBridge.addLog("Connection failed.")
            stopSlowDns()
            false
        }
    }

    private fun bridgeToBoostWorker(clientSock: Socket, workerPorts: List<Int>) {
        var workerSock: Socket? = null
        try {
            if (workerPorts.isEmpty()) {
                clientSock.close()
                return
            }
            val pickIdx = (roundRobinIndex.getAndIncrement() and Int.MAX_VALUE) % workerPorts.size
            val targetPort = workerPorts[pickIdx]

            workerSock = Socket().apply {
                tcpNoDelay = true
                trafficClass = 0x18
                sendBufferSize = 262144
                receiveBufferSize = 262144
                connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
            }

            val cIn = clientSock.getInputStream()
            val cOut = clientSock.getOutputStream()
            val wIn = workerSock.getInputStream()
            val wOut = workerSock.getOutputStream()

            val upstreamJob = balancerPool.submit {
                try {
                    val buf = ByteArray(131072)
                    while (isRunning) {
                        val n = cIn.read(buf)
                        if (n == -1) break
                        wOut.write(buf, 0, n)
                    }
                    wOut.flush()
                } catch (_: Throwable) {
                } finally {
                    try { workerSock.close() } catch (_: Throwable) {}
                }
            }

            val buf = ByteArray(131072)
            while (isRunning) {
                val n = wIn.read(buf)
                if (n == -1) break
                cOut.write(buf, 0, n)
            }
            cOut.flush()
            upstreamJob.cancel(true)
        } catch (_: Throwable) {
        } finally {
            try { clientSock.close() } catch (_: Throwable) {}
            try { workerSock?.close() } catch (_: Throwable) {}
        }
    }

    fun stopSlowDns() {
        isRunning = false
        // Automatically turn OFF SlowDNS Boost when stopped
        VpnStateBridge.slowDnsBoostEnabled.value = false
        try { balancerServer?.close() } catch (_: Throwable) {}
        balancerServer = null

        val procs = ArrayList(workerProcesses)
        workerProcesses.clear()
        balancerPool.execute {
            procs.forEach { proc ->
                try {
                    proc.destroy()
                    proc.destroyForcibly()
                } catch (_: Throwable) {}
            }
        }
    }
}
// === END OF FILE 6 ===
