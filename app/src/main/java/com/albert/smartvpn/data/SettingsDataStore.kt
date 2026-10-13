package com.albert.smartvpn.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "smartvpn_settings")

class SettingsDataStore(context: Context) {
    private val dataStore = context.dataStore

    companion object {
        val DNS_SERVER = stringPreferencesKey("dns_server")
        val SPLIT_TUNNEL = booleanPreferencesKey("split_tunnel")
    }

    val dnsServerFlow: Flow<String> = dataStore.data.map { it[DNS_SERVER] ?: "1.1.1.1" }
    val splitTunnelFlow: Flow<Boolean> = dataStore.data.map { it[SPLIT_TUNNEL] ?: false }

    suspend fun setDnsServer(dns: String) {
        dataStore.edit { it[DNS_SERVER] = dns }
    }
    
    suspend fun setSplitTunnel(enabled: Boolean) {
        dataStore.edit { it[SPLIT_TUNNEL] = enabled }
    }
}
