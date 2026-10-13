package com.albert.smartvpn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.albert.smartvpn.data.* // Wildcard ensures Profile is found

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(
    profiles: List<Profile>,
    onSelectProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onImportClipboard: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Configurations") },
                actions = {
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { onImportClipboard(it) }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Import from Clipboard")
                    }
                }
            )
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No profiles found. Paste a config to import.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Explicitly naming the parameter fixes the Int type mismatch
                items(items = profiles) { profile ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable { onSelectProfile(profile) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = profile.name, style = MaterialTheme.typography.titleMedium)
                                Text(text = profile.protocol, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onDeleteProfile(profile) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Profile")
                            }
                        }
                    }
                }
            }
        }
    }
}
