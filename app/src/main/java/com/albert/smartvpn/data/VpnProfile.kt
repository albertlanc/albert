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
    val network: String = "tcp",
    val security: String = "none",
    val sni: String = "",
    val pbk: String = "",
    val sid: String = "",
    val path: String = "",
    val flow: String = "",
    val rawJson: String = "",
    val isSelected: Boolean = false,
    // Advanced Tunnel / Edutecz Fields
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
    val enableWakeLock: Boolean = true
)
