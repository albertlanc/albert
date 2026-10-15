package com.albert.smartvpn.engine

import android.content.Context
import android.util.Base64
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
    private const val BOOST_INSTANCES = 15
    private const val BASE_WORKER_PORT = 7001

    private val workerProcesses = CopyOnWriteArrayList<Process>()
    private val activeWorkerPorts = CopyOnWriteArrayList<Int>()
    private var balancerServer: ServerSocket? = null
    private val balancerPool = Executors.newCachedThreadPool()
    private val roundRobinIndex = AtomicInteger(0)
    @Volatile private var isRunning = false

    fun getActiveWorkerPorts(): List<Int> {
        return if (activeWorkerPorts.isNotEmpty()) {
            activeWorkerPorts.toList()
        } else {
            listOf(7000)
        }
    }

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

        val rawDnsHost = dnsServerIp.trim().ifEmpty { "8.8.8.8" }.substringBefore(":")
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

        val boostActive = VpnStateBridge.slowDnsBoostEnabled.value
        val workerCount = if (boostActive) BOOST_INSTANCES else 1

        return try {
            isRunning = true
            VpnStateBridge.updateState("CONNECTING", "Connecting...")
            VpnStateBridge.addLog(if (boostActive) "Connecting (SlowDNS Boost)..." else "Connecting...")

            val resolverAddr = "$primaryDnsIp:$dnsPort"

            for (idx in 0 until workerCount) {
                val workerPort = BASE_WORKER_PORT + idx
                val workerListen = "127.0.0.1:$workerPort"

                val cmd = listOf(
                    dnsttBin.absolutePath,
                    "-udp",
                    resolverAddr,
                    "-pubkey",
                    cleanPubKey,
                    cleanNs,
                    workerListen
                )

                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                workerProcesses.add(proc)
                activeWorkerPorts.add(workerPort)

                // Drain stdout silently so pipe buffers never block
                balancerPool.execute {
                    try {
                        val ins = proc.inputStream
                        val buf = ByteArray(512)
                        while (isRunning && ins.read(buf) != -1) {}
                    } catch (_: Throwable) {}
                }
            }

            // Also keep port 7000 bound as a round-robin fallback balancer
            balancerServer = ServerSocket()
            balancerServer?.reuseAddress = true
            balancerServer?.bind(InetSocketAddress("127.0.0.1", localPort), 128)

            thread(start = true, name = "SlowDNS-Balancer") {
                while (isRunning) {
                    try {
                        val clientSock = balancerServer?.accept() ?: break
                        clientSock.tcpNoDelay = true
                        clientSock.sendBufferSize = 131072
                        clientSock.receiveBufferSize = 131072

                        balancerPool.execute {
                            bridgeToBoostWorker(clientSock, activeWorkerPorts)
                        }
                    } catch (_: Throwable) {
                        if (!isRunning) break
                    }
                }
            }

            Thread.sleep(750)

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
            VpnStateBridge.addDebugOnlyLog("SlowDNS Error: ${t.message}")
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

            workerSock = Socket()
            workerSock.tcpNoDelay = true
            workerSock.sendBufferSize = 131072
            workerSock.receiveBufferSize = 131072
            workerSock.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)

            val cIn = clientSock.getInputStream()
            val cOut = clientSock.getOutputStream()
            val wIn = workerSock.getInputStream()
            val wOut = workerSock.getOutputStream()

            val upstreamJob = balancerPool.submit {
                try {
                    val buf = ByteArray(65536)
                    while (isRunning) {
                        val n = cIn.read(buf)
                        if (n == -1) break
                        wOut.write(buf, 0, n)
                        if (cIn.available() == 0) wOut.flush()
                    }
                    wOut.flush()
                } catch (_: Throwable) {
                } finally {
                    try { workerSock.close() } catch (_: Throwable) {}
                }
            }

            val buf = ByteArray(65536)
            while (isRunning) {
                val n = wIn.read(buf)
                if (n == -1) break
                cOut.write(buf, 0, n)
                if (wIn.available() == 0) cOut.flush()
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
        try { balancerServer?.close() } catch (_: Throwable) {}
        balancerServer = null
        activeWorkerPorts.clear()

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
