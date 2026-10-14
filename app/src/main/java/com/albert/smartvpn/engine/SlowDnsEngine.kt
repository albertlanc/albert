package com.albert.smartvpn.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import kotlin.concurrent.thread

object SlowDnsEngine {
    private const val TAG = "SlowDnsEngine"
    private var dnsttProcess: Process? = null
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

        val resolverAddr = "$resolvedDnsIp:$dnsPort"
        val listenAddr = "127.0.0.1:$localPort"

        return try {
            VpnStateBridge.updateState("CONNECTING", "Starting SlowDNS...")
            VpnStateBridge.addLog("Starting SlowDNS (DNSTT) -> Resolver: $resolverAddr | NS: $cleanNs")

            val cmd = when {
                resolvedDnsIp.startsWith("https://", ignoreCase = true) ->
                    listOf(dnsttBin.absolutePath, "-doh", resolvedDnsIp, "-pubkey", cleanPubKey, cleanNs, listenAddr)
                resolvedDnsIp.startsWith("tls://", ignoreCase = true) ->
                    listOf(dnsttBin.absolutePath, "-dot", resolvedDnsIp.removePrefix("tls://"), "-pubkey", cleanPubKey, cleanNs, listenAddr)
                else ->
                    listOf(dnsttBin.absolutePath, "-udp", resolverAddr, "-pubkey", cleanPubKey, cleanNs, listenAddr)
            }

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            dnsttProcess = pb.start()
            isRunning = true

            thread(start = true, name = "SlowDNS-Logger") {
                try {
                    val proc = dnsttProcess ?: return@thread
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (isRunning) {
                        val line = reader.readLine() ?: break
                        val text = line.trim()
                        if (text.isNotEmpty()) {
                            Log.d(TAG, text)
                            VpnStateBridge.addLog("[DNSTT] $text")
                        }
                    }
                    if (isRunning) {
                        val exitCode = try { proc.waitFor() } catch (_: Throwable) { -1 }
                        VpnStateBridge.addLog("[DNSTT] Process exited (code $exitCode)")
                    }
                } catch (_: Throwable) {}
            }

            // Wait 1.2s for dnstt-client to bind 127.0.0.1:7000 without opening a dummy socket
            Thread.sleep(1200)

            if (dnsttProcess?.isAlive == true) {
                VpnStateBridge.addLog("SlowDNS listening on $listenAddr -> Launching SSH session...")
                true
            } else {
                val exitCode = try { dnsttProcess?.exitValue() } catch (_: Throwable) { -1 }
                VpnStateBridge.addLog("SlowDNS Error: dnstt exited early (code $exitCode). Check Public Key or NS Domain.")
                stopSlowDns()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SlowDNS Error", t)
            VpnStateBridge.addLog("SlowDNS Error: ${t.message}")
            stopSlowDns()
            false
        }
    }

    fun stopSlowDns() {
        isRunning = false
        try {
            dnsttProcess?.destroy()
            dnsttProcess?.destroyForcibly()
        } catch (_: Throwable) {}
        dnsttProcess = null
    }
}
