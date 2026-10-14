package com.albert.smartvpn.engine

import android.util.Log
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetSocketAddress
import java.net.Socket

object XrayCore {
    private const val TAG = "XrayCore"

    private fun invokeLibXray(requestJson: String): String? {
        val candidateClasses = listOf("libXray.LibXray", "libxray.Libxray")
        for (className in candidateClasses) {
            try {
                val clazz = Class.forName(className)
                val method = clazz.methods.firstOrNull {
                    it.name.equals("invoke", ignoreCase = true) && it.parameterTypes.size == 1
                }
                if (method != null) {
                    return method.invoke(null, requestJson) as? String
                }
            } catch (_: Throwable) {
                // Try next candidate
            }
        }
        return null
    }

    fun RunXrayFromJSON(configJson: String): Boolean {
        return try {
            VpnStateBridge.addLog("Starting Xray-core engine...")

            // Automatically strip removed "allowInsecure" field if present in any raw config
            val sanitizedConfig = configJson
                .replace(Regex(""""allowInsecure"\s*:\s*(true|false)\s*,\s*"""), "")
                .replace(Regex(""",\s*"allowInsecure"\s*:\s*(true|false)"""), "")
                .replace(Regex(""""allowInsecure"\s*:\s*(true|false)"""), "")

            val payload = JsonObject().apply {
                addProperty("xrayJson", sanitizedConfig)
            }
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "runXray")
                add("payload", payload)
            }

            val responseStr = invokeLibXray(request.toString())
            if (responseStr != null) {
                val responseJson = JsonParser.parseString(responseStr).asJsonObject
                val ok = responseJson.get("success")?.asBoolean ?: false
                if (ok) {
                    VpnStateBridge.addLog("Xray-core engine started successfully.")
                    return true
                } else {
                    VpnStateBridge.addLog("Xray-core rejected config: $responseStr")
                    return false
                }
            } else {
                VpnStateBridge.addLog("Error: libXray native bridge unavailable.")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Xray: ${t.message}", t)
            VpnStateBridge.addLog("Xray error: ${t.message}")
            false
        }
    }

    fun verifyServerReachable(host: String, port: Int): Boolean {
        return try {
            val cleanHost = host.trim()
            VpnStateBridge.addLog("Connecting to endpoint $cleanHost:$port...")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(cleanHost, port), 10000)
            }
            VpnStateBridge.addLog("Endpoint $cleanHost:$port is reachable.")
            true
        } catch (t: Throwable) {
            VpnStateBridge.addLog("Endpoint unreachable ($host:$port): ${t.message}")
            false
        }
    }

    fun StopXray(): Boolean {
        return try {
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "stopXray")
            }
            invokeLibXray(request.toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun XrayVersion(): String = "Xray-core"
}
