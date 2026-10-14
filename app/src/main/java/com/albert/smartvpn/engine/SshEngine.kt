package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

object SshEngine {
    private const val TAG = "SshEngine"
    private var sshClient: SSHClient? = null
    @Volatile private var isRunning = false
    private var localServer: ServerSocket? = null
    private val activeBridges = mutableListOf<ServerSocket>()

    private fun setupBouncyCastleProvider() {
        try {
            Security.removeProvider("BC")
            val bcClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            val provider = bcClass.getDeclaredConstructor().newInstance() as Provider
            Security.insertProviderAt(provider, 1)
            SecurityUtils.setRegisterBouncyCastle(false)
        } catch (t: Throwable) {
            Log.w(TAG, "BC provider setup note: ${t.message}")
        }
    }

    private fun buildAndroidSafeSshConfig(): DefaultConfig {
        setupBouncyCastleProvider()
        val config = DefaultConfig()
        val x25519Works = try {
            KeyPairGenerator.getInstance("X25519", "BC")
            true
        } catch (_: Throwable) {
            false
        }
        if (!x25519Works) {
            config.keyExchangeFactories = config.keyExchangeFactories.filterNot {
                it.name.contains("25519", ignoreCase = true) ||
                    it.name.contains("448", ignoreCase = true)
            }
            config.keyAlgorithms = config.keyAlgorithms.filterNot {
                it.name.contains("25519", ignoreCase = true) ||
                    it.name.contains("448", ignoreCase = true)
            }
        }
        return config
    }

    private fun createTrustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }

    fun startSshTunnel(
        host: String,
        port: Int,
        user: String,
        pass: String,
        localPort: Int,
        payloadTemplate: String = "",
        sni: String = "",
        proxyHost: String = "",
        proxyPort: Int = 8080,
        isSlowDns: Boolean = false
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
            val statusLabel = if (isSlowDns) "Connecting SSH over SlowDNS..." else "Connecting to $cleanHost:$port..."
            VpnStateBridge.updateState("CONNECTING", statusLabel)
            VpnStateBridge.addLog("Connecting SSH session -> $cleanHost:$port (User: $cleanUser)")

            val sshConfig = buildAndroidSafeSshConfig()
            sshClient = SSHClient(sshConfig).apply {
                addHostKeyVerifier(PromiscuousVerifier())
                connectTimeout = if (isSlowDns) 30000 else 15000
                timeout = if (isSlowDns) 45000 else 20000

                if (VpnStateBridge.sshCompression.value) {
                    try { useCompression() } catch (_: Throwable) {}
                }

                val keepAliveSec = VpnStateBridge.pingIntervalSec.value.coerceIn(5, 120)
                connection.keepAlive.keepAliveInterval = keepAliveSec

                if (!isSlowDns && (sni.isNotBlank() || proxyHost.isNotBlank() || payloadTemplate.isNotBlank())) {
                    socketFactory = createCustomTunnelSocketFactory(
                        targetHost = cleanHost,
                        targetPort = port,
                        payloadTemplate = payloadTemplate,
                        sni = sni.trim(),
                        proxyHost = proxyHost.trim(),
                        proxyPort = proxyPort
                    )
                }
            }

            sshClient?.connect(cleanHost, port)

            VpnStateBridge.updateState("CONNECTING", "Authenticating $cleanUser...")
            VpnStateBridge.addLog("Authenticating SSH user '$cleanUser'...")
            sshClient?.authPassword(cleanUser, pass)

            if (sshClient?.isAuthenticated == true) {
                isRunning = true
                VpnStateBridge.addLog("SSH Authentication SUCCESSFUL for '$cleanUser'.")
                val bindHost = if (VpnStateBridge.hotshareEnabled.value) "0.0.0.0" else "127.0.0.1"
                VpnStateBridge.addLog("Starting local SOCKS5 forwarder on $bindHost:$localPort...")
                startLocalSocks5Server(bindHost, localPort)
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

    private fun createCustomTunnelSocketFactory(
        targetHost: String,
        targetPort: Int,
        payloadTemplate: String,
        sni: String,
        proxyHost: String,
        proxyPort: Int
    ): SocketFactory {
        return object : SocketFactory() {
            override fun createSocket(): Socket = openTunneledSocket()
            override fun createSocket(host: String?, port: Int): Socket = openTunneledSocket()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = openTunneledSocket()
            override fun createSocket(host: InetAddress?, port: Int): Socket = openTunneledSocket()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = openTunneledSocket()

            private fun openTunneledSocket(): Socket {
                val tcpHost = if (proxyHost.isNotEmpty()) proxyHost else targetHost
                val tcpPort = if (proxyHost.isNotEmpty()) proxyPort else targetPort

                VpnStateBridge.addLog("Opening TCP socket to $tcpHost:$tcpPort...")
                val baseSocket = Socket()
                baseSocket.tcpNoDelay = true
                baseSocket.connect(InetSocketAddress(tcpHost, tcpPort), 15000)

                val shouldUseTls = (tcpPort == 443 || tcpPort == 8443 || targetPort == 443) ||
                    (sni.isNotEmpty() && payloadTemplate.isBlank())

                val activeSocket: Socket = if (shouldUseTls) {
                    val sniHost = sni.ifEmpty { targetHost }
                    VpnStateBridge.addLog("Performing SSL/TLS Handshake (SNI: $sniHost)...")
                    val sslFactory = createTrustAllSslSocketFactory()
                    val sslSock = sslFactory.createSocket(baseSocket, sniHost, tcpPort, true) as SSLSocket
                    val params = sslSock.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    sslSock.sslParameters = params
                    sslSock.startHandshake()
                    VpnStateBridge.addLog("SSL/TLS Handshake established (${sslSock.session.protocol}).")
                    sslSock
                } else {
                    baseSocket
                }

                if (payloadTemplate.isNotBlank()) {
                    val formatted = PayloadGenerator.generatePayload(
                        payloadTemplate,
                        targetHost,
                        targetPort,
                        sni.ifEmpty { targetHost }
                    )
                    val out: OutputStream = activeSocket.getOutputStream()
                    val chunks = formatted.split(Regex("\\[(instant_)?split\\]", RegexOption.IGNORE_CASE))
                    for ((index, chunk) in chunks.withIndex()) {
                        if (chunk.isNotEmpty()) {
                            out.write(chunk.toByteArray(Charsets.UTF_8))
                            out.flush()
                            if (index < chunks.size - 1) {
                                Thread.sleep(50)
                            }
                        }
                    }
                    VpnStateBridge.addLog("Injected HTTP/WS Payload (${formatted.length} bytes).")

                    val ins: InputStream = activeSocket.getInputStream()
                    val headerBuf = StringBuilder()
                    val b = ByteArray(1)
                    while (headerBuf.length < 4096 && ins.read(b) != -1) {
                        headerBuf.append(b[0].toInt().toChar())
                        if (headerBuf.endsWith("\r\n\r\n")) {
                            break
                        }
                    }
                    val firstLine = headerBuf.lineSequence().firstOrNull()?.trim() ?: ""
                    if (firstLine.isNotEmpty()) {
                        VpnStateBridge.addLog("HTTP/WS Response: $firstLine")
                    }
                }
                return activeSocket
            }
        }
    }

    private fun startLocalSocks5Server(bindHost: String, localPort: Int) {
        thread(start = true, name = "SSH-SOCKS5-Server") {
            try {
                localServer = ServerSocket()
                localServer?.reuseAddress = true
                localServer?.bind(InetSocketAddress(bindHost, localPort))
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

            val reqVer = ins.read()
            val cmd = ins.read()
            ins.read()
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
