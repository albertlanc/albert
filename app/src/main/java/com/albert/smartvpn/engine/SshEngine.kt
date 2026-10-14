package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object SshEngine {
    private const val TAG = "SshEngine"
    private var sshClient: SSHClient? = null
    @Volatile private var isRunning = false
    private var localServer: ServerSocket? = null
    private val activeBridges = mutableListOf<ServerSocket>()

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
            VpnStateBridge.addLog("Connecting to SSH server $cleanHost:$port...")

            sshClient = SSHClient().apply {
                addHostKeyVerifier(PromiscuousVerifier())
                connectTimeout = 10000
                timeout = 15000
            }

            sshClient?.connect(cleanHost, port)

            VpnStateBridge.updateState("CONNECTING", "Authenticating $cleanUser...")
            VpnStateBridge.addLog("Authenticating SSH user '$cleanUser'...")
            sshClient?.authPassword(cleanUser, pass)

            if (sshClient?.isAuthenticated == true) {
                isRunning = true
                VpnStateBridge.addLog("SSH Authentication SUCCESSFUL for '$cleanUser'.")
                VpnStateBridge.addLog("Starting local SOCKS5 server on 127.0.0.1:$localPort...")
                startLocalSocks5Server(localPort)
                true
            } else {
                VpnStateBridge.addLog("SSH Auth Failed: Invalid username or password.")
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
                VpnStateBridge.addLog("SSH Auth Failed: Wrong username or password!")
            } else {
                VpnStateBridge.addLog("SSH Connection Failed: $msg")
            }
            stopSshTunnel()
            false
        }
    }

    private fun startLocalSocks5Server(localPort: Int) {
        thread(start = true, name = "SSH-SOCKS5-Server") {
            try {
                localServer = ServerSocket()
                localServer?.reuseAddress = true
                localServer?.bind(InetSocketAddress("127.0.0.1", localPort))
                while (isRunning) {
                    val clientSocket = localServer?.accept() ?: break
                    thread(start = true) {
                        handleSocks5Client(clientSocket)
                    }
                }
            } catch (t: Throwable) {
                if (isRunning) {
                    VpnStateBridge.addLog("Local SOCKS5 server stopped: ${t.message}")
                }
            }
        }
    }

    private fun handleSocks5Client(client: Socket) {
        var bridgeServer: ServerSocket? = null
        var bridgeSocket: Socket? = null
        try {
            val ins = client.getInputStream()
            val outs = client.getOutputStream()

            // SOCKS5 Greeting
            val ver = ins.read()
            if (ver != 5) {
                client.close()
                return
            }
            val nMethods = ins.read()
            if (nMethods > 0) {
                val methods = ByteArray(nMethods)
                ins.read(methods)
            }
            outs.write(byteArrayOf(0x05, 0x00))
            outs.flush()

            // SOCKS5 Request
            val reqVer = ins.read()
            val cmd = ins.read()
            ins.read() // RSV
            val atyp = ins.read()
            if (reqVer != 5 || cmd != 1) {
                client.close()
                return
            }

            val targetHost = when (atyp) {
                1 -> {
                    val addr = ByteArray(4)
                    ins.read(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                3 -> {
                    val len = ins.read()
                    val domain = ByteArray(len)
                    ins.read(domain)
                    String(domain)
                }
                4 -> {
                    val addr = ByteArray(16)
                    ins.read(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                else -> ""
            }
            val portHigh = ins.read()
            val portLow = ins.read()
            val targetPort = (portHigh shl 8) or portLow

            // Bridge through SSHJ's standard LocalPortForwarder
            bridgeServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val bridgePort = bridgeServer.localPort
            synchronized(activeBridges) { activeBridges.add(bridgeServer) }

            val params = Parameters("127.0.0.1", bridgePort, targetHost, targetPort)
            thread(start = true) {
                try {
                    sshClient?.newLocalPortForwarder(params, bridgeServer)?.listen()
                } catch (_: Throwable) {}
            }

            bridgeSocket = Socket("127.0.0.1", bridgePort)

            // Reply SOCKS5 Success
            outs.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outs.flush()

            val sshIn = bridgeSocket.getInputStream()
            val sshOut = bridgeSocket.getOutputStream()

            val t1 = thread(start = true) {
                try {
                    ins.copyTo(sshOut)
                } catch (_: Throwable) {
                } finally {
                    try { bridgeSocket.close() } catch (_: Throwable) {}
                }
            }
            try {
                sshIn.copyTo(outs)
            } catch (_: Throwable) {}
            t1.join(200)
        } catch (_: Throwable) {
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { bridgeSocket?.close() } catch (_: Throwable) {}
            try {
                bridgeServer?.close()
                synchronized(activeBridges) { activeBridges.remove(bridgeServer) }
            } catch (_: Throwable) {}
        }
    }

    fun stopSshTunnel() {
        isRunning = false
        synchronized(activeBridges) {
            activeBridges.forEach {
                try { it.close() } catch (_: Throwable) {}
            }
            activeBridges.clear()
        }
        try { localServer?.close() } catch (_: Throwable) {}
        localServer = null
        try {
            sshClient?.disconnect()
            sshClient?.close()
        } catch (_: Throwable) {}
        sshClient = null
    }
}
