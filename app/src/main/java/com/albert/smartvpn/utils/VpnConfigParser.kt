package com.albert.smartvpn.utils

import android.net.Uri
import android.util.Base64
import com.albert.smartvpn.data.VpnProfile
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.net.URLEncoder

object VpnConfigParser {

    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    fun detectFlag(host: String, name: String): String {
        val combined = "$host $name".lowercase()
        return when {
            combined.contains("uk") || combined.contains(".co.uk") || combined.contains("london") -> "🇬🇧"
            combined.contains("nl") || combined.contains("amsterdam") || combined.contains("nether") -> "🇳🇱"
            combined.contains("us") || combined.contains("usa") || combined.contains("new york") -> "🇺🇸"
            combined.contains("de") || combined.contains("frankfurt") || combined.contains("german") -> "🇩🇪"
            combined.contains("fr") || combined.contains("paris") -> "🇫🇷"
            combined.contains("sg") || combined.contains("singapore") -> "🇸🇬"
            combined.contains("ng") || combined.contains("nigeria") || combined.contains("lagos") -> "🇳🇬"
            combined.contains("za") || combined.contains("south africa") -> "🇿🇦"
            combined.contains("ca") || combined.contains("canada") -> "🇨🇦"
            else -> "🇳🇱"
        }
    }

