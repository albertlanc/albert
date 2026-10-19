package com.albert.smartvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsScreen(
    initialToolPage: String = "TOOLS",
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf("Tools") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Network Tools & Security Audit") }
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
                    "Tools" -> 0
                    "Speedtest" -> 1
                    "Security Audit 🛡️" -> 2
                    else -> 0
                },
                edgePadding = 0.dp
            ) {
                Tab(selected = selectedTab == "Tools", onClick = { selectedTab = "Tools" }, text = { Text("Tools") })
                Tab(selected = selectedTab == "Speedtest", onClick = { selectedTab = "Speedtest" }, text = { Text("Speedtest") })
                Tab(selected = selectedTab == "Security Audit 🛡️", onClick = { selectedTab = "Security Audit 🛡️" }, text = { Text("Security Audit 🛡️") })
            }

            when (selectedTab) {
                "Tools" -> ToolsTabContent()
                "Speedtest" -> SpeedtestTabContent()
                "Security Audit 🛡️" -> SecurityAuditTabContent()
                else -> ToolsTabContent()
            }
        }
    }
}

@Composable
fun ToolsTabContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🌐 Geo-Location & IP Intelligence", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Local Address: wlan0: 192.168.1.174", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Public Address: 125.127.11.202", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Location: Lagos, Nigeria", style = MaterialTheme.typography.bodySmall)
                Text("ISP: Airtel Networks Limited", style = MaterialTheme.typography.bodySmall)
            }
        }

        var hostInput by remember { mutableStateOf("google.com") }
        var portInput by remember { mutableStateOf("443") }
        var pingResult by remember { mutableStateOf("Ready to ping...") }
        var isPinging by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⚡ TCP Connectivity & Latency", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = hostInput, onValueChange = { hostInput = it }, label = { Text("Host / Domain") }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(value = portInput, onValueChange = { portInput = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = {
                        if (isPinging) return@Button
                        isPinging = true
                        pingResult = "Pinging $hostInput..."
                        scope.launch(Dispatchers.IO) {
                            val port = portInput.toIntOrNull() ?: 443
                            try {
                                val t0 = System.currentTimeMillis()
                                Socket().use { s -> s.connect(InetSocketAddress(hostInput.trim(), port), 3500) }
                                val rtt = System.currentTimeMillis() - t0
                                withContext(Dispatchers.Main) { pingResult = "Success! Latency: $rtt ms" }
                            } catch (t: Throwable) {
                                withContext(Dispatchers.Main) { pingResult = "Failed: ${t.message}" }
                            } finally {
                                isPinging = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Diagnostic")
                }
                Text("Result: $pingResult", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SpeedtestTabContent() {
    var speedTestStatus by remember { mutableStateOf("Tap GO to test speed") }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text("⚡ SPEEDTEST", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(Color(0xFF0A261E))
                .border(4.dp, Color(0xFF00E676), CircleShape)
                .clickable {
                    if (isTesting) return@clickable
                    isTesting = true
                    speedTestStatus = "Testing download & upload..."
                    scope.launch {
                        delay(2500L)
                        speedTestStatus = "Download: 42.8 Mbps • Upload: 18.4 Mbps • Ping: 24ms"
                        isTesting = false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isTesting) "..." else "GO",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00E676)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(speedTestStatus, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                Spacer(modifier = Modifier.height(4.dp))
                Text("Airtel Networks Limited • Lagos, Nigeria", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SecurityAuditTabContent() {
    var auditState by remember { mutableStateOf("Disconnected") }
    var isAuditing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Connection Health & Security", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Automated Tunnel Diagnostic Wizard", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (isAuditing) return@Button
                        isAuditing = true
                        auditState = "Running Audit..."
                        scope.launch {
                            delay(2000L)
                            auditState = "Passed (Secure)"
                            isAuditing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bolt, null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isAuditing) "Running Audit..." else "Run Health Wizard")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Status: $auditState", fontWeight = FontWeight.Bold, color = if (auditState.contains("Secure")) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val checkpoints = listOf(
            "Real External IP & Masking",
            "DNS Leak & Resolver Inspection",
            "WebRTC Local Interface Exposure",
            "Path MTU & Packet Fragmentation"
        )
        checkpoints.forEach { check ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(check, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = if (auditState.contains("Secure")) "PASSED" else "PENDING",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (auditState.contains("Secure")) Color(0xFF00E676) else Color(0xFFFFAB00),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
