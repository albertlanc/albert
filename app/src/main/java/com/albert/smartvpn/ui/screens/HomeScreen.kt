package com.albert.smartvpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.albert.smartvpn.data.VpnProfile

@Composable
fun HomeScreen(
    isConnected: Boolean,
    activeProfile: VpnProfile?,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "btnColor"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Active Profile Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = activeProfile?.name ?: "No Profile Selected",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (activeProfile != null) "${activeProfile.protocol.uppercase()} • ${activeProfile.server}:${activeProfile.port}" else "Select or import a server configuration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Connection Button
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = onToggleConnection,
                modifier = Modifier.size(140.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = buttonColor)
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Toggle VPN",
                    modifier = Modifier.size(64.dp),
                    tint = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isConnected) "CONNECTED" else "DISCONNECTED",
                style = MaterialTheme.typography.titleLarge
            )
        }

        // Stats Placeholder
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Status", style = MaterialTheme.typography.bodySmall)
                Text(text = if (isConnected) "Protected" else "Idle", style = MaterialTheme.typography.bodyLarge)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Security", style = MaterialTheme.typography.bodySmall)
                Text(text = activeProfile?.security?.uppercase() ?: "NONE", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
