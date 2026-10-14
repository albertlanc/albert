package com.albert.smartvpn.utils

import android.net.Uri
import android.util.Base64
import com.albert.smartvpn.data.VpnProfile
import com.google.gson.JsonParser

object VpnConfigParser {

    fun parseUri(uriString: String): VpnProfile? {
        val raw = uriString.trim()
        return try {
            when {
                raw.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(raw, "VLESS")
                raw.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(raw, "TROJAN")
                raw.startsWith("vmess://", ignoreCase = true) -> parseVmess(raw)
                raw.startsWith("hy2://", ignoreCase = true) ||
                    raw.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(raw)
                raw.startsWith("ssh://", ignoreCase = true) -> parseSshUri(raw)
                raw.contains("[Interface]", ignoreCase = true) &&
                    raw.contains("[Peer]", ignoreCase = true) -> parseWireGuardConf(raw)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseVlessOrTrojan(uriStr: String, proto: String): VpnProfile? {
        val uri = Uri.parse(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else 443
        val uuid = uri.userInfo ?: ""
        val name = uri.fragment?.takeIf { it.isNotBlank() } ?: "$proto-$host"

        val rawSecurity = uri.getQueryParameter("security")?.lowercase() ?: ""
        val securityParam = when {
            rawSecurity in listOf("tls", "reality", "none") -> rawSecurity
            port in listOf(80, 8080, 8880, 2052, 2082, 2086, 2095) -> "none"
            else -> "tls"
        }

        val sni = uri.getQueryParameter("sni")?.takeIf { it.isNotBlank() }
            ?: uri.getQueryParameter("host")?.takeIf { it.isNotBlank() }
            ?: host
        val type = uri.getQueryParameter("type")?.lowercase()?.takeIf { it.isNotBlank() } ?: "ws"
        val path = uri.getQueryParameter("path")?.takeIf { it.isNotBlank() }
            ?: uri.getQueryParameter("serviceName")?.takeIf { it.isNotBlank() }
            ?: "/"
        val pbk = uri.getQueryParameter("pbk") ?: ""
        val sid = uri.getQueryParameter("sid") ?: ""
        val flow = uri.getQueryParameter("flow") ?: ""

        return VpnProfile(
            name = name,
            protocol = proto,
            server = host,
            port = port,
            uuid = uuid,
            network = type,
            security = securityParam,
            sni = sni,
            pbk = pbk,
            sid = sid,
            path = path,
            flow = flow,
            isSelected = true
        )
    }

    private fun parseVmess(uriStr: String): VpnProfile? {
        val b64 = uriStr.substringAfter("vmess://").trim()
        val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        val obj = JsonParser.parseString(decoded).asJsonObject

        val host = obj.get("add")?.asString ?: return null
        val port = obj.get("port")?.asString?.toIntOrNull() ?: 443
        val uuid = obj.get("id")?.asString ?: ""
        val name = obj.get("ps")?.asString?.takeIf { it.isNotBlank() } ?: "VMESS-$host"
        val net = obj.get("net")?.asString?.lowercase()?.takeIf { it.isNotBlank() } ?: "ws"
        val tls = obj.get("tls")?.asString?.lowercase() ?: ""
        val security = when {
            tls == "tls" -> "tls"
            tls == "none" || port in listOf(80, 8080, 8880, 2052, 2082, 2086, 2095) -> "none"
            else -> "tls"
        }
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

    private fun parseHysteria2(uriStr: String): VpnProfile? {
        val normalized = uriStr
            .replaceFirst(Regex("^hysteria2://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^hy2://", RegexOption.IGNORE_CASE), "https://")
        val uri = Uri.parse(normalized)
        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else 443
        val authPass = uri.userInfo ?: ""
        val name = uri.fragment?.takeIf { it.isNotBlank() } ?: "HY2-$host"
        val sni = uri.getQueryParameter("sni") ?: host
        val obfs = uri.getQueryParameter("obfs") ?: ""
        val obfsPassword = uri.getQueryParameter("obfs-password") ?: ""

        return VpnProfile(
            name = name,
            protocol = "HYSTERIA2",
            server = host,
            port = port,
            sni = sni,
            security = "tls",
            hy2Password = authPass,
            hy2Obfs = obfs,
            hy2ObfsPassword = obfsPassword,
            isSelected = true
        )
    }

    private fun parseWireGuardConf(conf: String): VpnProfile? {
        var privKey = ""
        var pubKey = ""
        var psk = ""
        var address = "10.0.0.2/32"
        var dns = "1.1.1.1"
        var mtu = 1420
        var endpointHost = ""
        var endpointPort = 51820

        conf.lines().forEach { rawLine ->
            val line = rawLine.substringBefore("#").trim()
            if (line.contains("=")) {
                val key = line.substringBefore("=").trim().lowercase()
                val value = line.substringAfter("=").trim()
                when (key) {
                    "privatekey" -> privKey = value
                    "publickey" -> pubKey = value
                    "presharedkey" -> psk = value
                    "address" -> address = value.substringBefore(",").trim()
                    "dns" -> dns = value.substringBefore(",").trim()
                    "mtu" -> mtu = value.toIntOrNull() ?: 1420
                    "endpoint" -> {
                        endpointHost = value.substringBeforeLast(":").trim()
                        endpointPort = value.substringAfterLast(":").toIntOrNull() ?: 51820
                    }
                }
            }
        }
        if (endpointHost.isEmpty() || privKey.isEmpty() || pubKey.isEmpty()) return null

        return VpnProfile(
            name = "WG-$endpointHost",
            protocol = "WIREGUARD",
            server = endpointHost,
            port = endpointPort,
            wgPrivateKey = privKey,
            wgPublicKey = pubKey,
            wgPresharedKey = psk,
            wgAddress = address,
            wgMtu = mtu,
            customDns = dns,
            security = "noise",
            isSelected = true
        )
    }

    private fun parseSshUri(uriStr: String): VpnProfile? {
        val uri = Uri.parse(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else 22
        val userInfo = uri.userInfo ?: ""
        val user = userInfo.substringBefore(":")
        val pass = userInfo.substringAfter(":", "")
        val name = uri.fragment?.takeIf { it.isNotBlank() } ?: "SSH-$host"
        return VpnProfile(
            name = name,
            protocol = "SSH",
            server = host,
            port = port,
            sshUser = user,
            sshPass = pass,
            isSelected = true
        )
    }
}
