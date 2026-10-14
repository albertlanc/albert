package com.albert.smartvpn.engine

import android.content.Context
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

object SlowDnsEngine {
    private const val TAG = "SlowDnsEngine"
    private var dnsttProcess: Process? = null
    @Volatile private var isRunning = false

    fun startSlowDns(
        context: Context,
        dnsServerIp: String,
        dnsPort: Int = 53,
        nsDomain: String,
        pubKey: String,
        localPort: Int = 7000
    ): Boolean {
        stopSlowDns()

        val cleanDnsIp = dnsServerIp.trim().ifEmpty { "8.8.8.8" }
        val cleanNs = nsDomain.trim()
        val cleanPubKey = pubKey.replace(Regex("\\s+"), "")

        if (cleanNs.isEmpty()) {
            VpnStateBridge.addLog("SlowDNS Error: Name Server (NS Domain) is empty!")
            return false
        }
        if (cleanPubKey.isEmpty()) {
            VpnStateBridge.addLog("SlowDNS Error: Public Key is empty!")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val dnsttBin = File(nativeDir, "libdnstt.so")
        if (!dnsttBin.exists()) {
            VpnStateBridge.addLog("SlowDNS Error: Native libdnstt.so binary not found.")
            return false
        }
        dnsttBin.setExecutable(true)

        val resolverAddr = if (cleanDnsIp.contains(":")) cleanDnsIp else "$cleanDnsIp:$dnsPort"
        val listenAddr = "127.0.0.1:$localPort"

        return try {
            VpnStateBridge.updateState("CONNECTING", "Starting SlowDNS...")
            VpnStateBridge.addLog("Starting SlowDNS (DNSTT) -> Resolver: $resolverAddr | NS: $cleanNs")

            val cmd = when {
                cleanDnsIp.startsWith("https://", ignoreCase = true) ->
                    listOf(dnsttBin.absolutePath, "-doh", cleanDnsIp, "-pubkey", cleanPubKey, cleanNs, listenAddr)
                cleanDnsIp.startsWith("tls://", ignoreCase = true) ->
                    listOf(dnsttBin.absolutePath, "-dot", cleanDnsIp.removePrefix("tls://"), "-pubkey", cleanPubKey, cleanNs, listenAddr)
                else ->
                    listOf(dnsttBin.absolutePath, "-udp", resolverAddr, "-pubkey", cleanPubKey, cleanNs, listenAddr)
            }

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            dnsttProcess = pb.start()
            isRunning = true

            thread(start = true, name = "SlowDNS-Logger") {
                try {
                    val reader = BufferedReader(InputStreamReader(dnsttProcess!!.inputStream))
                    var line: String?
                    while (isRunning && reader.readLine().also { line = it } != null) {
                        val text = line?.trim() ?: continue
                        if (text.isNotEmpty()) {
                            Log.d(TAG, text)
                            VpnStateBridge.addLog("[DNSTT] $text")
                        }
                    }
                } catch (_: Throwable) {}
            }

            var bound = false
            for (attempt in 1..20) {
                if (dnsttProcess?.isAlive == false) {
                    VpnStateBridge.addLog("SlowDNS Error: dnstt exited. Check Public Key or NS Domain.")
                    stopSlowDns()
                    return false
                }
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress("127.0.0.1", localPort), 200)
                        bound = true
                    }
                    if (bound) break
                } catch (_: Throwable) {
                    Thread.sleep(200)
                }
            }

            if (dnsttProcess?.isAlive == true) {
                VpnStateBridge.addLog("SlowDNS tunnel listening on $listenAddr")
                true
            } else {
                VpnStateBridge.addLog("SlowDNS failed to bind local port.")
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
