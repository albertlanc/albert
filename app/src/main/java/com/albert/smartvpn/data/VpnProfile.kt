package com.albert.smartvpn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_profiles")
data class VpnProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val uuid: String = "",
    val network: String = "ws",
    val security: String = "tls",
    val sni: String = "",
    val pbk: String = "",
    val sid: String = "",
    val path: String = "/",
    val flow: String = "",
    val rawJson: String = "",
    val isSelected: Boolean = false,
    // SSH, Proxy, Payload & SlowDNS Fields
    val sshUser: String = "",
    val sshPass: String = "",
    val payload: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val slowDnsDomain: String = "",
    val slowDnsPubKey: String = "",
    val slowDnsServerIp: String = "8.8.8.8",
    val udpgwPort: Int = 7300,
    val customDns: String = "1.1.1.1",
    val enableCompression: Boolean = true,
    val autoReconnect: Boolean = true,
    val enableWakeLock: Boolean = true,
    // WireGuard Fields
    val wgPrivateKey: String = "",
    val wgPublicKey: String = "",
    val wgPresharedKey: String = "",
    val wgAddress: String = "10.0.0.2/32",
    val wgMtu: Int = 1420,
    // Hysteria2 Fields
    val hy2Password: String = "",
    val hy2Obfs: String = "",
    val hy2ObfsPassword: String = "",
    val hy2UpMbps: Int = 50,
    val hy2DownMbps: Int = 100,
    // Config Lock & HWID Security
    val isLocked: Boolean = false,
    val lockPassphrase: String = "",
    val lockedDeviceId: String = "",
    val configNote: String = ""
)