    fun toStandardUri(profile: VpnProfile): String {
        if (profile.isLocked || profile.isImported) return ""
        val encName = URLEncoder.encode(profile.name, "UTF-8")
        return when (profile.protocol.uppercase()) {
            "TROJAN" -> "trojan://${profile.uuid}@${profile.server}:${profile.port}?security=${profile.security}&type=${profile.network}&sni=${profile.sni}&path=${URLEncoder.encode(profile.path, "UTF-8")}#$encName"
            "VLESS" -> "vless://${profile.uuid}@${profile.server}:${profile.port}?security=${profile.security}&type=${profile.network}&sni=${profile.sni}&pbk=${profile.pbk}&sid=${profile.sid}&fp=${profile.utlsFingerprint}&flow=${profile.flow}&path=${URLEncoder.encode(profile.path, "UTF-8")}#$encName"
            "VMESS" -> {
                val json = """{"v":"2","ps":"${profile.name}","add":"${profile.server}","port":"${profile.port}","id":"${profile.uuid}","aid":"0","net":"${profile.network}","type":"none","host":"${profile.wsHost.ifBlank { profile.sni }}","path":"${profile.path}","tls":"${profile.security}","sni":"${profile.sni}"}"""
                "vmess://" + Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            "SHADOWSOCKS" -> {
                val userB64 = Base64.encodeToString("${profile.ssMethod}:${profile.uuid}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                "ss://$userB64@${profile.server}:${profile.port}#$encName"
            }
            "HYSTERIA2" -> "hy2://${profile.hy2Password.ifBlank { profile.uuid }}@${profile.server}:${profile.port}?sni=${profile.sni}&obfs=${profile.hy2Obfs}&obfs-password=${profile.hy2ObfsPassword}&mport=${profile.hy2PortHopping}#$encName"
            else -> "ssh://${profile.sshUser}:${profile.sshPass}@${profile.server}:${profile.port}?sni=${profile.sni}&payload=${URLEncoder.encode(profile.payload, "UTF-8")}#$encName"
        }
    }

    fun toStandardJson(profile: VpnProfile): String {
        if (profile.isLocked || profile.isImported) return ""
        return if (profile.rawJson.isNotBlank()) profile.rawJson else prettyGson.toJson(profile)
    }

    fun parseUri(rawUri: String): VpnProfile? {
        val text = rawUri.trim()
        if (text.isEmpty()) return null

        val parsed = try {
            when {
                text.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(text, "VLESS")
                text.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(text, "TROJAN")
                text.startsWith("vmess://", ignoreCase = true) -> parseVmess(text)
                text.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(text)
                text.startsWith("hy2://", ignoreCase = true) ||
                    text.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(text)
                text.startsWith("slowdns://", ignoreCase = true) ||
                    text.startsWith("dnstt://", ignoreCase = true) -> parseSlowDnsUri(text)
                text.startsWith("ssh://", ignoreCase = true) -> parseSshUri(text)
                text.contains("[Interface]", ignoreCase = true) &&
                    text.contains("[Peer]", ignoreCase = true) -> parseWireGuardConf(text)
                text.contains("remote ", ignoreCase = true) &&
                    text.contains("dev tun", ignoreCase = true) -> parseOpenVpnConf(text)
                text.startsWith("{") && text.endsWith("}") -> parseRawJsonProfile(text)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        // Open URIs/configs imported via clipboard/file are unlocked (isLocked = false, isImported = false)
        // so the user can edit them, lock them, and share them!
        return parsed?.copy(isImported = false, isLocked = false)
    }

    private fun parseRawJsonProfile(jsonText: String): VpnProfile? {
        return try {
            val obj = JsonParser.parseString(jsonText).asJsonObject
            if (obj.has("protocol") && obj.has("server")) {
                val p = prettyGson.fromJson(jsonText, VpnProfile::class.java)
                val locked = p?.isLocked == true
                p?.copy(id = 0, isSelected = true, isImported = locked, isLocked = locked)
            } else {
                VpnProfile(
                    name = "Open JSON Config",
                    protocol = "VMESS",
                    server = "json-config",
                    port = 443,
                    uuid = "json",
                    rawJson = jsonText,
                    isSelected = true,
                    isImported = false,
                    isLocked = false
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseVlessOrTrojan(uriString: String, protocol: String): VpnProfile? {
        val uri = Uri.parse(uriString)
        val userInfo = uri.userInfo?.trim() ?: return null
        val host = uri.host?.trim() ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
            ?: "$protocol-$host"

        val securityParam = uri.getQueryParameter("security")?.lowercase()
            ?: if (port in listOf(80, 8080, 8880, 2052, 2082, 2086, 2095)) "none" else "tls"
        val networkParam = uri.getQueryParameter("type")?.lowercase() ?: "ws"
        val sniParam = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("host")
            ?: host
        val wsHostParam = uri.getQueryParameter("host") ?: sniParam
        val pathParam = uri.getQueryParameter("path")
            ?: uri.getQueryParameter("serviceName")
            ?: "/"
        val pbkParam = uri.getQueryParameter("pbk") ?: ""
        val sidParam = uri.getQueryParameter("sid") ?: ""
        val spxParam = uri.getQueryParameter("spx") ?: "/"
        val fpParam = uri.getQueryParameter("fp") ?: "chrome"
        val flowParam = uri.getQueryParameter("flow") ?: ""
        val alpnParam = uri.getQueryParameter("alpn") ?: "h2, http/1.1"

        return VpnProfile(
            name = remark,
            protocol = protocol,
            server = host,
            port = port,
            uuid = userInfo,
            network = networkParam,
            security = securityParam,
            sni = sniParam,
            wsHost = wsHostParam,
            alpn = alpnParam,
            pbk = pbkParam,
            sid = sidParam,
            spx = spxParam,
            utlsFingerprint = fpParam,
            flow = flowParam,
            path = pathParam,
            countryFlag = detectFlag(host, remark),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }

    private fun parseVmess(uriString: String): VpnProfile? {
        val b64 = uriString.substringAfter("vmess://").trim()
        val decodedBytes = Base64.decode(b64, Base64.DEFAULT)
        val jsonStr = String(decodedBytes, Charsets.UTF_8)
        val obj = JsonParser.parseString(jsonStr).asJsonObject

        val host = obj.get("add")?.asString?.trim() ?: return null
        val port = obj.get("port")?.asString?.toIntOrNull() ?: 443
        val uuid = obj.get("id")?.asString?.trim() ?: return null
        val name = obj.get("ps")?.asString?.takeIf { it.isNotBlank() } ?: "VMESS-$host"
        val net = obj.get("net")?.asString?.lowercase() ?: "ws"
        val tlsField = obj.get("tls")?.asString?.lowercase() ?: ""
        val security = if (tlsField == "tls" || (port == 443 && tlsField != "none")) "tls" else "none"
        val sni = obj.get("sni")?.asString?.takeIf { it.isNotBlank() }
            ?: obj.get("host")?.asString?.takeIf { it.isNotBlank() }
            ?: host
        val wsHost = obj.get("host")?.asString?.takeIf { it.isNotBlank() } ?: sni
        val path = obj.get("path")?.asString?.takeIf { it.isNotBlank() } ?: "/"

        return VpnProfile(
            name = name,
            protocol = "VMESS",
            server = host,
            port = port,
            uuid = uuid,
            network = net,
            security = security,
            sni = sni,
            wsHost = wsHost,
            path = path,
            countryFlag = detectFlag(host, name),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }

    private fun parseShadowsocks(uriString: String): VpnProfile? {
        val raw = uriString.removePrefix("ss://").removePrefix("SS://")
        val remark = if (raw.contains("#")) URLDecoder.decode(raw.substringAfter("#"), "UTF-8") else "SHADOWSOCKS"
        val mainPart = raw.substringBefore("#")
        val decoded = if (!mainPart.contains("@")) {
            String(Base64.decode(mainPart, Base64.DEFAULT), Charsets.UTF_8)
        } else {
            val userB64 = mainPart.substringBefore("@")
            val hostPart = mainPart.substringAfter("@")
            val userDecoded = try {
                String(Base64.decode(userB64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Throwable) { userB64 }
            "$userDecoded@$hostPart"
        }
        val creds = decoded.substringBeforeLast("@")
        val hostPort = decoded.substringAfterLast("@")
        val method = creds.substringBefore(":").ifBlank { "aes-256-gcm" }
        val password = creds.substringAfter(":")
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 8388

        return VpnProfile(
            name = remark,
            protocol = "SHADOWSOCKS",
            server = host,
            port = port,
            uuid = password,
            ssMethod = method,
            countryFlag = detectFlag(host, remark),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }

    private fun parseHysteria2(uriString: String): VpnProfile? {
        val normalized = if (uriString.startsWith("hy2://", true)) {
            "hysteria2://" + uriString.substring(6)
        } else {
            uriString
        }
        val uri = Uri.parse(normalized)
        val password = uri.userInfo?.let { URLDecoder.decode(it, "UTF-8") }?.trim() ?: ""
        val host = uri.host?.trim() ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
            ?: "HYSTERIA2-$host"

        val sni = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("peer")
            ?: host
        val obfs = uri.getQueryParameter("obfs") ?: ""
        val obfsPassword = uri.getQueryParameter("obfs-password") ?: ""
        val mport = uri.getQueryParameter("mport") ?: ""
        val pinSha256 = uri.getQueryParameter("pinSHA256") ?: ""
        val upMbps = uri.getQueryParameter("upmbps")?.toIntOrNull() ?: 50
        val downMbps = uri.getQueryParameter("downmbps")?.toIntOrNull() ?: 100

        return VpnProfile(
            name = remark,
            protocol = "HYSTERIA2",
            server = host,
            port = port,
            uuid = password,
            network = "quic",
            security = "tls",
            sni = sni,
            hy2Password = password,
            hy2Obfs = obfs,
            hy2ObfsPassword = obfsPassword,
            hy2PortHopping = mport,
            pinSha256 = pinSha256,
            hy2UpMbps = upMbps,
            hy2DownMbps = downMbps,
            countryFlag = detectFlag(host, remark),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }

    private fun parseSlowDnsUri(uriString: String): VpnProfile? {
        val uri = Uri.parse(uriString)
        val nsDomain = uri.host?.trim() ?: return null
        val port = if (uri.port > 0) uri.port else 22
        val userInfo = uri.userInfo ?: ""
        val sshUser = userInfo.substringBefore(":")
        val sshPass = userInfo.substringAfter(":", "")
        val dnsIp = uri.getQueryParameter("dns") ?: "8.8.8.8:53"
        val pubKey = uri.getQueryParameter("pubkey") ?: ""
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
            ?: "SSH-DNSTT-$nsDomain"

        return VpnProfile(
            name = remark,
            protocol = "SSH-DNSTT",
            server = nsDomain,
            port = port,
            uuid = sshUser,
            network = "slowdns",
            security = "none",
            sshUser = sshUser,
            sshPass = sshPass,
            slowDnsDomain = nsDomain,
            slowDnsPubKey = pubKey,
            slowDnsServerIp = dnsIp,
            pbk = pubKey,
            countryFlag = detectFlag(nsDomain, remark),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }

    private fun parseSshUri(uriString: String): VpnProfile? {
        val uri = Uri.parse(uriString)
        val host = uri.host?.trim() ?: return null
        val port = if (uri.port > 0) uri.port else 22
        val userInfo = uri.userInfo ?: ""
        val sshUser = userInfo.substringBefore(":")
        val sshPass = userInfo.substringAfter(":", "")
        val sni = uri.getQueryParameter("sni") ?: ""
        val payload = uri.getQueryParameter("payload")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        val mode = when {
            payload.isNotBlank() && sni.isNotBlank() -> "SSH-TLS-PAYLOAD"
            payload.isNotBlank() -> "SSH-PAYLOAD"
            sni.isNotBlank() -> "SSH-TLS"
            else -> "SSH-DIRECT"
        }
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
            ?: "$mode-$host"

        return VpnProfile(
            name = remark,
            protocol = mode,
            server = host,
            port = port,
            uuid = sshUser,
            sshUser = sshUser,
            sshPass = sshPass,
            sni = sni,
            payload = payload,
            security = if (port == 443 || mode.contains("TLS")) "tls" else "none",
            countryFlag = detectFlag(host, remark),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }

    private fun parseWireGuardConf(confText: String): VpnProfile? {
        var privateKey = ""
        var address = "10.0.0.2/32"
        var allowedIps = "0.0.0.0/0, ::/0"
        var mtu = 1420
        var publicKey = ""
        var presharedKey = ""
        var endpointHost = ""
        var endpointPort = 51820

        confText.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("#").trim()
            if (line.contains("=")) {
                val key = line.substringBefore("=").trim().lowercase()
                val value = line.substringAfter("=").trim()
                when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> address = value.substringBefore(",").trim()
                    "allowedips" -> allowedIps = value
                    "mtu" -> mtu = value.toIntOrNull() ?: 1420
                    "publickey" -> publicKey = value
                    "presharedkey" -> presharedKey = value
                    "endpoint" -> {
                        endpointHost = value.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
                        endpointPort = value.substringAfterLast(":").toIntOrNull() ?: 51820
                    }
                }
            }
        }

        if (endpointHost.isEmpty() || privateKey.isEmpty() || publicKey.isEmpty()) return null

        return VpnProfile(
            name = "WIREGUARD-$endpointHost",
            protocol = "WIREGUARD",
            server = endpointHost,
            port = endpointPort,
            uuid = publicKey,
            network = "udp",
            security = "noise",
            wgPrivateKey = privateKey,
            wgPublicKey = publicKey,
            wgPresharedKey = presharedKey,
            wgAddress = address,
            wgAllowedIps = allowedIps,
            wgMtu = mtu,
            countryFlag = detectFlag(endpointHost, "WireGuard"),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }

    private fun parseOpenVpnConf(ovpnText: String): VpnProfile? {
        var host = ""
        var port = 1194
        ovpnText.lineSequence().forEach { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.startsWith("remote ", ignoreCase = true)) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) host = parts[1]
                if (parts.size >= 3) port = parts[2].toIntOrNull() ?: 1194
            }
        }
        if (host.isEmpty()) return null
        return VpnProfile(
            name = "OPENVPN-$host",
            protocol = "OPENVPN",
            server = host,
            port = port,
            countryFlag = detectFlag(host, "OpenVPN"),
            isSelected = true,
            isImported = false,
            isLocked = false
        )
    }
}
