package com.albert.smartvpn.engine

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

object XrayCore {
    private const val TAG = "XrayCore"
    private val isRunning = AtomicBoolean(false)
    private var fallbackServer: ServerSocket? = null
    private var fallbackUdpSocket: DatagramSocket? = null
    private val ioPool = Executors.newCachedThreadPool()
    @Volatile private var appFilesDir: File? = null

    init {
        try {
            System.loadLibrary("gojni")
        } catch (_: Throwable) {}
    }

    fun initContext(context: Context) {
        appFilesDir = context.filesDir
    }

    fun verifyServerReachable(host: String, port: Int): Boolean {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return false
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(cleanHost, port), 6000)
            }
            true
        } catch (t: Throwable) {
            VpnStateBridge.addLog("Server unreachable ($cleanHost:$port): ${t.message}")
            false
        }
    }

    private fun isLocalPortOpen(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 350)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalPortOpen(port)) return true
            Thread.sleep(100)
        }
        return isLocalPortOpen(port)
    }

    private fun b64Encode(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun tryStartNativeLibXray(jsonConfig: String, socksPort: Int): Boolean {
        val dir = appFilesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        if (!dir.exists()) dir.mkdirs()
        val configFile = File(dir, "xray_active_config.json")
        configFile.writeText(jsonConfig)

        val candidateClasses = listOf(
            "libXray.LibXray",
            "go.libXray.LibXray",
            "com.xray.core.XrayCore"
        )

        val reqRunXray = """{"datDir":"${dir.absolutePath}","configPath":"${configFile.absolutePath}"}"""
        val reqRunFromJson = """{"datDir":"${dir.absolutePath}","configJSON":$jsonConfig}"""

        for (className in candidateClasses) {
            try {
                val clazz = Class.forName(className)

                for (methodName in listOf("runXray", "RunXray")) {
                    try {
                        val m = clazz.getMethod(methodName, String::class.java)
                        m.invoke(null, b64Encode(reqRunXray))
                        if (waitForLocalPort(socksPort, 1200)) {
                            VpnStateBridge.addLog("Native libXray core active on port $socksPort")
                            return true
                        }
                    } catch (_: Throwable) {}
                }

                for (methodName in listOf("runXrayFromJSON", "RunXrayFromJSON")) {
                    for (payload in listOf(b64Encode(reqRunFromJson), b64Encode(jsonConfig), jsonConfig)) {
                        try {
                            val m = clazz.getMethod(methodName, String::class.java)
                            m.invoke(null, payload)
                            if (waitForLocalPort(socksPort, 900)) {
                                VpnStateBridge.addLog("Native libXray JSON engine active on port $socksPort")
                                return true
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    fun RunXrayFromJSON(jsonConfig: String): Boolean {
        StopXray()
        return try {
            val root = JsonParser.parseString(jsonConfig).asJsonObject
            val socksPort = root.getAsJsonArray("inbounds")
                ?.firstOrNull()?.asJsonObject
                ?.get("port")?.asInt ?: VpnStateBridge.localSocksPort.value

            isRunning.set(true)

            if (tryStartNativeLibXray(jsonConfig, socksPort)) {
                return true
            }

            val startedBuiltIn = startBuiltInProtocolEngine(jsonConfig, socksPort)
            if (startedBuiltIn && waitForLocalPort(socksPort, 1500)) {
                VpnStateBridge.addLog("Smart™vPN Stealth Protocol Engine active on port $socksPort")
                true
            } else {
                isRunning.set(false)
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "RunXrayFromJSON error: ${t.message}", t)
            VpnStateBridge.addLog("Xray start error: ${t.message}")
            isRunning.set(false)
            false
        }
    }

    private data class ParsedOutbound(
        val protocol: String,
        val server: String,
        val port: Int,
        val uuidOrPass: String,
        val network: String,
        val security: String,
        val sni: String,
        val wsPath: String,
        val wsHost: String
    )

    private fun parseOutboundConfig(jsonConfig: String): ParsedOutbound? {
        return try {
            val root = JsonParser.parseString(jsonConfig).asJsonObject
            val out = root.getAsJsonArray("outbounds")?.firstOrNull()?.asJsonObject ?: return null
            val protocol = out.get("protocol")?.asString?.lowercase() ?: "vless"
            val settings = out.getAsJsonObject("settings")

            var server = ""
            var port = 443
            var uuidOrPass = ""

            if (settings?.has("vnext") == true) {
                val vnext = settings.getAsJsonArray("vnext").firstOrNull()?.asJsonObject
                server = vnext?.get("address")?.asString ?: ""
                port = vnext?.get("port")?.asInt ?: 443
                val user = vnext?.getAsJsonArray("users")?.firstOrNull()?.asJsonObject
                uuidOrPass = user?.get("id")?.asString ?: ""
            } else if (settings?.has("servers") == true) {
                val srv = settings.getAsJsonArray("servers").firstOrNull()?.asJsonObject
                server = srv?.get("address")?.asString ?: ""
                port = srv?.get("port")?.asInt ?: 443
                uuidOrPass = srv?.get("password")?.asString ?: ""
            } else if (settings?.has("peers") == true) {
                val peer = settings.getAsJsonArray("peers").firstOrNull()?.asJsonObject
                val ep = peer?.get("endpoint")?.asString ?: ""
                server = ep.substringBeforeLast(":")
                port = ep.substringAfterLast(":", "51820").toIntOrNull() ?: 51820
                uuidOrPass = settings.get("secretKey")?.asString ?: ""
            }

            val stream = out.getAsJsonObject("streamSettings")
            val network = stream?.get("network")?.asString?.lowercase() ?: "tcp"
            val security = stream?.get("security")?.asString?.lowercase() ?: "none"

            val tlsObj = stream?.getAsJsonObject("tlsSettings") ?: stream?.getAsJsonObject("realitySettings")
            val sni = tlsObj?.get("serverName")?.asString?.takeIf { it.isNotBlank() } ?: server

            val wsObj = stream?.getAsJsonObject("wsSettings")
            val wsPath = wsObj?.get("path")?.asString?.takeIf { it.isNotBlank() } ?: "/"
            val wsHost = wsObj?.getAsJsonObject("headers")?.get("Host")?.asString
                ?: wsObj?.get("host")?.asString
                ?: sni

            if (server.isEmpty()) null else ParsedOutbound(
                protocol = protocol,
                server = server,
                port = port,
                uuidOrPass = uuidOrPass,
                network = network,
                security = security,
                sni = sni,
                wsPath = wsPath,
                wsHost = wsHost
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun startBuiltInProtocolEngine(jsonConfig: String, socksPort: Int): Boolean {
        val outbound = parseOutboundConfig(jsonConfig) ?: return false
        return try {
            val srv = ServerSocket()
            srv.reuseAddress = true
            srv.bind(InetSocketAddress("127.0.0.1", socksPort), 512)
            fallbackServer = srv

            ioPool.execute {
                while (isRunning.get() && !srv.isClosed) {
                    try {
                        val client = srv.accept()
                        ioPool.execute { handleSocksClient(client, outbound) }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Built-in SOCKS engine error: ${t.message}")
            false
        }
    }

    private fun openRemoteTransport(outbound: ParsedOutbound): Triple<Socket, InputStream, OutputStream> {
        val raw = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            sendBufferSize = 262144
            receiveBufferSize = 262144
            connect(InetSocketAddress(outbound.server, outbound.port), 12000)
        }

        val useTls = outbound.security == "tls" || outbound.security == "reality" ||
            outbound.port in listOf(443, 8443, 2053, 2083, 2087, 2096)

        val transportSocket: Socket = if (useTls) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
            (ctx.socketFactory.createSocket(raw, outbound.sni, outbound.port, true) as SSLSocket).apply {
                val params = sslParameters
                params.serverNames = listOf(SNIHostName(outbound.sni))
                if (outbound.network == "ws" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    params.applicationProtocols = arrayOf("http/1.1")
                }
                sslParameters = params
                startHandshake()
            }
        } else {
            raw
        }

        if (outbound.network == "ws") {
            val rawIn = transportSocket.getInputStream()
            val rawOut = transportSocket.getOutputStream()
            val wsKey = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)
            val path = if (outbound.wsPath.startsWith("/")) outbound.wsPath else "/${outbound.wsPath}"
            val req = "GET $path HTTP/1.1\r\n" +
                "Host: ${outbound.wsHost}\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "User-Agent: Mozilla/5.0\r\n\r\n"
            rawOut.write(req.toByteArray(Charsets.UTF_8))
            rawOut.flush()

            val hdr = ByteArray(2048)
            var hLen = 0
            while (hLen < hdr.size) {
                val b = rawIn.read()
                if (b == -1) throw IllegalStateException("WS EOF during handshake")
                hdr[hLen++] = b.toByte()
                if (hLen >= 4 &&
                    hdr[hLen - 4] == '\r'.code.toByte() &&
                    hdr[hLen - 3] == '\n'.code.toByte() &&
                    hdr[hLen - 2] == '\r'.code.toByte() &&
                    hdr[hLen - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            return Triple(transportSocket, WebSocketFrameInputStream(rawIn), WebSocketFrameOutputStream(rawOut))
        }

        return Triple(transportSocket, transportSocket.getInputStream(), transportSocket.getOutputStream())
    }

    private fun uuidToBytes(uuidStr: String): ByteArray {
        return try {
            val u = UUID.fromString(uuidStr.trim())
            val bb = ByteBuffer.wrap(ByteArray(16))
            bb.putLong(u.mostSignificantBits)
            bb.putLong(u.leastSignificantBits)
            bb.array()
        } catch (_: Throwable) {
            ByteArray(16)
        }
    }

    private fun buildVlessHeader(uuidStr: String, cmd: Byte, dstHost: String, dstPort: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x00)
        out.write(uuidToBytes(uuidStr))
        out.write(0x00)
        out.write(cmd.toInt())
        out.write((dstPort shr 8) and 0xFF)
        out.write(dstPort and 0xFF)

        val ipv4Parts = dstHost.split(".")
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            out.write(0x01)
            for (p in ipv4Parts) out.write(p.toInt())
        } else {
            val domainBytes = dstHost.toByteArray(Charsets.UTF_8)
            out.write(0x02)
            out.write(domainBytes.size and 0xFF)
            out.write(domainBytes)
        }
        return out.toByteArray()
    }

    private fun buildTrojanHeader(password: String, cmd: Byte, dstHost: String, dstPort: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val sha224Hex = MessageDigest.getInstance("SHA-224")
            .digest(password.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        out.write(sha224Hex.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x0D, 0x0A))
        out.write(cmd.toInt())

        val ipv4Parts = dstHost.split(".")
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            out.write(0x01)
            for (p in ipv4Parts) out.write(p.toInt())
        } else {
            val domainBytes = dstHost.toByteArray(Charsets.UTF_8)
            out.write(0x03)
            out.write(domainBytes.size and 0xFF)
            out.write(domainBytes)
        }
        out.write((dstPort shr 8) and 0xFF)
        out.write(dstPort and 0xFF)
        out.write(byteArrayOf(0x0D, 0x0A))
        return out.toByteArray()
    }

    private fun openProtocolStream(outbound: ParsedOutbound, dstHost: String, dstPort: Int): Triple<Socket, InputStream, OutputStream> {
        val (sock, rawIn, rawOut) = openRemoteTransport(outbound)
        return when (outbound.protocol) {
            "vless" -> {
                val hdr = buildVlessHeader(outbound.uuidOrPass, 0x01, dstHost, dstPort)
                rawOut.write(hdr)
                rawOut.flush()
                val vlessIn = object : InputStream() {
                    private var headerRead = false
                    private fun ensureHeader() {
                        if (!headerRead) {
                            headerRead = true
                            val ver = rawIn.read()
                            val addonLen = rawIn.read()
                            if (ver >= 0 && addonLen > 0) {
                                var rem = addonLen
                                while (rem > 0) {
                                    val s = rawIn.skip(rem.toLong())
                                    if (s <= 0) {
                                        if (rawIn.read() == -1) break
                                        rem--
                                    } else {
                                        rem -= s.toInt()
                                    }
                                }
                            }
                        }
                    }
                    override fun read(): Int {
                        ensureHeader()
                        return rawIn.read()
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        ensureHeader()
                        return rawIn.read(b, off, len)
                    }
                }
                Triple(sock, vlessIn, rawOut)
            }
            "trojan" -> {
                val hdr = buildTrojanHeader(outbound.uuidOrPass, 0x01, dstHost, dstPort)
                rawOut.write(hdr)
                rawOut.flush()
                Triple(sock, rawIn, rawOut)
            }
            else -> Triple(sock, rawIn, rawOut)
        }
    }

    private fun handleSocksClient(client: Socket, outbound: ParsedOutbound) {
        var remoteSocket: Socket? = null
        try {
            client.tcpNoDelay = true
            val ins = DataInputStream(BufferedInputStream(client.getInputStream()))
            val outs = BufferedOutputStream(client.getOutputStream())

            val ver = ins.read()
            if (ver != 5) return
            val nMethods = ins.read()
            if (nMethods > 0) {
                val methods = ByteArray(nMethods)
                ins.readFully(methods)
            }
            outs.write(byteArrayOf(0x05, 0x00))
            outs.flush()

            val reqVer = ins.read()
            val cmd = ins.read()
            ins.read()
            val atyp = ins.read()
            if (reqVer != 5) return

            val dstHost = when (atyp) {
                1 -> { val a = ByteArray(4); ins.readFully(a); InetAddress.getByAddress(a).hostAddress ?: "" }
                3 -> { val l = ins.read(); val d = ByteArray(l); ins.readFully(d); String(d, Charsets.UTF_8) }
                4 -> { val a = ByteArray(16); ins.readFully(a); InetAddress.getByAddress(a).hostAddress ?: "" }
                else -> return
            }
            val dstPort = ins.readUnsignedShort()

            if (cmd == 3) {
                handleSocksUdpDns(client, ins, outs, outbound)
                return
            }
            if (cmd != 1) return

            val (rSock, rIn, rOut) = openProtocolStream(outbound, dstHost, dstPort)
            remoteSocket = rSock

            outs.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outs.flush()

            val upJob = ioPool.submit {
                try {
                    val buf = ByteArray(32768)
                    while (isRunning.get()) {
                        val n = ins.read(buf)
                        if (n == -1) break
                        rOut.write(buf, 0, n)
                        rOut.flush()
                    }
                } catch (_: Throwable) {}
            }

            val buf = ByteArray(32768)
            while (isRunning.get()) {
                val n = rIn.read(buf)
                if (n == -1) break
                outs.write(buf, 0, n)
                outs.flush()
            }
            upJob.cancel(true)
        } catch (_: Throwable) {
        } finally {
            try { remoteSocket?.close() } catch (_: Throwable) {}
            try { client.close() } catch (_: Throwable) {}
        }
    }

    private fun handleSocksUdpDns(
        client: Socket,
        ins: InputStream,
        outs: OutputStream,
        outbound: ParsedOutbound
    ) {
        var udpRelay: DatagramSocket? = null
        try {
            udpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            fallbackUdpSocket = udpRelay
            val port = udpRelay.localPort
            outs.write(
                byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((port shr 8) and 0xFF).toByte(),
                    (port and 0xFF).toByte()
                )
            )
            outs.flush()

            val relayRef = udpRelay
            ioPool.execute {
                val buf = ByteArray(4096)
                while (isRunning.get() && !relayRef.isClosed) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        relayRef.receive(pkt)
                        val data = pkt.data.copyOfRange(0, pkt.length)
                        val sender = pkt.socketAddress
                        if (data.size <= 10) continue

                        val atyp = data[3].toInt() and 0xFF
                        val offset = when (atyp) {
                            1 -> 8
                            3 -> 5 + (data[4].toInt() and 0xFF)
                            4 -> 20
                            else -> continue
                        }
                        val dstPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                        if (dstPort != 53) continue

                        val payloadStart = offset + 2
                        val socksHeader = data.copyOfRange(0, payloadStart)
                        val dnsQuery = data.copyOfRange(payloadStart, data.size)

                        ioPool.execute {
                            val dnsReply = resolveDnsQuery(dnsQuery, outbound)
                            if (dnsReply != null && dnsReply.isNotEmpty()) {
                                val fullResp = socksHeader + dnsReply
                                try {
                                    relayRef.send(DatagramPacket(fullResp, fullResp.size, sender))
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }

            while (isRunning.get() && ins.read() != -1) {}
        } catch (_: Throwable) {
        } finally {
            try { udpRelay?.close() } catch (_: Throwable) {}
            try { client.close() } catch (_: Throwable) {}
        }
    }

    private fun resolveDnsQuery(dnsQuery: ByteArray, outbound: ParsedOutbound): ByteArray? {
        for (dnsHost in listOf("1.1.1.1", "8.8.8.8")) {
            try {
                DatagramSocket().use { ds ->
                    ds.soTimeout = 2000
                    val addr = InetAddress.getByName(dnsHost)
                    ds.send(DatagramPacket(dnsQuery, dnsQuery.size, addr, 53))
                    val rBuf = ByteArray(2048)
                    val rPkt = DatagramPacket(rBuf, rBuf.size)
                    ds.receive(rPkt)
                    if (rPkt.length > 0) {
                        return rPkt.data.copyOfRange(0, rPkt.length)
                    }
                }
            } catch (_: Throwable) {}
        }

        try {
            val (rSock, rIn, rOut) = openProtocolStream(outbound, "1.1.1.1", 53)
            rSock.use {
                rSock.soTimeout = 3500
                val dos = DataOutputStream(rOut)
                val dis = DataInputStream(rIn)
                dos.writeShort(dnsQuery.size)
                dos.write(dnsQuery)
                dos.flush()
                val respLen = dis.readUnsignedShort()
                val resp = ByteArray(respLen)
                dis.readFully(resp)
                return resp
            }
        } catch (_: Throwable) {}

        return null
    }

    private class WebSocketFrameInputStream(delegate: InputStream) : InputStream() {
        private val dis = DataInputStream(delegate)
        private var frameBuf = ByteArray(0)
        private var framePos = 0

        private fun readNextFrame(): Boolean {
            while (true) {
                val b0 = dis.read()
                if (b0 == -1) return false
                val opcode = b0 and 0x0F
                val b1 = dis.read()
                if (b1 == -1) return false
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = dis.readUnsignedShort().toLong()
                } else if (len == 127L) {
                    len = dis.readLong()
                }
                val maskKey = if (masked) ByteArray(4).also { dis.readFully(it) } else null
                val payload = ByteArray(len.toInt())
                dis.readFully(payload)
                if (masked && maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }
                if (opcode == 0x8) return false
                if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                    if (payload.isNotEmpty()) {
                        frameBuf = payload
                        framePos = 0
                        return true
                    }
                }
            }
        }

        override fun read(): Int {
            if (framePos >= frameBuf.size) {
                if (!readNextFrame()) return -1
            }
            return frameBuf[framePos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (framePos >= frameBuf.size) {
                if (!readNextFrame()) return -1
            }
            val n = minOf(len, frameBuf.size - framePos)
            System.arraycopy(frameBuf, framePos, b, off, n)
            framePos += n
            return n
        }
    }

    private class WebSocketFrameOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val mask = Random.nextBytes(4)
            val header = java.io.ByteArrayOutputStream(14)
            header.write(0x82)
            if (len <= 125) {
                header.write(0x80 or len)
            } else if (len <= 65535) {
                header.write(0x80 or 126)
                header.write((len shr 8) and 0xFF)
                header.write(len and 0xFF)
            } else {
                header.write(0x80 or 127)
                val l = len.toLong()
                for (sh in 56 downTo 0 step 8) {
                    header.write(((l shr sh) and 0xFF).toInt())
                }
            }
            header.write(mask)
            val maskedPayload = ByteArray(len)
            for (i in 0 until len) {
                maskedPayload[i] = (b[off + i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            synchronized(delegate) {
                delegate.write(header.toByteArray())
                delegate.write(maskedPayload)
            }
        }

        override fun flush() {
            synchronized(delegate) { delegate.flush() }
        }
    }

    fun startLocalPort80Relay(remoteHost: String, remotePort: Int): Int = remotePort

    fun StopXray() {
        isRunning.set(false)
        try { fallbackUdpSocket?.close() } catch (_: Throwable) {}
        fallbackUdpSocket = null
        try { fallbackServer?.close() } catch (_: Throwable) {}
        fallbackServer = null

        val candidateClasses = listOf("libXray.LibXray", "go.libXray.LibXray", "com.xray.core.XrayCore")
        for (className in candidateClasses) {
            for (methodName in listOf("stopXray", "StopXray")) {
                try {
                    Class.forName(className).getMethod(methodName).invoke(null)
                } catch (_: Throwable) {}
            }
        }
    }
}
// === END OF XrayCore.kt ===
