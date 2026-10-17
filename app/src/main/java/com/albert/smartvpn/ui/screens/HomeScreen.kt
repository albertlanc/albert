package com.albert.smartvpn.ui.screens

import android.net.TrafficStats
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    isConnected: Boolean,
    activeProfile: VpnProfile?,
    onToggleConnection: () -> Unit,
    onPingClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionState by VpnStateBridge.connectionState.collectAsState()
    val statusMessage by VpnStateBridge.statusMessage.collectAsState()
    val connectedSinceMs by VpnStateBridge.connectedSinceMs.collectAsState()
    val pingResultText by VpnStateBridge.pingResultText.collectAsState()
    val isPinging by VpnStateBridge.isPinging.collectAsState()
    val customDns1 by VpnStateBridge.customDns1.collectAsState()
    val deviceId = remember { VpnStateBridge.getDeviceHardwareId(context) }

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var rxSpeedText by remember { mutableStateOf("0.0 KB/s") }
    var txSpeedText by remember { mutableStateOf("0.0 KB/s") }

    LaunchedEffect(isConnected, connectedSinceMs) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        while (true) {
            if (isConnected && connectedSinceMs > 0L) {
                elapsedSeconds = (System.currentTimeMillis() - connectedSinceMs) / 1000L
                val nowRx = TrafficStats.getTotalRxBytes()
                val nowTx = TrafficStats.getTotalTxBytes()
                val deltaRx = (nowRx - lastRx).coerceAtLeast(0L)
                val deltaTx = (nowTx - lastTx).coerceAtLeast(0L)
                lastRx = nowRx
                lastTx = nowTx
                rxSpeedText = formatRate(deltaRx)
                txSpeedText = formatRate(deltaTx)
            } else {
                elapsedSeconds = 0L
                rxSpeedText = "0.0 KB/s"
                txSpeedText = "0.0 KB/s"
                lastRx = TrafficStats.getTotalRxBytes()
                lastTx = TrafficStats.getTotalTxBytes()
            }
            delay(1000L)
        }
    }

    val formattedTimer = remember(elapsedSeconds) {
        val hrs = elapsedSeconds / 3600
        val mins = (elapsedSeconds % 3600) / 60
        val secs = elapsedSeconds % 60
        "%02d:%02d:%02d".format(hrs, mins, secs)
    }

    val isChecked = connectionState == "CONNECTED" || connectionState == "CONNECTING"

    val accentColor by animateColorAsState(
        targetValue = when (connectionState) {
            "CONNECTED" -> MaterialTheme.colorScheme.secondary
            "CONNECTING" -> Color(0xFFFF9800)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "AccentColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ReactorRings")
    val outerAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnected) 4000 else 10000, easing = LinearEasing)
        ),
        label = "OuterRing"
    )
    val innerAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnected) 2800 else 8000, easing = LinearEasing)
        ),
        label = "InnerRing"
    )

    val protocolEngineDisplay = remember(activeProfile) {
        if (activeProfile == null) "READY"
        else "${activeProfile.protocol.uppercase()} (${activeProfile.port})"
    }

    val securityLayerDisplay = remember(activeProfile) {
        if (activeProfile == null) {
            "SECURE / TLS"
        } else {
            val proto = activeProfile.protocol.uppercase()
            val sec = activeProfile.security.uppercase()
            val net = activeProfile.network.uppercase()
            when {
                proto in listOf("VLESS", "VMESS", "TROJAN") -> {
                    val tlsLabel = if (activeProfile.port == 80 || sec == "NONE") "NO-TLS (80)" else "TLS ($net)"
                    "$proto • $tlsLabel"
                }
                proto == "SLOWDNS" -> "SLOWDNS • DNSTT"
                proto.contains("SSH") -> "$proto • ${if (sec == "TLS" || activeProfile.port == 443) "TLSv1.3" else "DIRECT"}"
                proto == "WIREGUARD" -> "WIREGUARD • NOISE"
                proto == "HYSTERIA2" -> "HYSTERIA2 • QUIC"
                else -> "$proto • $sec"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. TOP CONNECT / DISCONNECT CHECKBOX COMMAND DECK
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, accentColor.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                .clickable { onToggleConnection() },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onToggleConnection() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = accentColor,
                            uncheckedColor = MaterialTheme.colorScheme.primary,
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = when (connectionState) {
                                "CONNECTED" -> "TUNNEL CONNECTED"
                                "CONNECTING" -> "CONNECTING..."
                                else -> "CONNECT VPN TUNNEL"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Status: $statusMessage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (connectionState == "CONNECTING") {
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.VerifiedUser else Icons.Default.PowerSettingsNew,
                            contentDescription = "Toggle Tunnel",
                            tint = accentColor
                        )
                    }
                }
            }
        }

        // 2. QUANTUM ORBITAL REACTOR CORE + PING BUTTON BENEATH STATUS + LIVE SPEEDOMETER
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val tertiaryColor = MaterialTheme.colorScheme.tertiary
                val primaryColor = MaterialTheme.colorScheme.primary

                Box(
                    modifier = Modifier
                        .size(138.dp)
                        .clickable { onToggleConnection() },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        rotate(outerAngle) {
                            drawArc(
                                color = accentColor,
                                startAngle = 0f,
                                sweepAngle = 110f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = accentColor.copy(alpha = 0.5f),
                                startAngle = 180f,
                                sweepAngle = 110f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        rotate(innerAngle) {
                            drawArc(
                                color = tertiaryColor,
                                startAngle = 45f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(16.dp.toPx(), 16.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - 32.dp.toPx(),
                                    size.height - 32.dp.toPx()
                                ),
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = primaryColor,
                                startAngle = 225f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(16.dp.toPx(), 16.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - 32.dp.toPx(),
                                    size.height - 32.dp.toPx()
                                ),
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.35f),
                                        Color.Transparent
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.Lock else Icons.Default.Shield,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = formattedTimer,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = if (isConnected) "TUNNEL STREAM ACTIVE • CONNECTED" else "SYSTEM STANDBY • READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    letterSpacing = 1.3.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // PING BUTTON DIRECTLY BENEATH STATUS (ENABLED ONLY WHEN CONNECTED)
                Button(
                    onClick = onPingClick,
                    enabled = isConnected && !isPinging,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Ping Tunnel",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConnected) {
                            if (isPinging) "Pinging Connected Server..." else pingResultText
                        } else {
                            "PING LATENCY & SPEED (CONNECT VPN FIRST)"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 10.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("DOWNLOAD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                rxSpeedText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Divider(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("UPLOAD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                txSpeedText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 3. TELEMETRY & ENGINE STATS GRID
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryStatCard(
                title = "PROTOCOL ENGINE",
                value = protocolEngineDisplay,
                icon = Icons.Default.Bolt,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                title = "SECURITY LAYER",
                value = securityLayerDisplay,
                icon = Icons.Default.Security,
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryStatCard(
                title = "TUNNEL STREAM",
                value = "OPTIMIZED",
                icon = Icons.Default.RocketLaunch,
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                title = "PRIMARY DNS",
                value = customDns1,
                icon = Icons.Default.Dns,
                accent = Color(0xFFFF9800),
                modifier = Modifier.weight(1f)
            )
        }

        // 4. DEVICE HARDWARE ID BADGE
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Hardware Device ID (HWID)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = deviceId,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Text(
                    text = "v2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatRate(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576L -> "%.2f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024L -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

@Composable
private fun TelemetryStatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
// === END OF FILE 12 ===
