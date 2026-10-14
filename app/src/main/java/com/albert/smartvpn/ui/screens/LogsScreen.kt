package com.albert.smartvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Engine Logs") },
                actions = {
                    IconButton(
                        onClick = {
                            if (logs.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                                VpnStateBridge.addLog("Logs copied to clipboard.")
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
                    }
                    TextButton(
                        onClick = { VpnStateBridge.clearLogs() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Logs",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF050A18))
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Logs cleared. Ready for new connection events.",
                    color = Color(0xFF6E7681),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF050A18))
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs) { logLine ->
                    val logColor = when {
                        logLine.contains("Failed", ignoreCase = true) ||
                            logLine.contains("Error", ignoreCase = true) ||
                            logLine.contains("Rejected", ignoreCase = true) ||
                            logLine.contains("Blocked", ignoreCase = true) -> Color(0xFFFF5252)
                        logLine.contains("SUCCESS", ignoreCase = true) ||
                            logLine.contains("CONNECTED!", ignoreCase = true) -> Color(0xFF00E676)
                        logLine.contains("Connecting", ignoreCase = true) ||
                            logLine.contains("Authenticating", ignoreCase = true) -> Color(0xFFFFAB00)
                        else -> Color(0xFFB0C4DE)
                    }
                    Text(
                        text = logLine,
                        color = logColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
