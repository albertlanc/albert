package com.albert.smartvpn.engine

import android.os.Build
import android.util.Base64
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
    private val ioThreadPool = Executors.newFixedThreadPool(24)
    private val tlsPorts = setOf(443, 8443, 2053, 2083, 2087, 2096)

    private fun socketBufSize(): Int =
        if (VpnStateBridge.turboSpeedEnabled.value) 524288 else 262144

    private fun buildAndroidSafeSshConfig(isSlowDns: Boolean): DefaultConfig {
        try {
            Security.removeProvider("BC")
            val bcClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            val provider = bcClass.getDeclaredConstructor().newInstance() as Provider
            Security.insertProviderAt(provider, 1)
            SecurityUtils.setRegisterBouncyCastle(false)
        } catch (_: Throwable) {}

        val config = DefaultConfig()
        val x25519Works = try {
            KeyPairGenerator.getInstance("X25519", "BC")
            true
        } catch (_: Throwable) {
            false
        }
        if (!x25519Works) {
            config.keyExchangeFactories = config.keyExchangeFactories.filterNot {
                it.name.contains("25519", true) || it.name.contains("448", true)
            }
            config.keyAlgorithms = config.keyAlgorithms.filterNot {
                it.name.contains("25519", true) || it.name.contains("448", true)
            }
        }

        config.cipherFactories = config.cipherFactories.sortedBy {
            when {
                it.name.contains("aes128-ctr", true) -> 0
                it.name.contains("aes128-gcm", true) -> 1
                it.name.contains("aes256-ctr", true) -> 2
                else -> 10
            }
        }

        if (isSlowDns) {
            val fastKex = config.keyExchangeFactories.filter {
                it.name.contains("25519", true) ||
                    it.name.contains("nistp256", true) ||
                    it.name.contains("group14", true)
            }
            if (fastKex.isNotEmpty()) config.keyExchangeFactories = fastKex

            val fastMacs = config.macFactories.filter {
                it.name.equals("hmac-sha2-256", true) || it.name.equals("hmac-sha1", true)
            }
            if (fastMacs.isNotEmpty()) config.macFactories = fastMacs
        }
        return config
    }

    private fun createTrustAllSslFactory(tlsVersion: String): javax.net.ssl.SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val proto = when (tlsVersion.uppercase()) {
            "TLSV1.3" -> "TLSv1.3"
            "TLSV1.2" -> "TLSv1.2"
            else -> "TLS"
        }
        return try {
            SSLContext.getInstance(proto).apply { init(null, trustAll, SecureRandom()) }.socketFactory
        } catch (_: Throwable) {
            SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }.socketFactory
        }
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
        proxyAuth: Boolean,
        proxyUser: String,
        proxyPass: String,
        tlsVersion: String,
        isSlowDns: Boolean,
        emitUiLogs: Boolean
    ): SSHClient? {
        val sshConfig = buildAndroidSafeSshConfig(isSlowDns)
        val isPlainDirect = isSlowDns || mode == "SSH" || mode == "SSH-DIRECT"

        val client = SSHClient(sshConfig).apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = if (isSlowDns) 45000 else 15000
            timeout = 0
            if (VpnStateBridge.sshCompression.value) {
                try { useCompression() } catch (_: Throwable) {}
            }
            connection.windowSize = if (VpnStateBridge.turboSpeedEnabled.value) 8388608L else 4194304L
            connection.maxPacketSize = 32768
            connection.keepAlive.keepAliveInterval = VpnStateBridge.pingIntervalSec.value.coerceIn(10, 120)

            if (!isPlainDirect) {
                socketFactory = createCustomTunnelSocketFactory(
                    mode = mode,
                    targetHost = cleanHost,
                    targetPort = port,
                    payloadTemplate = payloadTemplate,
                    sni = sni.trim(),
                    proxyHost = proxyHost.trim(),
                    proxyPort = proxyPort,
                    proxyAuth = proxyAuth,
                    proxyUser = proxyUser,
                    proxyPass = proxyPass,
                    tlsVersion = tlsVersion,
                    emitUiLogs = emitUiLogs
                )
            }
        }

        if (!isPlainDirect) client.connect("127.0.0.1", port) else client.connect(cleanHost, port)
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
        proxyAuth: Boolean = false,
        proxyUser: String = "",
        proxyPass: String = "",
        tlsVersion: String = "DEFAULT",
        isSlowDns: Boolean = false
    ): Boolean {
        if (isRunning) stopSshTunnel()

        val cleanHost = host.trim()
        val cleanUser = user.trim()
        val mode = protocolMode.uppercase()

        if (cleanHost.isEmpty() || cleanUser.isEmpty() || pass.isEmpty()) {
            VpnStateBridge.addLog("Connection failed: Missing SSH host, username, or password.")
            return false
        }

        return try {
            VpnStateBridge.updateState("CONNECTING", "CONNECTING...")
            VpnStateBridge.addLog("Connecting ($mode)...")

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
                proxyAuth = proxyAuth,
                proxyUser = proxyUser,
                proxyPass = proxyPass,
                tlsVersion = tlsVersion,
                isSlowDns = isSlowDns,
                emitUiLogs = true
            )

            if (primaryClient != null) {
                sshPool.add(primaryClient)
                isRunning = true
                startLocalSocks5Server("127.0.0.1", localPort)

                if (VpnStateBridge.turboSpeedEnabled.value) {
                    ioThreadPool.execute {
                        try {
                            if (!isRunning) return@execute
                            val secondary = createConnectedSshClient(
                                mode = mode,
                                cleanHost = cleanHost,
                                port = port,
                                cleanUser = cleanUser,
                                pass = pass,
                                payloadTemplate = payloadTemplate,
                                sni = sni,
                                proxyHost = proxyHost,
                                proxyPort = proxyPort,
                                proxyAuth = proxyAuth,
                                proxyUser = proxyUser,
                                proxyPass = proxyPass,
                                tlsVersion = tlsVersion,
                                isSlowDns = isSlowDns,
                                emitUiLogs = false
                            )
                            if (secondary != null && isRunning) sshPool.add(secondary) else secondary?.close()
                        } catch (_: Throwable) {}
                    }
                }
                true
            } else {
                VpnStateBridge.addLog("Authentication failed.")
                stopSshTunnel()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SSH Error: ${t.message}")
            val msg = t.message ?: ""
            if (msg.contains("Exhausted available authentication", true) ||
                msg.contains("Auth fail", true) ||
                msg.contains("UserAuthException", true)
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
        proxyAuth: Boolean,
        proxyUser: String,
        proxyPass: String,
        tlsVersion: String,
        emitUiLogs: Boolean
    ): SocketFactory {
        return object : SocketFactory() {
            override fun createSocket(): Socket = openTunneledSocket()
            override fun createSocket(h: String?, p: Int): Socket = openTunneledSocket()
            override fun createSocket(h: String?, p: Int, lh: InetAddress?, lp: Int): Socket = openTunneledSocket()
            override fun createSocket(h: InetAddress?, p: Int): Socket = openTunneledSocket()
            override fun createSocket(a: InetAddress?, p: Int, la: InetAddress?, lp: Int): Socket = openTunneledSocket()

            private fun openSslSocket(tcpHost: String, tcpPort: Int, sniHost: String, forceHttp1: Boolean): SSLSocket {
                val raw = Socket().apply {
                    tcpNoDelay = VpnStateBridge.tcpNoDelay.value
                    keepAlive = true
                    sendBufferSize = socketBufSize()
                    receiveBufferSize = socketBufSize()
                    connect(InetSocketAddress(tcpHost, tcpPort), 15000)
                }
                return (createTrustAllSslFactory(tlsVersion).createSocket(raw, sniHost, tcpPort, true) as SSLSocket).apply {
                    val p = sslParameters
                    p.serverNames = listOf(SNIHostName(sniHost))
                    if (forceHttp1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        p.applicationProtocols = arrayOf("http/1.1")
                    }
                    sslParameters = p
                    startHandshake()
                }
            }

            private fun openTunneledSocket(): Socket {
                val hasProxy = (mode.contains("PROXY") || mode == "SSH+PAYLOAD") && proxyHost.isNotEmpty()
                val hasTls = mode.contains("TLS") || mode == "SSH+SSL" || mode == "SSH+WS+SNI+PAYLOAD"
                val hasPayload = mode.contains("PAYLOAD")

                val tcpHost = if (hasProxy) proxyHost else targetHost
                val tcpPort = if (hasProxy) proxyPort else targetPort
                val sniHost = sni.ifEmpty { targetHost }

                if ((mode == "SSH-TLS" || mode == "SSH+SSL") && !hasPayload && !hasProxy) {
                    val sslSock = openSslSocket(tcpHost, tcpPort, sniHost, false)
                    val pushbackIn = PushbackInputStream(sslSock.getInputStream(), 32768)
                    val prevTimeout = sslSock.soTimeout
                    sslSock.soTimeout = 1000
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

                    return try {
                        sslSock.soTimeout = 10000
                        val wsReq = "GET / HTTP/1.1\r\nHost: $targetHost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
                        sslSock.getOutputStream().apply {
                            write(wsReq.toByteArray(Charsets.UTF_8))
                            flush()
                        }
                        consumeHttpUpgrade(pushbackIn, emitUiLogs)
                        sslSock.soTimeout = prevTimeout
                        PushbackWrappedSocket(sslSock, pushbackIn)
                    } catch (_: Throwable) {
                        try { sslSock.close() } catch (_: Throwable) {}
                        val fbSsl = openSslSocket(tcpHost, tcpPort, sniHost, true)
                        val fbIn = PushbackInputStream(fbSsl.getInputStream(), 32768)
                        val wsReq = "GET / HTTP/1.1\r\nHost: $targetHost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
                        fbSsl.getOutputStream().apply {
                            write(wsReq.toByteArray(Charsets.UTF_8))
                            flush()
                        }
                        fbSsl.soTimeout = 10000
                        try { consumeHttpUpgrade(fbIn, emitUiLogs) } finally { fbSsl.soTimeout = 0 }
                        PushbackWrappedSocket(fbSsl, fbIn)
                    }
                }

                val shouldUseTls = hasTls || (targetPort in tlsPorts)
                val activeSocket: Socket = if (shouldUseTls) {
                    openSslSocket(tcpHost, tcpPort, sniHost, payloadTemplate.isNotBlank())
                } else {
                    Socket().apply {
                        tcpNoDelay = VpnStateBridge.tcpNoDelay.value
                        keepAlive = true
                        sendBufferSize = socketBufSize()
                        receiveBufferSize = socketBufSize()
                        connect(InetSocketAddress(tcpHost, tcpPort), 15000)
                    }
                }

                val pushbackIn = PushbackInputStream(activeSocket.getInputStream(), 32768)

                val effectivePayload = when {
                    payloadTemplate.isNotBlank() -> payloadTemplate
                    hasProxy -> "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf][crlf]"
                    else -> ""
                }

                if (effectivePayload.isNotBlank()) {
                    val norm = effectivePayload.replace("Host: [sni]", "Host: [host]", true)
                    var formatted = PayloadGenerator.generatePayload(norm, targetHost, targetPort, sniHost)
                    if (proxyAuth && proxyUser.isNotBlank()) {
                        val b64Creds = Base64.encodeToString("$proxyUser:$proxyPass".toByteArray(), Base64.NO_WRAP)
                        formatted = formatted.replaceFirst("\r\n\r\n", "\r\nProxy-Authorization: Basic $b64Creds\r\n\r\n")
                    }
                    val out = activeSocket.getOutputStream()
                    val chunks = formatted.split(Regex("\\[(instant_|delay_)?split\\]", RegexOption.IGNORE_CASE))
                    for ((idx, chunk) in chunks.withIndex()) {
                        if (chunk.isNotEmpty()) {
                            out.write(chunk.toByteArray(Charsets.UTF_8))
                            out.flush()
                            if (idx < chunks.size - 1) Thread.sleep(25)
                        }
                    }
                    val prevTimeout = activeSocket.soTimeout
                    activeSocket.soTimeout = 10000
                    try { consumeHttpUpgrade(pushbackIn, emitUiLogs) } finally { activeSocket.soTimeout = prevTimeout }
                }
                return PushbackWrappedSocket(activeSocket, pushbackIn)
            }
        }
    }

    private fun consumeHttpUpgrade(pushbackIn: PushbackInputStream, emitUiLogs: Boolean) {
        val headerBuf = ByteArray(4096)
        var len = 0
        while (len < headerBuf.size) {
            val b = pushbackIn.read()
            if (b == -1) break
            headerBuf[len++] = b.toByte()
            val currentStr = String(headerBuf, 0, len, Charsets.ISO_8859_1)

            val sshIdx = currentStr.indexOf("SSH-2.0-")
            if (sshIdx >= 0 && currentStr.indexOf('\n', sshIdx) > sshIdx) {
                val bannerBytes = currentStr.substring(sshIdx).toByteArray(Charsets.ISO_8859_1)
                pushbackIn.unread(bannerBytes, 0, bannerBytes.size)
                if (emitUiLogs) {
                    VpnStateBridge.addLog("HTTP/1.1 200 OK")
                    VpnStateBridge.addLog("Switching Protocols...")
                }
                return
            }

            if (currentStr.endsWith("\r\n\r\n")) {
                val firstLine = currentStr.lineSequence().firstOrNull()?.trim() ?: ""
                if (emitUiLogs) {
                    if (firstLine.contains("101")) {
                        VpnStateBridge.addLog("HTTP/1.1 200 OK")
                        VpnStateBridge.addLog("HTTP/1.1 101 Switching Protocols")
                    } else if (firstLine.isNotEmpty()) {
                        VpnStateBridge.addLog(firstLine)
                    }
                }
                if (firstLine.contains("200") && !firstLine.contains("101")) {
                    val peekBuf = ByteArray(4)
                    val r = pushbackIn.read(peekBuf, 0, 4)
                    if (r > 0) {
                        val peekStr = String(peekBuf, 0, r, Charsets.ISO_8859_1)
                        pushbackIn.unread(peekBuf, 0, r)
                        if (peekStr.startsWith("HTTP", true)) {
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
        override fun setTcpNoDelay(on: Boolean) { try { delegate.tcpNoDelay = on } catch (_: Throwable) {} }
        override fun setKeepAlive(on: Boolean) { try { delegate.keepAlive = on } catch (_: Throwable) {} }
        override fun getInetAddress(): InetAddress? = delegate.inetAddress ?: InetAddress.getLoopbackAddress()
        override fun getLocalAddress(): InetAddress = delegate.localAddress ?: InetAddress.getLoopbackAddress()
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
    }

    private fun pickSshClient(): SSHClient? {
        val healthy = sshPool.filter { it.isConnected && it.isAuthenticated }
        if (healthy.isEmpty()) return sshPool.firstOrNull()
        return healthy[(poolSelector.getAndIncrement() and Int.MAX_VALUE) % healthy.size]
    }

    private fun startLocalSocks5Server(bindHost: String, localPort: Int) {
        thread(start = true, name = "SSH-SOCKS5-Server") {
            try {
                localServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindHost, localPort), 256)
                }
                while (isRunning) {
                    val client = localServer?.accept() ?: break
                    ioThreadPool.execute { handleSocks5Client(client) }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun openSshForwardSocket(targetHost: String, targetPort: Int): Pair<ServerSocket, Socket> {
        val bridgeServer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val bridgePort = bridgeServer.localPort
        activeBridges.add(bridgeServer)

        val selected = pickSshClient()
        val params = Parameters("127.0.0.1", bridgePort, targetHost, targetPort)
        ioThreadPool.execute {
            try { selected?.newLocalPortForwarder(params, bridgeServer)?.listen() } catch (_: Throwable) {}
        }

        val bridgeSocket = Socket().apply {
            tcpNoDelay = VpnStateBridge.tcpNoDelay.value
            sendBufferSize = socketBufSize()
            receiveBufferSize = socketBufSize()
            connect(InetSocketAddress("127.0.0.1", bridgePort), 8000)
        }
        return Pair(bridgeServer, bridgeSocket)
    }

    private fun handleSocks5Client(client: Socket) {
        var bridgeServer: ServerSocket? = null
        var bridgeSocket: Socket? = null
        try {
            client.tcpNoDelay = VpnStateBridge.tcpNoDelay.value
            client.sendBufferSize = socketBufSize()
            client.receiveBufferSize = socketBufSize()
            val ins = BufferedInputStream(client.getInputStream(), 65536)
            val outs = BufferedOutputStream(client.getOutputStream(), 65536)

            if (ins.read() != 5) { client.close(); return }
            val nMethods = ins.read()
            if (nMethods > 0) ins.read(ByteArray(nMethods))
            outs.write(byteArrayOf(0x05, 0x00))
            outs.flush()

            val reqVer = ins.read()
            val cmd = ins.read()
            ins.read()
            val atyp = ins.read()
            if (reqVer != 5) { client.close(); return }

            val targetHost = when (atyp) {
                1 -> { val a = ByteArray(4); ins.read(a); InetAddress.getByAddress(a).hostAddress ?: "" }
                3 -> { val l = ins.read(); val d = ByteArray(l); ins.read(d); String(d) }
                4 -> { val a = ByteArray(16); ins.read(a); InetAddress.getByAddress(a).hostAddress ?: "" }
                else -> ""
            }
            val targetPort = (ins.read() shl 8) or ins.read()

            if (cmd == 3) {
                handleSocks5UdpAssociate(client, ins, outs)
                return
            }
            if (cmd != 1) { client.close(); return }

            val pair = openSshForwardSocket(targetHost, targetPort)
            bridgeServer = pair.first
            bridgeSocket = pair.second

            outs.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outs.flush()

            val sshIn = BufferedInputStream(bridgeSocket.getInputStream(), 65536)
            val sshOut = BufferedOutputStream(bridgeSocket.getOutputStream(), 65536)

            val upFuture = ioThreadPool.submit {
                try {
                    val buf = ByteArray(65536)
                    while (isRunning) {
                        val n = ins.read(buf)
                        if (n == -1) break
                        sshOut.write(buf, 0, n)
                        sshOut.flush()
                    }
                } catch (_: Throwable) {
                } finally {
                    try { bridgeSocket.close() } catch (_: Throwable) {}
                }
            }

            val buf = ByteArray(65536)
            while (isRunning) {
                val n = sshIn.read(buf)
                if (n == -1) break
                outs.write(buf, 0, n)
                outs.flush()
            }
            upFuture.cancel(true)
        } catch (_: Throwable) {
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { bridgeSocket?.close() } catch (_: Throwable) {}
            try { bridgeServer?.close(); activeBridges.remove(bridgeServer) } catch (_: Throwable) {}
        }
    }

    private fun handleSocks5UdpAssociate(client: Socket, ins: InputStream, outs: OutputStream) {
        var udpRelay: DatagramSocket? = null
        try {
            udpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            activeUdpSockets.add(udpRelay)
            val port = udpRelay.localPort
            outs.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, ((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte()))
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
                            1 -> { offset = 8; InetAddress.getByAddress(data.copyOfRange(4, 8)).hostAddress ?: "8.8.8.8" }
                            3 -> { val l = data[4].toInt() and 0xFF; offset = 5 + l; String(data, 5, l) }
                            4 -> { offset = 20; InetAddress.getByAddress(data.copyOfRange(4, 20)).hostAddress ?: "8.8.8.8" }
                            else -> continue
                        }
                        val dstPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                        offset += 2
                        val header = data.copyOfRange(0, offset)
                        val payload = data.copyOfRange(offset, data.size)

                        ioThreadPool.execute {
                            var bSrv: ServerSocket? = null
                            var bSock: Socket? = null
                            try {
                                val pair = openSshForwardSocket(if (dstPort == 53) dstHost else "8.8.8.8", 53)
                                bSrv = pair.first
                                bSock = pair.second
                                bSock.soTimeout = 5000
                                val dos = DataOutputStream(bSock.getOutputStream())
                                val dis = DataInputStream(bSock.getInputStream())
                                dos.writeShort(payload.size)
                                dos.write(payload)
                                dos.flush()
                                val rLen = dis.readUnsignedShort()
                                val rPay = ByteArray(rLen)
                                dis.readFully(rPay)
                                val resp = header + rPay
                                relayRef.send(DatagramPacket(resp, resp.size, clientAddr))
                            } catch (_: Throwable) {
                            } finally {
                                try { bSock?.close() } catch (_: Throwable) {}
                                try { bSrv?.close(); activeBridges.remove(bSrv) } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) { break }
                }
            }
            while (isRunning && ins.read() != -1) {}
        } catch (_: Throwable) {
        } finally {
            try { udpRelay?.close(); activeUdpSockets.remove(udpRelay) } catch (_: Throwable) {}
            try { client.close() } catch (_: Throwable) {}
        }
    }

    fun stopSshTunnel() {
        isRunning = false
        activeUdpSockets.forEach { try { it.close() } catch (_: Throwable) {} }
        activeUdpSockets.clear()
        activeBridges.forEach { try { it.close() } catch (_: Throwable) {} }
        activeBridges.clear()
        try { localServer?.close() } catch (_: Throwable) {}
        localServer = null
        val toClose = ArrayList(sshPool)
        sshPool.clear()
        ioThreadPool.execute {
            toClose.forEach { try { it.disconnect(); it.close() } catch (_: Throwable) {} }
        }
    }
}
// === END OF FILE 9 ===
