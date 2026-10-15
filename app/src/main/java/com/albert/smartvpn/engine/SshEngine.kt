package com.albert.smartvpn.engine

import android.os.Build
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

object SshEngine {
    private const val TAG = "SshEngine"
    private val sshPool = CopyOnWriteArrayList<SSHClient>()
    private val poolSelector = AtomicInteger(0)
    @Volatile private var isRunning = false
    private var localServer: ServerSocket? = null
    private val activeBridges = CopyOnWriteArrayList<ServerSocket>()
    private val activeUdpSockets = CopyOnWriteArrayList<DatagramSocket>()
    private val ioThreadPool = Executors.newCachedThreadPool()

    private val tlsPorts = setOf(443, 8443, 2053, 2083, 2087, 2096)

    private fun setupBouncyCastleProvider() {
        try {
            Security.removeProvider("BC")
            val bcClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            val provider = bcClass.getDeclaredConstructor().newInstance() as Provider
            Security.insertProviderAt(provider, 1)
            SecurityUtils.setRegisterBouncyCastle(false)
        } catch (t: Throwable) {
            Log.d(TAG, "BC provider note: ${t.message}")
        }
    }

    private fun buildAndroidSafeSshConfig(isSlowDns: Boolean): DefaultConfig {
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

        config.cipherFactories = config.cipherFactories.sortedBy {
            when {
                it.name.contains("aes128-ctr", ignoreCase = true) -> 0
                it.name.contains("aes128-gcm", ignoreCase = true) -> 1
                it.name.contains("aes256-ctr", ignoreCase = true) -> 2
                else -> 10
            }
        }

        if (isSlowDns) {
            val fastKex = config.keyExchangeFactories.filter {
                it.name.contains("25519", ignoreCase = true) ||
                    it.name.contains("nistp256", ignoreCase = true) ||
                    it.name.contains("group14", ignoreCase = true)
            }
            if (fastKex.isNotEmpty()) config.keyExchangeFactories = fastKex

            val fastMacs = config.macFactories.filter {
                it.name.equals("hmac-sha2-256", ignoreCase = true) ||
                    it.name.equals("hmac-sha1", ignoreCase = true)
            }
            if (fastMacs.isNotEmpty()) config.macFactories = fastMacs
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

    private fun createConnectedSshClient(
        mode: String,
        cleanHost: String,
        port: Int,
        cleanUser: String,
        pass: String,
        payloadTemplate: String,
        sni: String,
        proxyHost: String,
        proxyPort: Int,
        isSlowDns: Boolean,
        emitUiLogs: Boolean
    ): SSHClient? {
        val sshConfig = buildAndroidSafeSshConfig(isSlowDns)
        val useCustomFactory = !isSlowDns && mode != "SSH"

        val client = SSHClient(sshConfig).apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = if (isSlowDns) 45000 else 15000
            timeout = 0

            if (VpnStateBridge.sshCompression.value) {
                try { useCompression() } catch (_: Throwable) {}
            }

            connection.windowSize = 8388608L
            connection.maxPacketSize = 65536
            val keepAliveSec = VpnStateBridge.pingIntervalSec.value.coerceIn(5, 120)
            connection.keepAlive.keepAliveInterval = keepAliveSec

            if (useCustomFactory) {
                socketFactory = createCustomTunnelSocketFactory(
                    mode = mode,
                    targetHost = cleanHost,
                    targetPort = port,
                    payloadTemplate = payloadTemplate,
                    sni = sni.trim(),
                    proxyHost = proxyHost.trim(),
                    proxyPort = proxyPort,
                    emitUiLogs = emitUiLogs
                )
            }
        }

        if (useCustomFactory) {
            client.connect("127.0.0.1", port)
        } else {
            client.connect(cleanHost, port)
        }

        client.authPassword(cleanUser, pass)
        return if (client.isAuthenticated) client else null
    }

    fun startSshTunnel(
        protocolMode: String = "SSH",
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
        val mode = protocolMode.uppercase()

        if (cleanHost.isEmpty() || cleanUser.isEmpty() || pass.isEmpty()) {
            VpnStateBridge.addLog("Connection failed: Missing configuration details.")
            return false
        }

        return try {
            VpnStateBridge.updateState("CONNECTING", "Connecting...")
            VpnStateBridge.addLog("Connecting...")
            Log.d(TAG, "Connecting [$mode] -> $cleanHost:$port")

            val primaryClient = createConnectedSshClient(
                mode = mode,
                cleanHost = cleanHost,
                port = port,
                cleanUser = cleanUser,
                pass = pass,
                payloadTemplate = payloadTemplate,
                sni = sni,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                isSlowDns = isSlowDns,
                emitUiLogs = true
            )

            if (primaryClient != null) {
                sshPool.add(primaryClient)
                isRunning = true

                startLocalSocks5Server("127.0.0.1", localPort)

                val extraSessions = when {
                    isSlowDns && VpnStateBridge.slowDnsBoostEnabled.value -> 5
                    !isSlowDns -> 3
                    else -> 0
                }

                if (extraSessions > 0) {
                    for (i in 1..extraSessions) {
                        ioThreadPool.execute {
                            try {
                                if (!isRunning) return@execute
                                val workerClient = createConnectedSshClient(
                                    mode = mode,
                                    cleanHost = cleanHost,
                                    port = port,
                                    cleanUser = cleanUser,
                                    pass = pass,
                                    payloadTemplate = payloadTemplate,
                                    sni = sni,
                                    proxyHost = proxyHost,
                                    proxyPort = proxyPort,
                                    isSlowDns = isSlowDns,
                                    emitUiLogs = false
                                )
                                if (workerClient != null && isRunning) {
                                    sshPool.add(workerClient)
                                    Log.d(TAG, "Parallel SSH Turbo session #${sshPool.size} online")
                                } else {
                                    workerClient?.close()
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                true
            } else {
                VpnStateBridge.addLog("Authentication failed.")
                stopSshTunnel()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SSH Error: ${t.message}", t)
            val msg = t.message ?: ""
            if (msg.contains("Exhausted available authentication", ignoreCase = true) ||
                msg.contains("Auth fail", ignoreCase = true) ||
                msg.contains("UserAuthException", ignoreCase = true)
            ) {
                VpnStateBridge.addLog("Authentication failed: Invalid username or password.")
            } else {
                VpnStateBridge.addLog("Connection failed.")
            }
            stopSshTunnel()
            false
        }
    }

    private fun createCustomTunnelSocketFactory(
        mode: String,
        targetHost: String,
        targetPort: Int,
        payloadTemplate: String,
        sni: String,
        proxyHost: String,
        proxyPort: Int,
        emitUiLogs: Boolean
    ): SocketFactory {
        return object : SocketFactory() {
            override fun createSocket(): Socket = openTunneledSocket()
            override fun createSocket(host: String?, port: Int): Socket = openTunneledSocket()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = openTunneledSocket()
            override fun createSocket(host: InetAddress?, port: Int): Socket = openTunneledSocket()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = openTunneledSocket()

            private fun openSslSocket(tcpHost: String, tcpPort: Int, sniHost: String, forceHttp1Alpn: Boolean): SSLSocket {
                val baseSocket = Socket()
                baseSocket.setPerformancePreferences(0, 1, 2)
                baseSocket.tcpNoDelay = true
                baseSocket.keepAlive = true
                baseSocket.sendBufferSize = 524288
                baseSocket.receiveBufferSize = 524288
                baseSocket.connect(InetSocketAddress(tcpHost, tcpPort), 15000)

                val sslFactory = createTrustAllSslSocketFactory()
                val sslSock = sslFactory.createSocket(baseSocket, sniHost, tcpPort, true) as SSLSocket
                val params = sslSock.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                if (forceHttp1Alpn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    params.applicationProtocols = arrayOf("http/1.1")
                }
                sslSock.sslParameters = params
                sslSock.startHandshake()
                return sslSock
            }

            private fun openTunneledSocket(): Socket {
                val useProxy = mode == "SSH+PAYLOAD" && proxyHost.isNotEmpty()
                val tcpHost = if (useProxy) proxyHost else targetHost
                val tcpPort = if (useProxy) proxyPort else targetPort
                val sniHost = sni.ifEmpty { targetHost }

                if (mode == "SSH+SSL") {
                    val sslSock = openSslSocket(tcpHost, tcpPort, sniHost, forceHttp1Alpn = false)
                    val pushbackIn = PushbackInputStream(sslSock.getInputStream(), 32768)
                    val prevTimeout = sslSock.soTimeout
                    sslSock.soTimeout = 1200
                    try {
                        val peek = ByteArray(16)
                        val n = pushbackIn.read(peek)
                        if (n > 0) {
                            val str = String(peek, 0, n, Charsets.ISO_8859_1)
                            pushbackIn.unread(peek, 0, n)
                            if (str.startsWith("SSH-")) {
                                if (emitUiLogs) VpnStateBridge.addLog("HTTP/1.1 200 OK")
                                sslSock.soTimeout = prevTimeout
                                return PushbackWrappedSocket(sslSock, pushbackIn)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Throwable) {}

                    try {
                        sslSock.soTimeout = 10000
                        val autoWsPayload =
                            "GET / HTTP/1.1\r\nHost: $targetHost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
                        val out = sslSock.getOutputStream()
                        out.write(autoWsPayload.toByteArray(Charsets.UTF_8))
                        out.flush()
                        consumeHttpUpgradeOrPreserveSshBanner(pushbackIn, emitUiLogs)
                        sslSock.soTimeout = prevTimeout
                        return PushbackWrappedSocket(sslSock, pushbackIn)
                    } catch (_: Throwable) {
                        try { sslSock.close() } catch (_: Throwable) {}
                        val fallbackSsl = openSslSocket(tcpHost, tcpPort, sniHost, forceHttp1Alpn = true)
                        val fbPushback = PushbackInputStream(fallbackSsl.getInputStream(), 32768)
                        val autoWsPayload =
                            "GET / HTTP/1.1\r\nHost: $targetHost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
                        fallbackSsl.getOutputStream().apply {
                            write(autoWsPayload.toByteArray(Charsets.UTF_8))
                            flush()
                        }
                        fallbackSsl.soTimeout = 10000
                        try {
                            consumeHttpUpgradeOrPreserveSshBanner(fbPushback, emitUiLogs)
                        } finally {
                            fallbackSsl.soTimeout = 0
                        }
                        return PushbackWrappedSocket(fallbackSsl, fbPushback)
                    }
                }

                val shouldUseTls = when (mode) {
                    "SSH+WS+SNI+PAYLOAD" -> (targetPort in tlsPorts) || (targetPort != 80 && targetPort != 8080 && sni.isNotEmpty())
                    "SSH+PAYLOAD" -> (tcpPort in tlsPorts) || (targetPort in tlsPorts)
                    else -> false
                }

                val activeSocket: Socket = if (shouldUseTls) {
                    openSslSocket(tcpHost, tcpPort, sniHost, forceHttp1Alpn = false)
                } else {
                    Socket().apply {
                        setPerformancePreferences(0, 1, 2)
                        tcpNoDelay = true
                        keepAlive = true
                        sendBufferSize = 524288
                        receiveBufferSize = 524288
                        connect(InetSocketAddress(tcpHost, tcpPort), 15000)
                    }
                }

                val pushbackIn = PushbackInputStream(activeSocket.getInputStream(), 32768)

                if (shouldUseTls) {
                    val prevTimeout = activeSocket.soTimeout
                    activeSocket.soTimeout = 400
                    try {
                        val peek = ByteArray(16)
                        val n = pushbackIn.read(peek)
                        if (n > 0) {
                            val str = String(peek, 0, n, Charsets.ISO_8859_1)
                            pushbackIn.unread(peek, 0, n)
                            if (str.startsWith("SSH-")) {
                                if (emitUiLogs) VpnStateBridge.addLog("HTTP/1.1 200 OK")
                                activeSocket.soTimeout = prevTimeout
                                return PushbackWrappedSocket(activeSocket, pushbackIn)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                    } finally {
                        activeSocket.soTimeout = prevTimeout
                    }
                }

                if (payloadTemplate.isNotBlank()) {
                    val normalizedTemplate = if (mode == "SSH+WS+SNI+PAYLOAD") {
                        payloadTemplate.replace("Host: [sni]", "Host: [host]", ignoreCase = true)
                    } else {
                        payloadTemplate
                    }

                    val formatted = PayloadGenerator.generatePayload(
                        template = normalizedTemplate,
                        targetHost = targetHost,
                        targetPort = targetPort,
                        sni = sniHost
                    )

                    val out: OutputStream = activeSocket.getOutputStream()
                    val chunks = formatted.split(Regex("\\[(instant_)?split\\]", RegexOption.IGNORE_CASE))
                    for ((index, chunk) in chunks.withIndex()) {
                        if (chunk.isNotEmpty()) {
                            out.write(chunk.toByteArray(Charsets.UTF_8))
                            out.flush()
                            if (index < chunks.size - 1) {
                                Thread.sleep(40)
                            }
                        }
                    }

                    val previousTimeout = activeSocket.soTimeout
                    activeSocket.soTimeout = 12000
                    try {
                        consumeHttpUpgradeOrPreserveSshBanner(pushbackIn, emitUiLogs)
                    } finally {
                        activeSocket.soTimeout = previousTimeout
                    }
                }

                return PushbackWrappedSocket(activeSocket, pushbackIn)
            }
        }
    }

    private fun consumeHttpUpgradeOrPreserveSshBanner(
        pushbackIn: PushbackInputStream,
        emitUiLogs: Boolean
    ) {
        val headerBuf = ByteArray(4096)
        var len = 0

        while (len < headerBuf.size) {
            val b = pushbackIn.read()
            if (b == -1) break
            headerBuf[len++] = b.toByte()

            val currentStr = String(headerBuf, 0, len, Charsets.ISO_8859_1)

            val sshIdx = currentStr.indexOf("SSH-2.0-")
            if (sshIdx >= 0 && currentStr.indexOf('\n', sshIdx) > sshIdx) {
                val sshBannerBytes = currentStr.substring(sshIdx).toByteArray(Charsets.ISO_8859_1)
                pushbackIn.unread(sshBannerBytes, 0, sshBannerBytes.size)
                if (emitUiLogs) {
                    VpnStateBridge.addLog("HTTP/1.1 200 OK")
                    VpnStateBridge.addLog("Switching Protocols...")
                }
                return
            }

            if (currentStr.endsWith("\r\n\r\n")) {
                val firstLine = currentStr.lineSequence().firstOrNull()?.trim() ?: ""
                if (emitUiLogs) {
                    when {
                        firstLine.contains("101") -> {
                            VpnStateBridge.addLog("HTTP/1.1 200 OK")
                            VpnStateBridge.addLog("HTTP/1.1 101 Switching Protocols")
                        }
                        firstLine.isNotEmpty() -> VpnStateBridge.addLog(firstLine)
                    }
                }

                if (firstLine.contains("200") && !firstLine.contains("101")) {
                    val peekBuf = ByteArray(4)
                    val readCount = pushbackIn.read(peekBuf, 0, 4)
                    if (readCount > 0) {
                        val peekStr = String(peekBuf, 0, readCount, Charsets.ISO_8859_1)
                        pushbackIn.unread(peekBuf, 0, readCount)
                        if (peekStr.startsWith("HTTP", ignoreCase = true)) {
                            len = 0
                            continue
                        }
                    }
                }
                return
            }
        }
    }

    private class PushbackWrappedSocket(
        private val delegate: Socket,
        private val customIn: InputStream
    ) : Socket() {
        override fun connect(endpoint: SocketAddress?) {}
        override fun connect(endpoint: SocketAddress?, timeout: Int) {}
        override fun bind(bindpoint: SocketAddress?) {}
        override fun getInputStream(): InputStream = customIn
        override fun getOutputStream(): OutputStream = delegate.getOutputStream()
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isClosed(): Boolean = delegate.isClosed
        override fun isBound(): Boolean = true
        override fun close() = delegate.close()
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setTcpNoDelay(on: Boolean) {
            try { delegate.tcpNoDelay = on } catch (_: Throwable) {}
        }
        override fun getTcpNoDelay(): Boolean = try { delegate.tcpNoDelay } catch (_: Throwable) { true }
        override fun setKeepAlive(on: Boolean) {
            try { delegate.keepAlive = on } catch (_: Throwable) {}
        }
        override fun getKeepAlive(): Boolean = try { delegate.keepAlive } catch (_: Throwable) { true }
        override fun setReceiveBufferSize(size: Int) {
            try { delegate.receiveBufferSize = size } catch (_: Throwable) {}
        }
        override fun getReceiveBufferSize(): Int = try { delegate.receiveBufferSize } catch (_: Throwable) { 524288 }
        override fun setSendBufferSize(size: Int) {
            try { delegate.sendBufferSize = size } catch (_: Throwable) {}
        }
        override fun getSendBufferSize(): Int = try { delegate.sendBufferSize } catch (_: Throwable) { 524288 }
        override fun getInetAddress(): InetAddress? = delegate.inetAddress ?: InetAddress.getLoopbackAddress()
        override fun getLocalAddress(): InetAddress = delegate.localAddress ?: InetAddress.getLoopbackAddress()
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
        override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    }

    private fun pickSshClient(): SSHClient? {
        val healthy = sshPool.filter { it.isConnected && it.isAuthenticated }
        if (healthy.isEmpty()) return sshPool.firstOrNull()
        val idx = (poolSelector.getAndIncrement() and Int.MAX_VALUE) % healthy.size
        return healthy[idx]
    }

    private fun startLocalSocks5Server(bindHost: String, localPort: Int) {
        thread(start = true, name = "SSH-SOCKS5-Server") {
            try {
                localServer = ServerSocket()
                localServer?.reuseAddress = true
                localServer?.bind(InetSocketAddress(bindHost, localPort), 512)
                while (isRunning) {
                    val clientSocket = localServer?.accept() ?: break
                    ioThreadPool.execute {
                        handleSocks5Client(clientSocket)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun openSshForwardSocket(targetHost: String, targetPort: Int): Pair<ServerSocket, Socket> {
        val bridgeServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val bridgePort = bridgeServer.localPort
        activeBridges.add(bridgeServer)

        val selectedClient = pickSshClient()
        val params = Parameters("127.0.0.1", bridgePort, targetHost, targetPort)
        ioThreadPool.execute {
            try {
                selectedClient?.newLocalPortForwarder(params, bridgeServer)?.listen()
            } catch (_: Throwable) {}
        }

        val bridgeSocket = Socket()
        bridgeSocket.tcpNoDelay = true
        bridgeSocket.sendBufferSize = 262144
        bridgeSocket.receiveBufferSize = 262144
        bridgeSocket.connect(InetSocketAddress("127.0.0.1", bridgePort), 10000)
        return Pair(bridgeServer, bridgeSocket)
    }

    private fun handleSocks5Client(client: Socket) {
        var bridgeServer: ServerSocket? = null
        var bridgeSocket: Socket? = null
        try {
            client.tcpNoDelay = true
            client.sendBufferSize = 262144
            client.receiveBufferSize = 262144
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
            if (reqVer != 5) {
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

            if (cmd == 3) {
                handleSocks5UdpAssociate(client, ins, outs)
                return
            }

            if (cmd != 1) {
                client.close()
                return
            }

            val pair = openSshForwardSocket(targetHost, targetPort)
            bridgeServer = pair.first
            bridgeSocket = pair.second

            outs.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outs.flush()

            val sshIn = bridgeSocket.getInputStream()
            val sshOut = bridgeSocket.getOutputStream()

            val upstreamFuture = ioThreadPool.submit {
                try {
                    val buf = ByteArray(65536)
                    while (isRunning) {
                        val n = ins.read(buf)
                        if (n == -1) break
                        sshOut.write(buf, 0, n)
                        if (ins.available() == 0) {
                            sshOut.flush()
                        }
                    }
                    sshOut.flush()
                } catch (_: Throwable) {
                } finally {
                    try { bridgeSocket.close() } catch (_: Throwable) {}
                }
            }

            try {
                val buf = ByteArray(65536)
                while (isRunning) {
                    val n = sshIn.read(buf)
                    if (n == -1) break
                    outs.write(buf, 0, n)
                    if (sshIn.available() == 0) {
                        outs.flush()
                    }
                }
                outs.flush()
            } catch (_: Throwable) {}
            upstreamFuture.cancel(true)
        } catch (_: Throwable) {
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { bridgeSocket?.close() } catch (_: Throwable) {}
            try {
                bridgeServer?.close()
                activeBridges.remove(bridgeServer)
            } catch (_: Throwable) {}
        }
    }

    private fun handleSocks5UdpAssociate(client: Socket, ins: InputStream, outs: OutputStream) {
        var udpRelay: DatagramSocket? = null
        try {
            udpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            activeUdpSockets.add(udpRelay)
            val localUdpPort = udpRelay.localPort

            outs.write(
                byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((localUdpPort shr 8) and 0xFF).toByte(),
                    (localUdpPort and 0xFF).toByte()
                )
            )
            outs.flush()

            val relayRef = udpRelay
            ioThreadPool.execute {
                val buf = ByteArray(4096)
                while (isRunning && !relayRef.isClosed) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        relayRef.receive(pkt)
                        val data = pkt.data.copyOfRange(0, pkt.length)
                        val clientAddr = pkt.socketAddress
                        if (data.size <= 10) continue

                        val atyp = data[3].toInt() and 0xFF
                        var offset = 4
                        val dstHost = when (atyp) {
                            1 -> {
                                val ip = InetAddress.getByAddress(data.copyOfRange(4, 8)).hostAddress ?: "8.8.8.8"
                                offset = 8
                                ip
                            }
                            3 -> {
                                val dLen = data[4].toInt() and 0xFF
                                val dom = String(data, 5, dLen)
                                offset = 5 + dLen
                                dom
                            }
                            4 -> {
                                val ip = InetAddress.getByAddress(data.copyOfRange(4, 20)).hostAddress ?: "8.8.8.8"
                                offset = 20
                                ip
                            }
                            else -> continue
                        }
                        val dstPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                        offset += 2
                        val socksUdpHeader = data.copyOfRange(0, offset)
                        val udpPayload = data.copyOfRange(offset, data.size)

                        ioThreadPool.execute {
                            var bServer: ServerSocket? = null
                            var bSocket: Socket? = null
                            try {
                                val dnsTarget = if (dstPort == 53) dstHost else "8.8.8.8"
                                val pair = openSshForwardSocket(dnsTarget, 53)
                                bServer = pair.first
                                bSocket = pair.second
                                bSocket.soTimeout = 5000

                                val dos = DataOutputStream(bSocket.getOutputStream())
                                val dis = DataInputStream(bSocket.getInputStream())
                                dos.writeShort(udpPayload.size)
                                dos.write(udpPayload)
                                dos.flush()

                                val respLen = dis.readUnsignedShort()
                                val respPayload = ByteArray(respLen)
                                dis.readFully(respPayload)

                                val fullResp = ByteArray(socksUdpHeader.size + respPayload.size)
                                System.arraycopy(socksUdpHeader, 0, fullResp, 0, socksUdpHeader.size)
                                System.arraycopy(respPayload, 0, fullResp, socksUdpHeader.size, respPayload.size)

                                relayRef.send(DatagramPacket(fullResp, fullResp.size, clientAddr))
                            } catch (_: Throwable) {
                            } finally {
                                try { bSocket?.close() } catch (_: Throwable) {}
                                try {
                                    bServer?.close()
                                    activeBridges.remove(bServer)
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }

            while (isRunning && ins.read() != -1) {}
        } catch (_: Throwable) {
        } finally {
            try {
                udpRelay?.close()
                activeUdpSockets.remove(udpRelay)
            } catch (_: Throwable) {}
            try { client.close() } catch (_: Throwable) {}
        }
    }

    fun stopSshTunnel() {
        isRunning = false
        activeUdpSockets.forEach {
            try { it.close() } catch (_: Throwable) {}
        }
        activeUdpSockets.clear()

        activeBridges.forEach {
            try { it.close() } catch (_: Throwable) {}
        }
        activeBridges.clear()

        try { localServer?.close() } catch (_: Throwable) {}
        localServer = null

        val clientsToClose = ArrayList(sshPool)
        sshPool.clear()
        ioThreadPool.execute {
            clientsToClose.forEach { client ->
                try {
                    client.disconnect()
                    client.close()
                } catch (_: Throwable) {}
            }
        }
    }
}
