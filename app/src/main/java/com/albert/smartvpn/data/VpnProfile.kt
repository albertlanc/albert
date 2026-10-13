package com.albert.smartvpn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_profiles")
data class VpnProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val protocol: String, // vless, vmess, trojan, ssh
    val server: String,
    val port: Int,
    val uuid: String, // Used as password for Trojan/SSH
    val network: String, // tcp, ws, grpc
    val security: String, // none, tls, reality
    val sni: String = "",
    val pbk: String = "", // REALITY Public Key
    val sid: String = "", // REALITY Short ID
    val path: String = "",
    val flow: String = "", // e.g., xtls-rprx-vision
    val rawJson: String = "", // For custom raw JSON imports
    var isSelected: Boolean = false
)
