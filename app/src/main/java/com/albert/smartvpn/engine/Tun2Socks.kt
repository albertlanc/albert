package com.albert.smartvpn.engine

import android.util.Log
import hev.htproxy.TProxyService
import java.io.File

package hev.htproxy {
    class TProxyService {
        companion object {
            init {
                try {
                    System.loadLibrary("hev-socks5-tunnel")
                } catch (e: Throwable) {
                    Log.e("TProxyService", "Failed to load libhev-socks5-tunnel.so: ${e.message}")
                }
            }

            @JvmStatic
            external fun TProxyStartService(configPath: String, fd: Int): Boolean

            @JvmStatic
            external fun TProxyStopService(): Boolean

            @JvmStatic
            external fun TProxyIsRunning(): Boolean
        }
    }
}
