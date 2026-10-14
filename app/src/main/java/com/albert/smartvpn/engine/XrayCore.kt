package com.albert.smartvpn.engine

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
                // Try next generated gomobile class name
            }
        }
        Log.e(TAG, "Could not locate LibXray.invoke in libXray.aar")
        return null
    }

    fun RunXrayFromJSON(configJson: String): Boolean {
        return try {
            val payload = JsonObject().apply {
                addProperty("xrayJson", configJson)
            }
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "runXray")
                add("payload", payload)
            }

            val responseStr = invokeLibXray(request.toString()) ?: return false
            Log.d(TAG, "runXray response: $responseStr")
            val responseJson = JsonParser.parseString(responseStr).asJsonObject
            responseJson.get("success")?.asBoolean ?: false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start Xray: ${e.message}", e)
            false
        }
    }

    fun StopXray(): Boolean {
        return try {
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "stopXray")
            }
            val responseStr = invokeLibXray(request.toString()) ?: return false
            Log.d(TAG, "stopXray response: $responseStr")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop Xray: ${e.message}", e)
            false
        }
    }

    fun XrayVersion(): String {
        return try {
            val request = JsonObject().apply {
                addProperty("apiVersion", 3)
                addProperty("method", "xrayVersion")
            }
            invokeLibXray(request.toString()) ?: "Xray-core"
        } catch (e: Throwable) {
            "Unknown"
        }
    }
}
