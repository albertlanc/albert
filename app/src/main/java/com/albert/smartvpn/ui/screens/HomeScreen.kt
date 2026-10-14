package com.albert.smartvpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge

@Composable
fun HomeScreen(
    isConnected: Boolean,
    activeProfile: VpnProfile?,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by VpnStateBridge.connectionState.collectAsState()
    val statusMessage by VpnStateBridge.statusMessage.collectAsState()

    val buttonColor by animateColorAsState(
        targetValue = when (connectionState) {
            "CONNECTED" -> MaterialTheme.colorScheme.primary
            "CONNECTING" -> Color(0xFFFFAB00)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "ButtonColor"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = activeProfile?.name ?: "No Profile Selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (activeProfile != null) {
                            "${activeProfile.protocol} • ${activeProfile.server}:${activeProfile.port}"
                        } else {
                            "Select or import a server configuration"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onToggleConnection,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                modifier = Modifier.size(160.dp)
            ) {
                if (connectionState == "CONNECTING") {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(56.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Connect",
                        modifier = Modifier.size(64.dp),
                        tint = if (isConnected) Color.Black else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = when (connectionState) {
                    "CONNECTED" -> "CONNECTED"
                    "CONNECTING" -> "CONNECTING..."
                    else -> "DISCONNECTED"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = when (connectionState) {
                    "CONNECTED" -> MaterialTheme.colorScheme.primary
                    "CONNECTING" -> Color(0xFFFFAB00)
                    else -> MaterialTheme.colorScheme.onBackground
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Status", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Security", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = activeProfile?.security?.uppercase() ?: "NONE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
