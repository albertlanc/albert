package com.albert.smartvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Scanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsScreen(
    initialToolPage: String = "PING",
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(initialToolPage) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Network Tools") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ScrollableTabRow(
                selectedTabIndex = when (selectedTab) {
                    "PING" -> 0
                    "GEOIP" -> 1
                    "HOTSHARE" -> 2
                    "SSH_SETTINGS" -> 3
                    else -> 0
                },
                edgePadding = 0.dp
            ) {
                Tab(selected = selectedTab == "PING", onClick = { selectedTab = "PING" }, text = { Text("Ping") })
                Tab(selected = selectedTab == "GEOIP", onClick = { selectedTab = "GEOIP" }, text = { Text("GeoIP") })
                Tab(selected = selectedTab == "HOTSHARE", onClick = { selectedTab = "HOTSHARE" }, text = { Text("Hotshare") })
                Tab(selected = selectedTab == "SSH_SETTINGS", onClick = { selectedTab = "SSH_SETTINGS" }, text = { Text("Settings") })
            }

            when (selectedTab) {
                "PING" -> PingToolContent()
                "GEOIP" -> GeoIpToolContent()
                "HOTSHARE" -> HotshareToolContent()
                "SSH_SETTINGS" -> SettingsToolContent()
                else -> PingToolContent()
            }
        }
    }
}

@Composable
fun PingToolContent() {
    var hostInput by remember { mutableStateOf("google.com") }
    var portInput by remember { mutableStateOf("443") }
    var resultText by remember { mutableStateOf("Ready to test latency.") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TCP Host & Port Latency Checker", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("Host / IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = portInput,
                onValueChange = { portInput = it },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (isLoading) return@Button
                    isLoading = true
                    resultText = "Pinging $hostInput..."
                    scope.launch(Dispatchers.IO) {
                        val port = portInput.toIntOrNull() ?: 443
                        try {
                            val t0 = System.currentTimeMillis()
                            Socket().use { s ->
                                s.connect(InetSocketAddress(hostInput.trim(), port), 4000)
                            }
                            val rtt = System.currentTimeMillis() - t0
                            withContext(Dispatchers.Main) {
                                resultText = "Success! Latency: $rtt ms ($hostInput:$port)"
                            }
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                resultText = "Connection failed: ${t.message}"
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Test Latency")
                }
            }
            Divider()
            Text(
                text = resultText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun GeoIpToolContent() {
    var ipInfo by remember { mutableStateOf("Tap button to fetch public IP information.") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Public IP & GeoIP Lookup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Button(
                onClick = {
                    if (isLoading) return@Button
                    isLoading = true
                    ipInfo = "Fetching IP details..."
                    scope.launch(Dispatchers.IO) {
                        try {
                            val url = URL("https://ipapi.co/json/")
                            val scanner = Scanner(url.openStream(), "UTF-8").useDelimiter("\\A")
                            val response = if (scanner.hasNext()) scanner.next() else "No data"
                            withContext(Dispatchers.Main) {
                                ipInfo = response
                            }
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                ipInfo = "Failed to fetch GeoIP: ${t.message}"
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Text("Lookup My IP Location")
            }
            Divider()
            Text(
                text = ipInfo,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HotshareToolContent() {
    val hotshareEnabled by VpnStateBridge.hotshareEnabled.collectAsState()
    val hotshareIp by VpnStateBridge.hotshareIp.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wi-Fi Hotshare (Tether VPN)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Share your active VPN connection with other devices connected to your mobile hotspot via HTTP/SOCKS5 proxy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable Hotshare Proxy", fontWeight = FontWeight.Bold)
                Switch(
                    checked = hotshareEnabled,
                    onCheckedChange = { VpnStateBridge.hotshareEnabled.value = it }
                )
            }
            if (hotshareEnabled) {
                Divider()
                Text(
                    text = "Proxy Server Active:\nIP: $hotshareIp\nPort: 10808 (SOCKS5) / 8080 (HTTP)",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun SettingsToolContent() {
    val sshCompression by VpnStateBridge.sshCompression.collectAsState()
    val autoReconnect by VpnStateBridge.autoReconnect.collectAsState()
    val customDns1 by VpnStateBridge.customDns1.collectAsState()
    val customDns2 by VpnStateBridge.customDns2.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Advanced Tunnel Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("SSH Compression")
                Switch(checked = sshCompression, onCheckedChange = { VpnStateBridge.sshCompression.value = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Auto Reconnect")
                Switch(checked = autoReconnect, onCheckedChange = { VpnStateBridge.autoReconnect.value = it })
            }

            OutlinedTextField(
                value = customDns1,
                onValueChange = { VpnStateBridge.customDns1.value = it },
                label = { Text("Primary Custom DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = customDns2,
                onValueChange = { VpnStateBridge.customDns2.value = it },
                label = { Text("Secondary Custom DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
// === END OF FILE ===
