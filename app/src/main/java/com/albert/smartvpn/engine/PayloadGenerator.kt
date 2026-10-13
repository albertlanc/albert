package com.albert.smartvpn.engine

object PayloadGenerator {
    /**
     * Generates a raw HTTP payload string from a user-defined template.
     * Example template: "CONNECT [host_port] HTTP/1.1[crlf]Host: [sni][crlf]Connection: Keep-Alive[crlf][crlf]"
     */
    fun generatePayload(template: String, targetHost: String, targetPort: Int, sni: String): String {
        var payload = template
        
        // Standard replacements for custom HTTP injectors
        payload = payload.replace("[crlf]", "\r\n")
        payload = payload.replace("[cr]", "\r")
        payload = payload.replace("[lf]", "\n")
        payload = payload.replace("[host_port]", "$targetHost:$targetPort")
        payload = payload.replace("[host]", targetHost)
        payload = payload.replace("[port]", targetPort.toString())
        payload = payload.replace("[sni]", sni.takeIf { it.isNotEmpty() } ?: targetHost)
        payload = payload.replace("[protocol]", "HTTP/1.1")
        
        // Ensure the payload terminates with a double CRLF if not explicitly provided
        if (!payload.endsWith("\r\n\r\n")) {
            payload += "\r\n\r\n"
        }
        
        return payload
    }
}
