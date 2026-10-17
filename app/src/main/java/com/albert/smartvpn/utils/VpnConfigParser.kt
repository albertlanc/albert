package com.albert.smartvpn.utils

import android.net.Uri
import android.util.Base64
import com.albert.smartvpn.data.VpnProfile
import com.google.gson.JsonParser
import java.net.URLDecoder

object VpnConfigParser {

    fun parseUri(rawUri: String): VpnProfile? {
        val text = rawUri.trim()
        if (text.isEmpty()) return null

        return try {
            when {
                text.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(text, "VLESS")
                text.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(text, "TROJAN")
                text.startsWith("vmess://", ignoreCase = true) -> parseVmess(text)
                text.startsWith("hy2://", ignoreCase = true) ||
                    text.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(text)
                text.startsWith("slowdns://", ignoreCase = true) -> parseSlowDnsUri(text)
                text.startsWith("ssh://", ignoreCase = true) -> parseSshUri(text)
                text.contains("[Interface]", ignoreCase = true) &&
                    text.contains("[Peer]", ignoreCase = true) -> parseWireGuardConf(text)
                else -> null
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
        val pathParam = uri.getQueryParameter("path")
            ?: uri.getQueryParameter("serviceName")
            ?: "/"
        val pbkParam = uri.getQueryParameter("pbk") ?: ""
        val sidParam = uri.getQueryParameter("sid") ?: ""
        val flowParam = uri.getQueryParameter("flow") ?: ""

        return VpnProfile(
            name = remark,
            protocol = protocol,
            server = host,
            port = port,
            uuid = userInfo,
            network = networkParam,
            security = securityParam,
            sni = sniParam,
            pbk = pbkParam,
            sid = sidParam,
            flow = flowParam,
            path = pathParam,
            isSelected = true
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
            path = path,
            isSelected = true
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
            ?: "Hysteria2-$host"

        val sni = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("peer")
            ?: host
        val obfs = uri.getQueryParameter("obfs") ?: ""
        val obfsPassword = uri.getQueryParameter("obfs-password") ?: ""
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
            hy2UpMbps = upMbps,
            hy2DownMbps = downMbps,
            isSelected = true
        )
    }

    private fun parseSlowDnsUri(uriString: String): VpnProfile? {
        val uri = Uri.parse(uriString)
        val nsDomain = uri.host?.trim() ?: return null
        val port = if (uri.port > 0) uri.port else 53
        val userInfo = uri.userInfo ?: ""
        val sshUser = userInfo.substringBefore(":")
        val sshPass = userInfo.substringAfter(":", "")
        val dnsIp = uri.getQueryParameter("dns") ?: "8.8.8.8"
        val pubKey = uri.getQueryParameter("pubkey") ?: ""
        val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
            ?: "SlowDNS-$nsDomain"

        return VpnProfile(
            name = remark,
            protocol = "SLOWDNS",
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
            isSelected = true
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
            payload.isNotBlank() && sni.isNotBlank() -> "SSH+WS+SNI+PAYLOAD"
            payload.isNotBlank() -> "SSH+PAYLOAD"
            sni.isNotBlank() -> "SSH+SSL"
            else -> "SSH"
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
            security = if (port == 443 || mode == "SSH+SSL") "tls" else "none",
            isSelected = true
        )
    }

    private fun parseWireGuardConf(confText: String): VpnProfile? {
        var privateKey = ""
        var address = "10.0.0.2/32"
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
            name = "WireGuard-$endpointHost",
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
            wgMtu = mtu,
            isSelected = true
        )
    }
}
// === END OF FILE 4 ===
