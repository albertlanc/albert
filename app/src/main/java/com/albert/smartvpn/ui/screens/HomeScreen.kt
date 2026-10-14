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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionState by VpnStateBridge.connectionState.collectAsState()
    val statusMessage by VpnStateBridge.statusMessage.collectAsState()
    val connectedSinceMs by VpnStateBridge.connectedSinceMs.collectAsState()
    val localSocksPort by VpnStateBridge.localSocksPort.collectAsState()
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
            "CONNECTED" -> Color(0xFF00E676)
            "CONNECTING" -> Color(0xFFFFAB00)
            else -> Color(0xFF00F0FF)
        },
        label = "AccentColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ReactorRings")
    val outerAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnected) 4500 else 10000, easing = LinearEasing)
        ),
        label = "OuterRing"
    )
    val innerAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnected) 3200 else 8000, easing = LinearEasing)
        ),
        label = "InnerRing"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF040711),
                        Color(0xFF0A1435),
                        Color(0xFF040711)
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, accentColor.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                .clickable { onToggleConnection() },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D193B))
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
                            uncheckedColor = Color(0xFF00F0FF),
                            checkmarkColor = Color(0xFF040711)
                        )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = when (connectionState) {
                                "CONNECTED" -> "TUNNEL CONNECTED"
                                "CONNECTING" -> "ESTABLISHING TUNNEL..."
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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E2F66), RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF09122C))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(148.dp)
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
                                color = Color(0xFF7C4DFF),
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
                                color = Color(0xFF00F0FF),
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
                            .size(84.dp)
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
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = formattedTimer,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                Text(
                    text = if (isConnected) "QUANTUM ENCRYPTED STREAM ACTIVE" else "SYSTEM STANDBY • READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    letterSpacing = 1.4.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F1D45))
                        .padding(vertical = 10.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("DOWNLOAD", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8))
                            Text(rxSpeedText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Divider(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp),
                        color = Color(0xFF1E2F66)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = null,
                            tint = Color(0xFF00F0FF),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("UPLOAD", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8))
                            Text(txSpeedText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryStatCard(
                title = "PROTOCOL ENGINE",
                value = activeProfile?.protocol ?: "READY",
                icon = Icons.Default.Bolt,
                accent = Color(0xFF00F0FF),
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                title = "SECURITY LAYER",
                value = activeProfile?.security?.uppercase() ?: "TLS / SSH",
                icon = Icons.Default.Security,
                accent = Color(0xFF7C4DFF),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryStatCard(
                title = "LOCAL SOCKS5",
                value = "127.0.0.1:$localSocksPort",
                icon = Icons.Default.Lan,
                accent = Color(0xFF00E676),
                modifier = Modifier.weight(1f)
            )
            TelemetryStatCard(
                title = "PRIMARY DNS",
                value = customDns1,
                icon = Icons.Default.Dns,
                accent = Color(0xFFFFAB00),
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1736))
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
                        tint = Color(0xFF00F0FF),
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
                            color = Color.White
                        )
                    }
                }
                Text(
                    text = "ENCLAVE LOCKED",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00E676),
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1B3D))
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
                color = Color.White
            )
        }
    }
}
