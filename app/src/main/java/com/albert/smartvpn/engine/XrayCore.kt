package com.albert.smartvpn.engine

import android.util.Log

object XrayCore {
    init {
        try {
            System.loadLibrary("Xray") // Loads libXray.so
        } catch (e: UnsatisfiedLinkError) {
            Log.e("XrayCore", "Native Xray library not found: ${e.message}")
        }
    }

    /**
     * Starts the Xray core using a generated JSON configuration string.
     * The config must include an inbound SOCKS port (e.g., 10808) for hev-socks5-tunnel to connect to.
     */
    external fun RunXrayFromJSON(configJson: String): Boolean

    /**
     * Stops the running Xray instance.
     */
    external fun StopXray(): Boolean
    
    /**
     * Queries the core version.
     */
    external fun XrayVersion(): String
}
