package com.albert.smartvpn.utils

import android.net.Uri
import com.albert.smartvpn.data.VpnProfile
import java.net.URLDecoder

object VpnConfigParser {
    fun parseUri(uriString: String): VpnProfile? {
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme?.lowercase()) {
                "vless" -> parseVless(uri)
                "trojan" -> parseTrojan(uri)
                // VMess usually uses base64 encoding; parsed separately via Gson
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVless(uri: Uri): VpnProfile {
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "VLESS Profile"

        return VpnProfile(
            name = name,
            protocol = "vless",
            server = host,
            port = port,
            uuid = uuid,
            network = uri.getQueryParameter("type") ?: "tcp",
            security = uri.getQueryParameter("security") ?: "none",
            sni = uri.getQueryParameter("sni") ?: "",
            pbk = uri.getQueryParameter("pbk") ?: "",
            sid = uri.getQueryParameter("sid") ?: "",
            path = uri.getQueryParameter("path") ?: "",
            flow = uri.getQueryParameter("flow") ?: ""
        )
    }

    private fun parseTrojan(uri: Uri): VpnProfile {
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Trojan Profile"

        return VpnProfile(
            name = name,
            protocol = "trojan",
            server = host,
            port = port,
            uuid = password,
            network = uri.getQueryParameter("type") ?: "tcp",
            security = uri.getQueryParameter("security") ?: "tls",
            sni = uri.getQueryParameter("sni") ?: "",
            path = uri.getQueryParameter("path") ?: ""
        )
    }
}
