package com.albert.smartvpn.engine

object PayloadGenerator {
    fun generatePayload(
        template: String,
        targetHost: String,
        targetPort: Int,
        sni: String
    ): String {
        val effectiveHost = sni.trim().ifEmpty { targetHost.trim() }
        return template
            .replace("[host_port]", "$effectiveHost:$targetPort", ignoreCase = true)
            .replace("[host]", effectiveHost, ignoreCase = true)
            .replace("[sni]", effectiveHost, ignoreCase = true)
            .replace("[port]", targetPort.toString(), ignoreCase = true)
            .replace("[protocol]", "HTTP/1.1", ignoreCase = true)
            .replace("[ua]", "Mozilla/5.0 (Linux; Android 14; SmartVPN/3.0)", ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("[cr]", "\r", ignoreCase = true)
            .replace("[lf]", "\n", ignoreCase = true)
    }
}
