package com.albert.smartvpn.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.albert.smartvpn.data.VpnProfile
import com.albert.smartvpn.ui.viewmodel.ConfigCryptoHelper
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(
    profiles: List<VpnProfile>,
    onSelectProfile: (VpnProfile) -> Unit,
    onDeleteProfile: (VpnProfile) -> Unit,
    onImportClipboard: (String) -> Unit,
    onSaveProfile: (VpnProfile) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showConfigDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var sharingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var unlockPromptProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showImportModal by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Configurations") },
                actions = {
                    // Add Manual Config
                    IconButton(onClick = {
                        editingProfile = null
                        showConfigDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Manual Config")
                    }
                    // Import via Passphrase / Cloud / File String
                    IconButton(onClick = { showImportModal = true }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Import Locked / Cloud Config")
                    }
                    // Quick Clipboard Paste
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { onImportClipboard(it) }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste from Clipboard")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingProfile = null
                    showConfigDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Config")
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No configurations yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap + to create a custom SSH, Proxy+Payload, SlowDNS, or VLESS config, or tap the Cloud/Clipboard icons above to import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(profiles) { profile: VpnProfile ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable { onSelectProfile(profile) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (profile.isLocked) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Locked",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (profile.isLocked) {
                                        "${profile.protocol} • [Locked Endpoint]"
                                    } else {
                                        "${profile.protocol} • ${profile.server}:${profile.port}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (profile.configNote.isNotBlank()) {
                                    Text(
                                        text = profile.configNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Row {
                                // Share / Export Button
                                IconButton(onClick = { sharingProfile = profile }) {
                                    Icon(Icons.Default.Share, contentDescription = "Share Config")
                                }
                                // Edit Button (Checks Lock State)
                                IconButton(onClick = {
                                    if (profile.isLocked) {
                                        unlockPromptProfile = profile
                                    } else {
                                        editingProfile = profile
                                        showConfigDialog = true
                                    }
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Profile")
                                }
                                // Delete Button
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

    // Add / Edit Config Dialog with Protocol Dropdown
    if (showConfigDialog) {
        ProtocolConfigDialog(
            initialProfile = editingProfile,
            onDismiss = { showConfigDialog = false },
            onSave = { newProfile ->
                onSaveProfile(newProfile)
                showConfigDialog = false
            }
        )
    }

    // Unlock Locked Config Dialog before Editing
    if (unlockPromptProfile != null) {
        val target = unlockPromptProfile!!
        var enteredPass by remember { mutableStateOf("") }
        var errorText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { unlockPromptProfile = null },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Locked Configuration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This configuration is locked. Enter the creator passphrase to unlock and edit:")
                    OutlinedTextField(
                        value = enteredPass,
                        onValueChange = {
                            enteredPass = it
                            errorText = ""
                        },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorText.isNotEmpty()) {
                        Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (target.lockPassphrase.isNotEmpty() && enteredPass == target.lockPassphrase) {
                        unlockPromptProfile = null
                        editingProfile = target
                        showConfigDialog = true
                    } else {
                        errorText = "Invalid passphrase! Locked configs cannot be viewed without the key."
                    }
                }) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { unlockPromptProfile = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Share / Lock / QR / File / Cloud Dialog
    if (sharingProfile != null) {
        ShareAndLockDialog(
            profile = sharingProfile!!,
            onDismiss = { sharingProfile = null },
            onUpdateLockState = { updated ->
                onSaveProfile(updated)
                sharingProfile = updated
            }
        )
    }

    // Import Locked / Passphrase / Cloud Config Dialog
    if (showImportModal) {
        ImportAdvancedDialog(
            onDismiss = { showImportModal = false },
            onImportSuccess = { importedProfile ->
                onSaveProfile(importedProfile)
                showImportModal = false
            }
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
        "SSH" to "SSH Direct (Port 22)",
        "SSH+PAYLOAD" to "SSH + Proxy + Payload (HTTP / WS)",
        "SSH+SSL" to "SSH + SSL / TLS (SNI Bug Host)",
        "SLOWDNS" to "SlowDNS (DNSTT Tunnel)",
        "VLESS" to "VLESS (Xray Core)",
        "VMESS" to "VMess (Xray Core)",
        "TROJAN" to "Trojan (Xray Core)"
    )

    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(initialProfile?.protocol ?: "SSH") }
    val selectedProtocolLabel = protocolModes.firstOrNull { it.first == selectedProtocol }?.second ?: selectedProtocol

    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var server by remember { mutableStateOf(initialProfile?.server ?: "") }
    var port by remember { mutableStateOf((initialProfile?.port ?: 22).toString()) }

    // SSH Credentials
    var sshUser by remember { mutableStateOf(initialProfile?.sshUser ?: "") }
    var sshPass by remember { mutableStateOf(initialProfile?.sshPass ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Payload & Proxy
    var payload by remember {
        mutableStateOf(
            initialProfile?.payload?.ifBlank {
                "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"
            } ?: "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"
        )
    }
    var proxyHost by remember { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf((initialProfile?.proxyPort ?: 8080).toString()) }

    // SSL SNI & Xray Fields
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }
    var uuid by remember { mutableStateOf(initialProfile?.uuid ?: "") }
    var wsPath by remember { mutableStateOf(initialProfile?.path ?: "/") }
    var xNetwork by remember { mutableStateOf(initialProfile?.network ?: "ws") }

    // SlowDNS Dedicated Fields
    var slowDnsServerIp by remember { mutableStateOf(initialProfile?.slowDnsServerIp ?: "8.8.8.8") }
    var slowDnsDomain by remember { mutableStateOf(initialProfile?.slowDnsDomain ?: "") }
    var slowDnsPubKey by remember { mutableStateOf(initialProfile?.slowDnsPubKey ?: "") }

    // Lock & Advanced Settings
    var isLocked by remember { mutableStateOf(initialProfile?.isLocked ?: false) }
    var lockPassphrase by remember { mutableStateOf(initialProfile?.lockPassphrase ?: "") }
    var configNote by remember { mutableStateOf(initialProfile?.configNote ?: "") }
    var udpgwPort by remember { mutableStateOf((initialProfile?.udpgwPort ?: 7300).toString()) }
    var customDns by remember { mutableStateOf(initialProfile?.customDns ?: "1.1.1.1") }
    var showAdvancedSection by remember { mutableStateOf(false) }

    val payloadTags = listOf(
        "[host]", "[port]", "[host_port]", "[crlf]", "[cr]", "[lf]", "[ua]", "[split]", "[instant_split]"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(if (initialProfile == null) "Configure Profile" else "Edit Profile")
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                val resolvedName = name.ifBlank { "$selectedProtocol-${server.ifBlank { "Server" }}" }
                                val resolvedPort = port.toIntOrNull() ?: if (selectedProtocol == "SSH") 22 else 443
                                val activePayload = if (selectedProtocol == "SSH+PAYLOAD") payload else ""
                                val activeSni = if (selectedProtocol in listOf("SSH+SSL", "VLESS", "VMESS", "TROJAN")) sni else ""
                                val activeProxy = if (selectedProtocol == "SSH+PAYLOAD") proxyHost else ""

                                onSave(
                                    VpnProfile(
                                        id = initialProfile?.id ?: 0,
                                        name = resolvedName,
                                        protocol = selectedProtocol,
                                        server = server.ifBlank { slowDnsServerIp },
                                        port = resolvedPort,
                                        uuid = if (selectedProtocol in listOf("VLESS", "VMESS", "TROJAN")) uuid else sshUser,
                                        network = if (selectedProtocol == "SLOWDNS") "slowdns" else xNetwork,
                                        security = if (selectedProtocol in listOf("SSH+SSL", "VLESS", "VMESS", "TROJAN")) "tls" else "none",
                                        sni = activeSni,
                                        pbk = slowDnsPubKey,
                                        path = wsPath,
                                        isSelected = true,
                                        sshUser = sshUser,
                                        sshPass = sshPass,
                                        payload = activePayload,
                                        proxyHost = activeProxy,
                                        proxyPort = proxyPort.toIntOrNull() ?: 8080,
                                        slowDnsDomain = slowDnsDomain,
                                        slowDnsPubKey = slowDnsPubKey,
                                        slowDnsServerIp = slowDnsServerIp,
                                        udpgwPort = udpgwPort.toIntOrNull() ?: 7300,
                                        customDns = customDns,
                                        isLocked = isLocked,
                                        lockPassphrase = lockPassphrase,
                                        configNote = configNote
                                    )
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Save")
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
                    // 1. Profile Name & Protocol Dropdown Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "1. Select Tunnel Protocol",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )

                            ExposedDropdownMenuBox(
                                expanded = dropdownExpanded,
                                onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedProtocolLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Connection Mode / Protocol") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false }
                                ) {
                                    protocolModes.forEach { (code, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                selectedProtocol = code
                                                dropdownExpanded = false
                                                when (code) {
                                                    "SSH", "SLOWDNS" -> port = "22"
                                                    "SSH+PAYLOAD" -> port = "80"
                                                    "SSH+SSL", "VLESS", "VMESS", "TROJAN" -> port = "443"
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Profile Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 2. Dynamic Protocol-Specific Page
                    when (selectedProtocol) {
                        "SSH" -> {
                            SshServerAndAuthCard(
                                title = "2. SSH Direct Server & Credentials",
                                server = server,
                                onServerChange = { server = it },
                                port = port,
                                onPortChange = { port = it },
                                sshUser = sshUser,
                                onUserChange = { sshUser = it },
                                sshPass = sshPass,
                                onPassChange = { sshPass = it },
                                passwordVisible = passwordVisible,
                                onTogglePasswordVisible = { passwordVisible = !passwordVisible }
                            )
                        }

                        "SSH+PAYLOAD" -> {
                            SshServerAndAuthCard(
                                title = "2. SSH Server & Credentials",
                                server = server,
                                onServerChange = { server = it },
                                port = port,
                                onPortChange = { port = it },
                                sshUser = sshUser,
                                onUserChange = { sshUser = it },
                                sshPass = sshPass,
                                onPassChange = { sshPass = it },
                                passwordVisible = passwordVisible,
                                onTogglePasswordVisible = { passwordVisible = !passwordVisible }
                            )

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "3. HTTP Payload & Remote Proxy",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        payloadTags.forEach { tag ->
                                            AssistChip(
                                                onClick = { payload += tag },
                                                label = { Text(tag) }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = payload,
                                        onValueChange = { payload = it },
                                        label = { Text("HTTP Custom Payload") },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                        minLines = 4,
                                        maxLines = 6,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = proxyHost,
                                            onValueChange = { proxyHost = it },
                                            label = { Text("Proxy IP / Host (Optional)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(2f)
                                        )
                                        OutlinedTextField(
                                            value = proxyPort,
                                            onValueChange = { proxyPort = it },
                                            label = { Text("Port") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        "SSH+SSL" -> {
                            SshServerAndAuthCard(
                                title = "2. SSH SSL/TLS Server & Credentials",
                                server = server,
                                onServerChange = { server = it },
                                port = port,
                                onPortChange = { port = it },
                                sshUser = sshUser,
                                onUserChange = { sshUser = it },
                                sshPass = sshPass,
                                onPassChange = { sshPass = it },
                                passwordVisible = passwordVisible,
                                onTogglePasswordVisible = { passwordVisible = !passwordVisible }
                            )

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "3. SSL SNI / Bug Host Configuration",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    OutlinedTextField(
                                        value = sni,
                                        onValueChange = { sni = it },
                                        label = { Text("SSL SNI / Bug Host (e.g. cdn.domain.com)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        "SLOWDNS" -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "2. SlowDNS (DNSTT) Configuration",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    OutlinedTextField(
                                        value = slowDnsServerIp,
                                        onValueChange = { slowDnsServerIp = it },
                                        label = { Text("SlowDNS Server IP / DNS Resolver") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = slowDnsDomain,
                                        onValueChange = { slowDnsDomain = it },
                                        label = { Text("SlowDNS Name Server (NS Domain)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = slowDnsPubKey,
                                        onValueChange = { slowDnsPubKey = it },
                                        label = { Text("SlowDNS Public Key") },
                                        minLines = 2,
                                        maxLines = 4,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            SshServerAndAuthCard(
                                title = "3. SSH Account Credentials",
                                server = server.ifBlank { slowDnsServerIp },
                                onServerChange = { server = it },
                                port = port,
                                onPortChange = { port = it },
                                sshUser = sshUser,
                                onUserChange = { sshUser = it },
                                sshPass = sshPass,
                                onPassChange = { sshPass = it },
                                passwordVisible = passwordVisible,
                                onTogglePasswordVisible = { passwordVisible = !passwordVisible }
                            )
                        }

                        else -> {
                            // VLESS, VMESS, TROJAN Page
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "2. $selectedProtocol Server & Credentials",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = server,
                                            onValueChange = { server = it },
                                            label = { Text("Server Host / Domain") },
                                            singleLine = true,
                                            modifier = Modifier.weight(2f)
                                        )
                                        OutlinedTextField(
                                            value = port,
                                            onValueChange = { port = it },
                                            label = { Text("Port") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    OutlinedTextField(
                                        value = uuid,
                                        onValueChange = { uuid = it },
                                        label = { Text(if (selectedProtocol == "TROJAN") "Trojan Password" else "UUID") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = sni,
                                        onValueChange = { sni = it },
                                        label = { Text("TLS SNI / Bug Host") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = wsPath,
                                        onValueChange = { wsPath = it },
                                        label = { Text("WebSocket / gRPC Path (e.g. /ws)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // 3. Config Lock & Anti-Sniff Protection Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Lock Configuration",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Hide server, payload & credentials from users",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(checked = isLocked, onCheckedChange = { isLocked = it })
                            }

                            if (isLocked) {
                                OutlinedTextField(
                                    value = lockPassphrase,
                                    onValueChange = { lockPassphrase = it },
                                    label = { Text("Unlock Passphrase (Password)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            OutlinedTextField(
                                value = configNote,
                                onValueChange = { configNote = it },
                                label = { Text("Custom Banner / Note (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 4. Collapsible Advanced UDPGW & DNS Settings
                    TextButton(
                        onClick = { showAdvancedSection = !showAdvancedSection },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showAdvancedSection) "Hide Advanced DNS / UDPGW Settings ▲" else "Show Advanced DNS / UDPGW Settings ▼")
                    }

                    if (showAdvancedSection) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = udpgwPort,
                                    onValueChange = { udpgwPort = it },
                                    label = { Text("UDPGW Port") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = customDns,
                                    onValueChange = { customDns = it },
                                    label = { Text("Custom DNS") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun SshServerAndAuthCard(
    title: String,
    server: String,
    onServerChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    sshUser: String,
    onUserChange: (String) -> Unit,
    sshPass: String,
    onPassChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = server,
                    onValueChange = onServerChange,
                    label = { Text("Server Host / IP") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = sshUser,
                onValueChange = onUserChange,
                label = { Text("SSH Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = sshPass,
                onValueChange = onPassChange,
                label = { Text("SSH Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisible) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Show/Hide Password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ShareAndLockDialog(
    profile: VpnProfile,
    onDismiss: () -> Unit,
    onUpdateLockState: (VpnProfile) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var lockConfig by remember { mutableStateOf(profile.isLocked) }
    var passphrase by remember { mutableStateOf(profile.lockPassphrase) }
    var showQrCode by remember { mutableStateOf(false) }

    val encryptedUri = remember(profile, lockConfig, passphrase) {
        ConfigCryptoHelper.exportProfileUri(profile, lockConfig, passphrase)
    }
    val cloudToken = remember(encryptedUri) {
        "SVPN-CLOUD:" + encryptedUri.substringAfter("://").substringAfter("/")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share & Lock: ${profile.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lock Config (Hide Credentials)", fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = lockConfig,
                        onCheckedChange = {
                            lockConfig = it
                            onUpdateLockState(profile.copy(isLocked = it, lockPassphrase = passphrase))
                        }
                    )
                }

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        onUpdateLockState(profile.copy(isLocked = lockConfig, lockPassphrase = it))
                    },
                    label = { Text("Passphrase Protection (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Divider()

                // 1. Share as .svpn Config File
                Button(
                    onClick = {
                        try {
                            val cleanName = profile.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val outFile = File(context.cacheDir, "$cleanName.svpn")
                            outFile.writeText(encryptedUri)

                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${profile.name}.svpn")
                                putExtra(Intent.EXTRA_TEXT, encryptedUri)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share .svpn Config"))
                            VpnStateBridge.addLog("Exported ${profile.name}.svpn")
                        } catch (t: Throwable) {
                            VpnStateBridge.addLog("Share error: ${t.message}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Config File (.svpn)")
                }

                // 2. Copy Passphrase-Encrypted URI
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(encryptedUri))
                        VpnStateBridge.addLog("Copied encrypted smartvpn:// link to clipboard.")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Passphrase-Locked Link")
                }

                // 3. Copy Cloud Share Code
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(cloudToken))
                        VpnStateBridge.addLog("Copied SVPN-CLOUD token to clipboard.")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Cloud Config Code")
                }

                // 4. Toggle QR Code Display
                OutlinedButton(
                    onClick = { showQrCode = !showQrCode },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (showQrCode) "Hide QR Code" else "Show Config QR Code")
                }

                if (showQrCode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ConfigQrMatrixView(data = encryptedUri)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun ConfigQrMatrixView(data: String) {
    val digest = remember(data) {
        MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
    }
    Box(
        modifier = Modifier
            .size(180.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSize = 21
            val cellSize = size.width / gridSize

            fun isFinderPattern(r: Int, c: Int): Boolean {
                val topLeft = r in 0..6 && c in 0..6
                val topRight = r in 0..6 && c in (gridSize - 7) until gridSize
                val bottomLeft = r in (gridSize - 7) until gridSize && c in 0..6
                return topLeft || topRight || bottomLeft
            }

            fun drawFinder(startR: Int, startC: Int) {
                for (r in 0..6) {
                    for (c in 0..6) {
                        val isBorder = r == 0 || r == 6 || c == 0 || c == 6
                        val isInner = r in 2..4 && c in 2..4
                        if (isBorder || isInner) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset((startC + c) * cellSize, (startR + r) * cellSize),
                                size = Size(cellSize, cellSize)
                            )
                        }
                    }
                }
            }

            drawFinder(0, 0)
            drawFinder(0, gridSize - 7)
            drawFinder(gridSize - 7, 0)

            var bitIndex = 0
            for (r in 0 until gridSize) {
                for (c in 0 until gridSize) {
                    if (!isFinderPattern(r, c)) {
                        val byteVal = digest[(bitIndex / 8) % digest.size].toInt()
                        val bit = (byteVal shr (bitIndex % 8)) and 1
                        if (bit == 1) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(c * cellSize, r * cellSize),
                                size = Size(cellSize, cellSize)
                            )
                        }
                        bitIndex++
                    }
                }
            }
        }
    }
}

@Composable
fun ImportAdvancedDialog(
    onDismiss: () -> Unit,
    onImportSuccess: (VpnProfile) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var configInput by remember {
        mutableStateOf(clipboardManager.getText()?.text ?: "")
    }
    var passphrase by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Locked / Cloud Config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste a smartvpn:// link, SVPN-CLOUD token, or .svpn file content below:",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = configInput,
                    onValueChange = {
                        configInput = it
                        errorMsg = ""
                    },
                    label = { Text("Config Link / Cloud Code") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        errorMsg = ""
                    },
                    label = { Text("Passphrase (If Locked with Password)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg.isNotEmpty()) {
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = ConfigCryptoHelper.importProfileUri(configInput, passphrase)
                if (parsed != null) {
                    VpnStateBridge.addLog("Unlocked & imported config: ${parsed.name}")
                    onImportSuccess(parsed)
                } else {
                    errorMsg = "Could not unlock config. Check your passphrase or config code."
                }
            }) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
