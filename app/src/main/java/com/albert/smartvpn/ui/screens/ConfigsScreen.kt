package com.albert.smartvpn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.albert.smartvpn.data.VpnProfile

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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Configurations") },
                actions = {
                    IconButton(onClick = {
                        editingProfile = null
                        showConfigDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Manual Config")
                    }
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { onImportClipboard(it) }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Import from Clipboard")
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
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No profiles found.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap + to build a custom SSH / SlowDNS / VLESS config or paste from clipboard.",
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
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${profile.protocol} • ${profile.server}:${profile.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (profile.slowDnsDomain.isNotEmpty()) {
                                    Text(
                                        text = "NS: ${profile.slowDnsDomain} (${profile.slowDnsServerIp})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (profile.sni.isNotEmpty()) {
                                    Text(
                                        text = "SNI: ${profile.sni}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = {
                                    editingProfile = profile
                                    showConfigDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Profile")
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

    if (showConfigDialog) {
        AdvancedConfigDialog(
            initialProfile = editingProfile,
            onDismiss = { showConfigDialog = false },
            onSave = { newProfile ->
                onSaveProfile(newProfile)
                showConfigDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedConfigDialog(
    initialProfile: VpnProfile?,
    onDismiss: () -> Unit,
    onSave: (VpnProfile) -> Unit
) {
    val protocols = listOf(
        "SLOWDNS",
        "SSH+WS",
        "SSH+SSL",
        "SSH",
        "VLESS",
        "VMESS",
        "TROJAN"
    )

    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var selectedProtocol by remember { mutableStateOf(initialProfile?.protocol ?: "SLOWDNS") }
    var server by remember { mutableStateOf(initialProfile?.server ?: "") }
    var port by remember { mutableStateOf((initialProfile?.port ?: 22).toString()) }

    // SSH & Auth Fields
    var sshUser by remember { mutableStateOf(initialProfile?.sshUser ?: "") }
    var sshPass by remember { mutableStateOf(initialProfile?.sshPass ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var uuid by remember { mutableStateOf(initialProfile?.uuid ?: "") }

    // Payload & Proxy / SNI Fields
    var payload by remember {
        mutableStateOf(
            initialProfile?.payload
                ?: "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"
        )
    }
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }
    var proxyHost by remember { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf((initialProfile?.proxyPort ?: 8080).toString()) }
    var wsPath by remember { mutableStateOf(initialProfile?.path ?: "/") }

    // SlowDNS (DNSTT) Dedicated Fields
    var slowDnsServerIp by remember { mutableStateOf(initialProfile?.slowDnsServerIp ?: "8.8.8.8") }
    var slowDnsDomain by remember { mutableStateOf(initialProfile?.slowDnsDomain ?: "") }
    var slowDnsPubKey by remember { mutableStateOf(initialProfile?.slowDnsPubKey ?: "") }

    // Advanced Edutecz Settings
    var udpgwPort by remember { mutableStateOf((initialProfile?.udpgwPort ?: 7300).toString()) }
    var customDns by remember { mutableStateOf(initialProfile?.customDns ?: "1.1.1.1") }
    var enableCompression by remember { mutableStateOf(initialProfile?.enableCompression ?: true) }
    var autoReconnect by remember { mutableStateOf(initialProfile?.autoReconnect ?: true) }
    var enableWakeLock by remember { mutableStateOf(initialProfile?.enableWakeLock ?: true) }

    val payloadTags = listOf(
        "[host]",
        "[port]",
        "[host_port]",
        "[crlf]",
        "[cr]",
        "[lf]",
        "[ua]",
        "[split]",
        "[instant_split]",
        "[protocol]"
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
                        Text(if (initialProfile == null) "New Advanced Config" else "Edit Config")
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
                                val resolvedServer = server.ifBlank { slowDnsServerIp }
                                val resolvedPort = port.toIntOrNull() ?: 443
                                val resolvedProxyPort = proxyPort.toIntOrNull() ?: 8080
                                val resolvedUdpPort = udpgwPort.toIntOrNull() ?: 7300

                                onSave(
                                    VpnProfile(
                                        id = initialProfile?.id ?: 0,
                                        name = resolvedName,
                                        protocol = selectedProtocol,
                                        server = resolvedServer,
                                        port = resolvedPort,
                                        uuid = if (uuid.isNotBlank()) uuid else sshUser,
                                        network = if (selectedProtocol == "SLOWDNS") "slowdns" else "ws",
                                        security = if (selectedProtocol.contains("SSL") || selectedProtocol in listOf("VLESS", "VMESS", "TROJAN")) "tls" else "none",
                                        sni = sni,
                                        pbk = slowDnsPubKey,
                                        path = wsPath,
                                        isSelected = initialProfile?.isSelected ?: true,
                                        sshUser = sshUser,
                                        sshPass = sshPass,
                                        payload = payload,
                                        proxyHost = proxyHost,
                                        proxyPort = resolvedProxyPort,
                                        slowDnsDomain = slowDnsDomain,
                                        slowDnsPubKey = slowDnsPubKey,
                                        slowDnsServerIp = slowDnsServerIp,
                                        udpgwPort = resolvedUdpPort,
                                        customDns = customDns,
                                        enableCompression = enableCompression,
                                        autoReconnect = autoReconnect,
                                        enableWakeLock = enableWakeLock
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Profile Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name (e.g. UK SlowDNS / VLESS)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Tunnel Mode / Protocol Selector
                    Text(
                        text = "Tunnel Protocol / Mode",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        protocols.forEach { proto ->
                            FilterChip(
                                selected = selectedProtocol == proto,
                                onClick = {
                                    selectedProtocol = proto
                                    if (proto == "SLOWDNS" && port == "443") port = "22"
                                    if (proto in listOf("VLESS", "VMESS", "TROJAN") && port == "22") port = "443"
                                },
                                label = { Text(proto) }
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    // Server Host & Port
                    Text(
                        text = "Core Server Endpoint",
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
                            label = { Text("Server Host / IP") },
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

                    // Authentication Section (SSH vs Xray)
                    if (selectedProtocol in listOf("SLOWDNS", "SSH+WS", "SSH+SSL", "SSH")) {
                        Text(
                            text = "SSH Authentication",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it },
                                label = { Text("SSH Username") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = sshPass,
                                onValueChange = { sshPass = it },
                                label = { Text("SSH Password") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle Password"
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Text(
                            text = "Xray Authentication (VLESS / VMess / Trojan)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("UUID / Trojan Password") },
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

                    // Dedicated SlowDNS (DNSTT) Section
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "SlowDNS (DNSTT) Settings",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = slowDnsServerIp,
                        onValueChange = { slowDnsServerIp = it },
                        label = { Text("SlowDNS Server IP / DNS Resolver (e.g. 8.8.8.8 or VPS IP)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = slowDnsDomain,
                        onValueChange = { slowDnsDomain = it },
                        label = { Text("SlowDNS Name Server (NS Domain, e.g. ns.domain.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = slowDnsPubKey,
                        onValueChange = { slowDnsPubKey = it },
                        label = { Text("SlowDNS Public Key (Hex / Base64 Key)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // HTTP Custom Payload & Quick Generator Section
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "HTTP Custom Payload & Generator Tags",
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
                        label = { Text("HTTP / WebSocket Payload Box") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 4,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // SNI & Remote Proxy Section
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "SSL SNI / Bug Host & Remote Proxy",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = sni,
                        onValueChange = { sni = it },
                        label = { Text("SSL SNI / Bug Host (e.g. cdn.int.vid.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = proxyHost,
                            onValueChange = { proxyHost = it },
                            label = { Text("Remote Proxy IP / Host") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = proxyPort,
                            onValueChange = { proxyPort = it },
                            label = { Text("Proxy Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Advanced Edutecz Tunnel Options
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Advanced Tunnel Controls (UDPGW, DNS & Keep-Alive)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = udpgwPort,
                            onValueChange = { udpgwPort = it },
                            label = { Text("BadVPN UDPGW Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customDns,
                            onValueChange = { customDns = it },
                            label = { Text("Primary DNS") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-Reconnect on Drop")
                        Switch(checked = autoReconnect, onCheckedChange = { autoReconnect = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SSH Data Compression")
                        Switch(checked = enableCompression, onCheckedChange = { enableCompression = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("CPU WakeLock (Prevent Sleep)")
                        Switch(checked = enableWakeLock, onCheckedChange = { enableWakeLock = it })
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
