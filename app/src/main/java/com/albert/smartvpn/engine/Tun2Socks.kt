package com.albert.smartvpn.engine

import android.util.Log

object Tun2Socks {
    init {
        try {
            System.loadLibrary("hev-socks5-tunnel") // Loads libhev-socks5-tunnel.so
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Tun2Socks", "Native hev-socks5-tunnel library not found: ${e.message}")
        }
    }

    /**
     * Starts the tunnel using the File Descriptor (FD) from VpnService.
     * @param tunFd The file descriptor of the TUN interface.
     * @param socksPort The local SOCKS port Xray is listening on (e.g., 10808).
     * @param socksAddress The local address, usually "127.0.0.1".
     */
    external fun startTunnel(tunFd: Int, socksAddress: String, socksPort: Int): Boolean

    /**
     * Stops the packet forwarding process.
     */
    external fun stopTunnel(): Boolean
}
