package com.albert.smartvpn.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.albert.smartvpn.engine.HotshareServer
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

@Composable
fun NetworkToolsScreen(
    initialToolPage: String = "PING",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activePage by remember(initialToolPage) { mutableStateOf(initialToolPage) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val toolTabs = listOf(
        "PING" to "Ping Checker",
        "GEOIP" to "My IP & Geo",
        "HOTSHARE" to "Wi-Fi Hotshare",
        "SSH_SETTINGS" to "Adv. SSH Settings",
        "PAYLOAD_GEN" to "Payload Gen",
        "SNI_HUNTER" to "SNI Hunter"
    )

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            toolTabs.forEach { (key, label) ->
                FilterChip(selected = activePage == key, onClick = { activePage = key }, label = { Text(label) })
            }
        }

        when (activePage) {
            "PING" -> {
                var targetHost by remember { mutableStateOf("8.8.8.8") }
                var targetPort by remember { mutableStateOf("443") }
                var output by remember { mutableStateOf("Ready to test TCP/ICMP latency...") }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Ping & TCP Port Latency Checker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = targetHost, onValueChange = { targetHost = it }, label = { Text("IP or Domain") }, singleLine = true, modifier = Modifier.weight(2f))
                            OutlinedTextField(value = targetPort, onValueChange = { targetPort = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Button(
                            onClick = {
                                output = "Pinging $targetHost..."
                                scope.launch {
                                    val portNum = targetPort.toIntOrNull() ?: 443
                                    output = withContext(Dispatchers.IO) {
                                        val sb = StringBuilder()
                                        for (seq in 1..4) {
                                            val t0 = System.currentTimeMillis()
                                            try {
                                                Socket().use { it.connect(InetSocketAddress(targetHost.trim(), portNum), 4000) }
                                                sb.appendLine("Seq #$seq -> $targetHost:$portNum: Connected (${System.currentTimeMillis() - t0} ms)")
                                            } catch (e: Exception) {
                                                sb.appendLine("Seq #$seq -> $targetHost:$portNum: Failed (${e.message})")
                                            }
                                        }
                                        sb.toString()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Execute Ping Test") }
                        Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            "GEOIP" -> {
                var geoResult by remember { mutableStateOf("Tap button below to fetch public IP, ISP, ASN, and Country...") }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("My IP & GeoIP Lookup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Button(
                            onClick = {
                                geoResult = "Querying GeoIP servers..."
                                scope.launch {
                                    geoResult = withContext(Dispatchers.IO) {
                                        try {
                                            val conn = URL("https://ipwho.is/").openConnection() as HttpURLConnection
                                            conn.connectTimeout = 7000
                                            conn.readTimeout = 7000
                                            conn.inputStream.bufferedReader().readText().replace(",", ",\n").replace("{", "").replace("}", "")
                                        } catch (e: Exception) { "GeoIP Lookup Failed: ${e.message}" }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Check My IP & Location") }
                        Text(geoResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            "HOTSHARE" -> {
                val hotshareEnabled by VpnStateBridge.hotshareEnabled.collectAsState()
                val detectedIp by VpnStateBridge.hotshareIp.collectAsState()
                val localSocksPort by VpnStateBridge.localSocksPort.collectAsState()
                val hotsharePort = HotshareServer.HOTSHARE_PORT

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Wi-Fi Hotspot VPN Share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (hotshareEnabled) "ACTIVE • Sharing VPN on $detectedIp:$hotsharePort" else "INACTIVE • Turn on Hotspot & tap Start below",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hotshareEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = hotshareEnabled,
                                onCheckedChange = { if (it) HotshareServer.startHotshare(localSocksPort) else HotshareServer.stopHotshare() }
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent().apply { setClassName("com.android.settings", "com.android.settings.TetherSettings") })
                                } catch (_: Throwable) {
                                    try { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Throwable) {}
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.WifiTethering, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("1. Open Phone Wi-Fi Hotspot Settings")
                        }

                        Button(
                            onClick = {
                                if (hotshareEnabled) HotshareServer.stopHotshare() else HotshareServer.startHotshare(localSocksPort)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (hotshareEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(if (hotshareEnabled) Icons.Default.StopCircle else Icons.Default.PlayArrow, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (hotshareEnabled) "STOP SHARING VPN CONNECTION" else "2. START SHARING VPN OVER HOTSPOT", fontWeight = FontWeight.Bold)
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("HOTSPOT PROXY DETAILS (HTTP & SOCKS5)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Text("Proxy Host / IP:  $detectedIp\nProxy Port:       $hotsharePort", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { VpnStateBridge.hotshareIp.value = HotshareServer.detectHotspotIpAddress() }) {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Refresh IP")
                                    }
                                    TextButton(onClick = {
                                        val shareText = "Connect to my Smart VPN Wi-Fi Hotspot!\nSet Wi-Fi Proxy to Manual:\nHost: $detectedIp\nPort: $hotsharePort"
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }, "Share Hotspot Details"))
                                    }) {
                                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Share Details")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "SSH_SETTINGS" -> {
                val sshCompression by VpnStateBridge.sshCompression.collectAsState()
                val autoReconnect by VpnStateBridge.autoReconnect.collectAsState()
                val pingInterval by VpnStateBridge.pingIntervalSec.collectAsState()
                val udpgwPort by VpnStateBridge.udpgwPort.collectAsState()
                val customDns1 by VpnStateBridge.customDns1.collectAsState()
                val customDns2 by VpnStateBridge.customDns2.collectAsState()

                var pingInput by remember(pingInterval) { mutableStateOf(pingInterval.toString()) }
                var udpgwInput by remember(udpgwPort) { mutableStateOf(udpgwPort.toString()) }
                var dns1Input by remember(customDns1) { mutableStateOf(customDns1) }
                var dns2Input by remember(customDns2) { mutableStateOf(customDns2) }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Advanced SSH & Tunnel Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("SSH Zlib Compression")
                            Switch(checked = sshCompression, onCheckedChange = { VpnStateBridge.sshCompression.value = it })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto-Reconnect on Drop")
                            Switch(checked = autoReconnect, onCheckedChange = { VpnStateBridge.autoReconnect.value = it })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = pingInput, onValueChange = { pingInput = it; it.toIntOrNull()?.let { v -> VpnStateBridge.pingIntervalSec.value = v } }, label = { Text("Ping Interval (s)") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = udpgwInput, onValueChange = { udpgwInput = it; it.toIntOrNull()?.let { v -> VpnStateBridge.udpgwPort.value = v } }, label = { Text("UDPGW Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = dns1Input, onValueChange = { dns1Input = it; VpnStateBridge.customDns1.value = it }, label = { Text("Primary DNS") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = dns2Input, onValueChange = { dns2Input = it; VpnStateBridge.customDns2.value = it }, label = { Text("Secondary DNS") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            "PAYLOAD_GEN" -> {
                var bugHost by remember { mutableStateOf("bree.omatescomobile.com") }
                var httpMethod by remember { mutableStateOf("GET") }
                var wsUpgrade by remember { mutableStateOf(true) }
                var splitMode by remember { mutableStateOf(false) }

                val generatedPayload = remember(bugHost, httpMethod, wsUpgrade, splitMode) {
                    buildString {
                        if (splitMode) append("[split]")
                        append("$httpMethod / HTTP/1.1[crlf]Host: $bugHost[crlf]")
                        if (wsUpgrade) append("Upgrade: websocket[crlf]Connection: Upgrade[crlf]")
                        else append("Connection: Keep-Alive[crlf]")
                        append("User-Agent: [ua][crlf][crlf]")
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("HTTP / WebSocket Payload Generator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(value = bugHost, onValueChange = { bugHost = it }, label = { Text("Host / SNI Domain") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("GET", "CONNECT", "POST", "HEAD").forEach { m ->
                                FilterChip(selected = httpMethod == m, onClick = { httpMethod = m }, label = { Text(m) })
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("WebSocket Upgrade")
                            Switch(checked = wsUpgrade, onCheckedChange = { wsUpgrade = it })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Packet Split ([split])")
                            Switch(checked = splitMode, onCheckedChange = { splitMode = it })
                        }
                        OutlinedTextField(value = generatedPayload, onValueChange = {}, readOnly = true, label = { Text("Generated Payload") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { clipboardManager.setText(AnnotatedString(generatedPayload)) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Generated Payload")
                        }
                    }
                }
            }

            "SNI_HUNTER" -> {
                var testDomain by remember { mutableStateOf("cloudflare.com") }
                var scanResult by remember { mutableStateOf("Enter a domain to inspect HTTP/SSL headers & CDN server...") }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("SNI / Bug Host Header Scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(value = testDomain, onValueChange = { testDomain = it }, label = { Text("Bug Host Domain") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                scanResult = "Scanning $testDomain..."
                                scope.launch {
                                    scanResult = withContext(Dispatchers.IO) {
                                        try {
                                            val clean = testDomain.trim().removePrefix("https://").removePrefix("http://")
                                            val conn = URL("https://$clean").openConnection() as HttpURLConnection
                                            conn.connectTimeout = 6000
                                            conn.readTimeout = 6000
                                            conn.requestMethod = "HEAD"
                                            "Host: $clean\nHTTP Status: ${conn.responseCode} ${conn.responseMessage}\nServer: ${conn.getHeaderField("Server") ?: "Unknown"}\nCF-RAY: ${conn.getHeaderField("CF-RAY") ?: "None"}"
                                        } catch (e: Exception) { "Scan Failed: ${e.message}" }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Check Bug Host Status") }
                        Text(scanResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
// === END OF FILE ===
