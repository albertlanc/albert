package com.albert.smartvpn.engine

import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

object SshEngine {
    private const val TAG = "SshEngine"
    private var sshClient: SSHClient? = null
    private var isRunning = false
    private var localServer: ServerSocket? = null

    /**
     * Connects to the SSH server and opens a local port for routing traffic.
     */
    fun startSshTunnel(
        host: String,
        port: Int,
        user: String,
        pass: String,
        localPort: Int,
        payloadTemplate: String = "",
        sni: String = ""
    ): Boolean {
        if (isRunning) stopSshTunnel()
        
        return try {
            sshClient = SSHClient().apply {
                addHostKeyVerifier(PromiscuousVerifier()) // Bypasses strict host key checking for dynamic servers
                connectTimeout = 15000
                timeout = 0 // Keep-alive indefinitely
            }

            // TODO: Implement raw Socket proxy injection here for `payloadTemplate` before sshClient.connect()
            Log.d(TAG, "Connecting to SSH Server $host:$port...")
            sshClient?.connect(host, port)
            
            Log.d(TAG, "Authenticating user $user...")
            sshClient?.authPassword(user, pass)

            if (sshClient?.isAuthenticated == true) {
                isRunning = true
                Log.d(TAG, "SSH Authenticated. Starting local port forwarder on port $localPort...")
                startLocalForwarding(localPort)
                true
            } else {
                Log.e(TAG, "SSH Authentication failed.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSH Tunnel Error: ${e.message}")
            stopSshTunnel()
            false
        }
    }

    private fun startLocalForwarding(localPort: Int) {
        thread(start = true, name = "SSH-Local-Forward") {
            try {
                localServer = ServerSocket(localPort)
                val params = LocalPortForwarder.Parameters(
                    "0.0.0.0", localPort, "127.0.0.1", 80
                )
                val forwarder = sshClient?.newLocalPortForwarder(params, localServer)
                forwarder?.listen() // Blocks and routes traffic indefinitely
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Port forwarder dropped: ${e.message}")
            }
        }
    }

    fun stopSshTunnel() {
        isRunning = false
        try {
            localServer?.close()
            sshClient?.disconnect()
            sshClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing SSH: ${e.message}")
        }
        sshClient = null
    }
}
