package com.albert.smartvpn.engine

import android.content.Context
import android.util.Base64
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class SlowDnsNode(
    val cloneIndex: Int,
    val totalClones: Int,
    val profileName: String,
    val localPort: Int,
    val sshUser: String,
    val sshPass: String
)

object SlowDnsEngine {
    private const val BASE_WORKER_PORT = 7001
    private val workerProcesses = CopyOnWriteArrayList<Process>()
    private val drainPool = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false

    private val fastPublicDnsPool = listOf(
        "8.8.8.8", "1.1.1.1", "8.8.4.4", "1.0.0.1", "9.9.9.9", "208.67.222.222"
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

    /**
     * Launches a dedicated native dnstt-client process for every clone in the cluster.
     * If the user selected 1 SlowDNS profile and SlowDNS Boost is ON, it automatically expands
     * into 6 parallel internal boost clones so each gets its own KCP/Noise window and SSH transport.
     */
    fun startSlowDnsCluster(
        context: Context,
        selectedProfiles: List<VpnProfile>
    ): List<SlowDnsNode> {
        stopSlowDns()
        if (selectedProfiles.isEmpty()) return emptyList()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val dnsttBin = File(nativeDir, "libdnstt.so")
        if (!dnsttBin.exists()) {
            VpnStateBridge.addLog("SlowDNS Error: Native engine missing.")
            return emptyList()
        }
        dnsttBin.setExecutable(true)

        val boostEnabled = VpnStateBridge.slowDnsBoostEnabled.value
        val expandedProfiles: List<VpnProfile> = if (selectedProfiles.size == 1 && boostEnabled) {
            val base = selectedProfiles.first()
            (1..6).map { idx ->
                if (idx == 1) base else base.copy(name = "${base.name} (Boost #$idx)")
            }
        } else {
            selectedProfiles
        }

        val total = expandedProfiles.size
        val activeNodes = mutableListOf<SlowDnsNode>()
        isRunning = true

        VpnStateBridge.updateState("CONNECTING", "Starting $total SlowDNS Clones...")
        VpnStateBridge.addLog("Initializing $total SlowDNS tunnel instance(s)...")

        for ((index, prof) in expandedProfiles.withIndex()) {
            val cleanNs = prof.slowDnsDomain.trim()
            val cleanPubKey = normalizePubKeyToHex(prof.slowDnsPubKey.ifBlank { prof.pbk })
            if (cleanNs.isEmpty() || cleanPubKey.length != 64) {
                VpnStateBridge.addLog("Skipped ${prof.name}: Invalid NS Domain or 64-char Hex Public Key.")
                continue
            }

            val rawDnsHost = prof.slowDnsServerIp.trim().ifEmpty { "8.8.8.8" }.substringBefore(":")
            val primaryDnsIp = try {
                InetAddress.getByName(rawDnsHost).hostAddress ?: rawDnsHost
            } catch (_: Throwable) {
                rawDnsHost
            }

            // If user configured a standard public DNS (8.8.8.8 / 1.1.1.1), stripe clones across
            // fast resolvers to avoid per-IP UDP rate-limiting; if it's a private VPS IP, keep it on primaryDnsIp
            val resolverIp = if (primaryDnsIp in fastPublicDnsPool && index > 0) {
                fastPublicDnsPool[index % fastPublicDnsPool.size]
            } else {
                primaryDnsIp
            }

            val workerPort = BASE_WORKER_PORT + index
            val listenAddr = "127.0.0.1:$workerPort"
            val resolverAddr = "$resolverIp:53"

            try {
                val cmd = listOf(
                    dnsttBin.absolutePath,
                    "-udp",
                    resolverAddr,
                    "-pubkey",
                    cleanPubKey,
                    cleanNs,
                    listenAddr
                )
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                workerProcesses.add(proc)

                drainPool.execute {
                    try {
                        val ins = proc.inputStream
                        val buf = ByteArray(512)
                        while (isRunning && ins.read(buf) != -1) {}
                    } catch (_: Throwable) {}
                }

                activeNodes.add(
                    SlowDnsNode(
                        cloneIndex = index + 1,
                        totalClones = total,
                        profileName = prof.name,
                        localPort = workerPort,
                        sshUser = prof.sshUser.ifBlank { prof.uuid },
                        sshPass = prof.sshPass
                    )
                )
            } catch (t: Throwable) {
                VpnStateBridge.addDebugOnlyLog("Failed to spawn SlowDNS worker #${index + 1}: ${t.message}")
            }
        }

        Thread.sleep(900)

        val alive = workerProcesses.count { it.isAlive }
        if (alive == 0) {
            VpnStateBridge.addLog("SlowDNS Error: Could not bind DNS workers.")
            stopSlowDns()
            return emptyList()
        }

        return activeNodes
    }

    fun stopSlowDns() {
        isRunning = false
        val procs = ArrayList(workerProcesses)
        workerProcesses.clear()
        drainPool.execute {
            procs.forEach { proc ->
                try {
                    proc.destroy()
                    proc.destroyForcibly()
                } catch (_: Throwable) {}
            }
        }
    }
}
