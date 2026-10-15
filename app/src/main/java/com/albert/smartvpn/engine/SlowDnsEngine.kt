package com.albert.smartvpn.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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

    private val boostResolvers = listOf(
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9",
        "149.112.112.112", "208.67.222.222", "208.67.220.220", "4.2.2.1", "4.2.2.2"
    )

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

        if (cleanNs.isEmpty()) {
            VpnStateBridge.addLog("SlowDNS Error: Name Server (NS Domain) is empty!")
            return false
        }
        if (cleanPubKey.length != 64) {
            VpnStateBridge.addLog("SlowDNS Error: Public Key must be 64 hex chars (got ${cleanPubKey.length}).")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val dnsttBin = File(nativeDir, "libdnstt.so")
        if (!dnsttBin.exists()) {
            VpnStateBridge.addLog("SlowDNS Error: Native libdnstt.so binary not found.")
            return false
        }
        dnsttBin.setExecutable(true)

        val primaryDnsIp = try {
            InetAddress.getByName(rawDnsHost).hostAddress ?: rawDnsHost
        } catch (_: Throwable) {
            rawDnsHost
        }

        return try {
            isRunning = true
            VpnStateBridge.updateState("CONNECTING", "Starting 15x SlowDNS Boost...")
            VpnStateBridge.addLog("Activating SlowDNS 15x Turbo Boost -> NS: $cleanNs")

            val activeWorkerPorts = CopyOnWriteArrayList<Int>()

            // Spawn 15 internal parallel dnstt-client instances on ports 7001..7015
            for (idx in 0 until BOOST_INSTANCES) {
                val workerPort = BASE_WORKER_PORT + idx
                val workerListen = "127.0.0.1:$workerPort"

                // First 10 instances lock onto the user's primary DNS server IP;
                // remaining 5 instances stripe across parallel DNS resolvers for multi-path speed boost
                val targetResolverIp = if (idx < 10) {
                    primaryDnsIp
                } else {
                    boostResolvers[(idx - 10) % boostResolvers.size]
                }
                val resolverAddr = "$targetResolverIp:$dnsPort"

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

                // Log primary worker events without flooding the UI
                if (idx == 0) {
                    thread(start = true, name = "SlowDNS-Primary-Logger") {
                        try {
                            val reader = BufferedReader(InputStreamReader(proc.inputStream))
                            while (isRunning) {
                                val line = reader.readLine() ?: break
                                val text = line.trim()
                                if (text.isNotEmpty()) {
                                    Log.d(TAG, text)
                                    VpnStateBridge.addLog("[DNSTT-Boost] $text")
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                } else {
                    // Drain stdout of worker instances 2..15 so their pipe buffers never block
                    balancerPool.execute {
                        try {
                            val ins = proc.inputStream
                            val buf = ByteArray(512)
                            while (isRunning && ins.read(buf) != -1) {}
                        } catch (_: Throwable) {}
                    }
                }
            }

            // Start the internal Multi-Path Load Balancer on 127.0.0.1:7000
            balancerServer = ServerSocket()
            balancerServer?.reuseAddress = true
            balancerServer?.bind(InetSocketAddress("127.0.0.1", localPort), 64)

            thread(start = true, name = "SlowDNS-15x-Balancer") {
                while (isRunning) {
                    try {
                        val clientSock = balancerServer?.accept() ?: break
                        clientSock.tcpNoDelay = true
                        clientSock.sendBufferSize = 65536
                        clientSock.receiveBufferSize = 65536

                        balancerPool.execute {
                            bridgeToBoostWorker(clientSock, activeWorkerPorts)
                        }
                    } catch (_: Throwable) {
                        if (!isRunning) break
                    }
                }
            }

            Thread.sleep(1000)

            val aliveCount = workerProcesses.count { it.isAlive }
            if (aliveCount > 0) {
                VpnStateBridge.addLog("SlowDNS Boost Active: $aliveCount/$BOOST_INSTANCES parallel engines bound to 127.0.0.1:$localPort")
                true
            } else {
                VpnStateBridge.addLog("SlowDNS Error: All boost workers exited. Check Public Key or NS Domain.")
                stopSlowDns()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SlowDNS Boost Error", t)
            VpnStateBridge.addLog("SlowDNS Error: ${t.message}")
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
            workerSock.sendBufferSize = 65536
            workerSock.receiveBufferSize = 65536
            workerSock.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)

            val cIn = clientSock.getInputStream()
            val cOut = clientSock.getOutputStream()
            val wIn = workerSock.getInputStream()
            val wOut = workerSock.getOutputStream()

            val upstreamJob = balancerPool.submit {
                try {
                    val buf = ByteArray(32768)
                    while (isRunning) {
                        val n = cIn.read(buf)
                        if (n == -1) break
                        wOut.write(buf, 0, n)
                        wOut.flush()
                    }
                } catch (_: Throwable) {
                } finally {
                    try { workerSock.close() } catch (_: Throwable) {}
                }
            }

            val buf = ByteArray(32768)
            while (isRunning) {
                val n = wIn.read(buf)
                if (n == -1) break
                cOut.write(buf, 0, n)
                cOut.flush()
            }
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

        workerProcesses.forEach { proc ->
            try {
                proc.destroy()
                proc.destroyForcibly()
            } catch (_: Throwable) {}
        }
        workerProcesses.clear()
    }
}
