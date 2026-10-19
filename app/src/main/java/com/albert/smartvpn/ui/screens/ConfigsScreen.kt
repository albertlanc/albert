package com.albert.smartvpn.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.ConfigCryptoHelper
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import com.albert.smartvpn.utils.VpnConfigParser
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(
    profiles: List<VpnProfile>,
    onSelectProfile: (VpnProfile) -> Unit,
    onDeleteProfile: (VpnProfile) -> Unit,
    onDuplicateProfile: (VpnProfile) -> Unit,
    onPingProfile: (VpnProfile) -> Unit,
    onPingAllProfiles: () -> Unit,
    onImportClipboard: (String) -> Unit,
    onSaveProfile: (VpnProfile) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val connectionState by VpnStateBridge.connectionState.collectAsState()
    val slowDnsBoostEnabled by VpnStateBridge.slowDnsBoostEnabled.collectAsState()
    val toastMessage by VpnStateBridge.toastMessage.collectAsState()
    val isTunnelBusy = connectionState != "DISCONNECTED"

    var showConfigDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var sharingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showConnectedEditBlockAlert by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Configurations") },
                    actions = {
                        IconButton(onClick = onPingAllProfiles) {
                            Icon(Icons.Default.Bolt, contentDescription = "Ping All", tint = Color(0xFFFFC107))
                        }
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add / Import Config")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { editingProfile = null; showConfigDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Config")
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .border(
                            width = 1.dp,
                            color = if (slowDnsBoostEnabled) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.RocketLaunch,
                                contentDescription = null,
                                tint = if (slowDnsBoostEnabled) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "SlowDNS Speed Boost",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (slowDnsBoostEnabled) "ACTIVE • Connected" else "STANDBY (OFF)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (slowDnsBoostEnabled) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(checked = slowDnsBoostEnabled, onCheckedChange = null, enabled = false)
                    }
                }

                if (profiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No configurations saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Tap + to create or import SSH, VLESS, VMess, Trojan, ShadowSocks, WireGuard, OpenVPN, or Hysteria2.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(profiles) { profile: VpnProfile ->
                            val isCurrentlyConnectedProfile = isTunnelBusy && profile.isSelected

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clickable {
                                        if (!isTunnelBusy) {
                                            onSelectProfile(profile)
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (profile.isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(14.dp)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            RadioButton(
                                                selected = profile.isSelected,
                                                onClick = { if (!isTunnelBusy) onSelectProfile(profile) },
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(profile.countryFlag, fontSize = 20.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = "${profile.protocol.uppercase()} • ${profile.server}:${profile.port}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (profile.lastPingMs > 0) Color(0xFF00E676).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
                                        ) {
                                            Text(
                                                text = if (profile.lastPingMs > 0) "${profile.lastPingMs}ms" else "---",
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (profile.lastPingMs > 0) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = { onPingProfile(profile) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Bolt, "Ping", tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = { sharingProfile = profile }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Share, "Share", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = { onDuplicateProfile(profile) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.CopyAll, "Duplicate", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                if (isCurrentlyConnectedProfile) showConnectedEditBlockAlert = true
                                                else { editingProfile = profile; showConfigDialog = true }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                "Edit",
                                                tint = if (isCurrentlyConnectedProfile) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else LocalContentColor.current,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (isCurrentlyConnectedProfile) showConnectedEditBlockAlert = true
                                                else onDeleteProfile(profile)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                "Delete",
                                                tint = if (isCurrentlyConnectedProfile) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else Color(0xFFFF5252),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showImportMenu) {
            AlertDialog(
                onDismissRequest = { showImportMenu = false },
                title = { Text("Import Configuration") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                showImportMenu = false
                                clipboardManager.getText()?.text?.let { onImportClipboard(it) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Import from Clipboard")
                        }
                        TextButton(
                            onClick = {
                                showImportMenu = false
                                editingProfile = null
                                showConfigDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Manual Configuration")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showImportMenu = false }) { Text("Cancel") }
                }
            )
        }

        if (toastMessage.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 8.dp
                ) {
                    Text(
                        text = toastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }

    if (showConnectedEditBlockAlert) {
        AlertDialog(
            onDismissRequest = { showConnectedEditBlockAlert = false },
            title = { Text("Profile Connected") },
            text = { Text("You cannot edit or delete an active configuration while the VPN tunnel is connected. Please disconnect first.") },
            confirmButton = { Button(onClick = { showConnectedEditBlockAlert = false }) { Text("OK") } }
        )
    }

    if (showConfigDialog) {
        ProtocolConfigDialog(
            initialProfile = editingProfile,
            onDismiss = { showConfigDialog = false },
            onSave = { onSaveProfile(it); showConfigDialog = false }
        )
    }

    if (sharingProfile != null) {
        ShareAndLockDialog(
            profile = sharingProfile!!,
            profilesList = profiles,
            onDismiss = { sharingProfile = null },
            onUpdateLockState = { onSaveProfile(it); sharingProfile = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolConfigDialog(
    initialProfile: VpnProfile?,
    onDismiss: () -> Unit,
    onSave: (VpnProfile) -> Unit
) {
    val protocolModes = listOf(
        "SSH-DIRECT" to "SSH Direct",
        "SSH-PROXY" to "SSH Proxy",
        "SSH-PAYLOAD" to "SSH Payload",
        "SSH-TLS" to "SSH TLS",
        "SSH-DNSTT" to "SSH DNSTT (SlowDNS)",
        "VMESS" to "VMess",
        "VLESS" to "VLESS",
        "TROJAN" to "Trojan",
        "SHADOWSOCKS" to "ShadowSocks",
        "WIREGUARD" to "WireGuard",
        "OPENVPN" to "OpenVPN",
        "HYSTERIA2" to "Hysteria2"
    )

    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(initialProfile?.protocol?.uppercase() ?: "SSH-DIRECT") }

    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var server by remember { mutableStateOf(initialProfile?.server ?: "") }
    var port by remember { mutableStateOf((initialProfile?.port ?: 443).toString()) }
    var uuid by remember { mutableStateOf(initialProfile?.uuid ?: "") }
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }
    var payload by remember { mutableStateOf(initialProfile?.payload ?: "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(if (initialProfile == null) "New Configuration" else "Edit Configuration") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) } },
                    actions = {
                        Button(
                            onClick = {
                                val resolvedPort = port.toIntOrNull() ?: 443
                                onSave(
                                    VpnProfile(
                                        id = initialProfile?.id ?: 0,
                                        name = name.ifBlank { "$selectedProtocol-$server" },
                                        protocol = selectedProtocol,
                                        server = server.ifBlank { sni },
                                        port = resolvedPort,
                                        uuid = uuid,
                                        sni = sni,
                                        payload = payload,
                                        isSelected = true
                                    )
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("SAVE CONFIGURATION")
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { dropdownExpanded = !dropdownExpanded }) {
                        OutlinedTextField(
                            value = selectedProtocol,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Protocol") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                            protocolModes.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { selectedProtocol = code; dropdownExpanded = false }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Server IP / Host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Server Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (selectedProtocol in listOf("VMESS", "VLESS", "TROJAN", "SHADOWSOCKS", "HYSTERIA2")) {
                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("Password / UUID") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = sni,
                        onValueChange = { sni = it },
                        label = { Text("SNI / Bug Host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text("Payload / Custom Headers") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun ShareAndLockDialog(
    profile: VpnProfile,
    profilesList: List<VpnProfile>,
    onDismiss: () -> Unit,
    onUpdateLockState: (VpnProfile) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val myDeviceId = remember { VpnStateBridge.getDeviceHardwareId(context) }

    var lockConfig by remember { mutableStateOf(profile.isLocked) }
    var passphrase by remember { mutableStateOf(profile.lockPassphrase) }
    var targetDeviceId by remember { mutableStateOf(profile.lockedDeviceId.ifBlank { myDeviceId }) }

    val encryptedUri = remember(profile, lockConfig, passphrase, targetDeviceId) {
        ConfigCryptoHelper.exportProfileUri(profile, lockConfig, passphrase, targetDeviceId)
    }
    val bundleUri = remember(profilesList) {
        ConfigCryptoHelper.exportLoadBalanceBundle(profilesList)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${profile.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(encryptedUri))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy URI")
                }
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(bundleUri))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Load Balance Bundle")
                }
                Divider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Lock Configuration Settings", fontWeight = FontWeight.Bold)
                    Switch(
                        checked = lockConfig,
                        onCheckedChange = {
                            lockConfig = it
                            onUpdateLockState(profile.copy(isLocked = it, lockPassphrase = passphrase, lockedDeviceId = targetDeviceId))
                        }
                    )
                }
                if (lockConfig) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            onUpdateLockState(profile.copy(isLocked = true, lockPassphrase = it, lockedDeviceId = targetDeviceId))
                        },
                        label = { Text("Password (Optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = targetDeviceId,
                        onValueChange = {
                            targetDeviceId = it.trim().uppercase()
                            onUpdateLockState(profile.copy(isLocked = true, lockPassphrase = passphrase, lockedDeviceId = targetDeviceId))
                        },
                        label = { Text("Target HWID(s) (Comma separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val cleanName = profile.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    File(context.cacheDir, "$cleanName.svpn").writeText(encryptedUri)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${profile.name}.svpn")
                        putExtra(Intent.EXTRA_TEXT, encryptedUri)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Save as .svpn File"))
                } catch (_: Throwable) {}
            }) {
                Text("Save as .svpn File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
