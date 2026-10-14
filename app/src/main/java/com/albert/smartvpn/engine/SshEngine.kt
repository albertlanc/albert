package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

object SshEngine {
    private const val TAG = "SshEngine"
    private var sshClient: SSHClient? = null
    private var isRunning = false
    private var localServer: ServerSocket? = null
    private var preSocket: Socket? = null

    fun startSshTunnel(
        host: String,
        port: Int,
        user: String,
        pass: String,
        localPort: Int,
        payloadTemplate: String = "",
        sni: String = "",
        proxyHost: String = "",
        proxyPort: Int = 8080
    ): Boolean {
        if (isRunning) stopSshTunnel()

        val cleanHost = host.trim()
        val cleanUser = user.trim()

        if (cleanHost.isEmpty()) {
            VpnStateBridge.addLog("SSH Error: Server host is empty.")
            return false
        }
        if (cleanUser.isEmpty() || pass.isEmpty()) {
            VpnStateBridge.addLog("SSH Auth Error: Username or password is empty!")
            return false
        }

        return try {
            VpnStateBridge.updateState("CONNECTING", "Connecting to $cleanHost:$port...")
            VpnStateBridge.addLog("Initializing SSH engine for $cleanHost:$port (User: $cleanUser)")

            // Optional HTTP Payload / SSL SNI pre-handshake verification
            if (sni.isNotBlank()) {
                VpnStateBridge.addLog("Performing TLS SNI handshake with Bug Host: $sni")
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val rawSock = Socket()
                rawSock.connect(InetSocketAddress(cleanHost, port), 10000)
                val sslSock = factory.createSocket(rawSock, sni.trim(), port, true) as SSLSocket
                val params = sslSock.sslParameters
                params.serverNames = listOf(SNIHostName(sni.trim()))
                sslSock.sslParameters = params
                sslSock.startHandshake()
                VpnStateBridge.addLog("TLS SNI handshake succeeded (${sslSock.session.protocol}).")
                preSocket = sslSock
            } else if (proxyHost.isNotBlank()) {
                VpnStateBridge.addLog("Connecting via Remote Proxy $proxyHost:$proxyPort...")
                val proxySock = Socket()
                proxySock.connect(InetSocketAddress(proxyHost.trim(), proxyPort), 10000)
                if (payloadTemplate.isNotBlank()) {
                    val formattedPayload = PayloadGenerator.generatePayload(
                        payloadTemplate,
                        cleanHost,
                        port,
                        sni
                    )
                    VpnStateBridge.addLog("Injecting HTTP Payload (${formattedPayload.length} bytes)...")
                    val out: OutputStream = proxySock.getOutputStream()
                    out.write(formattedPayload.toByteArray())
                    out.flush()
                    val inp: InputStream = proxySock.getInputStream()
                    val buf = ByteArray(1024)
                    val read = inp.read(buf)
                    if (read > 0) {
                        val firstLine = String(buf, 0, read).lineSequence().firstOrNull() ?: ""
                        VpnStateBridge.addLog("Proxy response: $firstLine")
                    }
                }
                preSocket = proxySock
            }

            sshClient = SSHClient().apply {
                addHostKeyVerifier(PromiscuousVerifier())
                connectTimeout = 12000
                timeout = 15000
            }

            VpnStateBridge.addLog("Opening SSH transport to $cleanHost:$port...")
            sshClient?.connect(cleanHost, port)

            VpnStateBridge.updateState("CONNECTING", "Authenticating $cleanUser...")
            VpnStateBridge.addLog("Authenticating SSH user '$cleanUser'...")
            sshClient?.authPassword(cleanUser, pass)

            if (sshClient?.isAuthenticated == true) {
                isRunning = true
                VpnStateBridge.addLog("SSH Authentication SUCCESSFUL for user '$cleanUser'.")
                VpnStateBridge.addLog("Starting local port forwarder on 127.0.0.1:$localPort...")
                startLocalForwarding(localPort)
                true
            } else {
                VpnStateBridge.addLog("SSH Authentication FAILED: Invalid username or password.")
                stopSshTunnel()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SSH Tunnel Error", t)
            val msg = t.message ?: t.javaClass.simpleName
            if (msg.contains("Exhausted available authentication", ignoreCase = true) ||
                msg.contains("Auth fail", ignoreCase = true) ||
                msg.contains("UserAuthException", ignoreCase = true)
            ) {
                VpnStateBridge.addLog("SSH Login Rejected: Wrong username or password!")
            } else {
                VpnStateBridge.addLog("SSH Connection Failed: $msg")
            }
            stopSshTunnel()
            false
        }
    }

    private fun startLocalForwarding(localPort: Int) {
        thread(start = true, name = "SSH-Local-Forward") {
            try {
                localServer = ServerSocket()
                localServer?.reuseAddress = true
                localServer?.bind(InetSocketAddress("127.0.0.1", localPort))
                val params = Parameters("127.0.0.1", localPort, "127.0.0.1", 80)
                val forwarder = sshClient?.newLocalPortForwarder(params, localServer)
                forwarder?.listen()
            } catch (t: Throwable) {
                if (isRunning) {
                    Log.e(TAG, "Port forwarder dropped: ${t.message}")
                    VpnStateBridge.addLog("SSH forwarder stopped: ${t.message}")
                }
            }
        }
    }

    fun stopSshTunnel() {
        isRunning = false
        try {
            preSocket?.close()
        } catch (_: Throwable) {}
        preSocket = null

        try {
            localServer?.close()
        } catch (_: Throwable) {}
        localServer = null

        try {
            sshClient?.disconnect()
            sshClient?.close()
        } catch (_: Throwable) {}
        sshClient = null
    }
}
