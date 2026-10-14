package hev.htproxy

import android.util.Log

object TProxyService {
    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (t: Throwable) {
            Log.e("TProxyService", "Failed to load libhev-socks5-tunnel.so: ${t.message}")
        }
    }

    @JvmStatic
    external fun TProxyStartService(config_path: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
