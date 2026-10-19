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
    var showCloudImportModal by remember { mutableStateOf(false) }
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                showImportMenu = false
                                clipboardManager.getText()?.text?.let { onImportClipboard(it) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Import from Clipboard", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                        TextButton(
                            onClick = {
                                showImportMenu = false
                                showCloudImportModal = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Import from Cloud", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                        TextButton(
                            onClick = {
                                showImportMenu = false
                                editingProfile = null
                                showConfigDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Manual Configuration", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showImportMenu = false }) { Text("Cancel") }
                }
            )
        }

        if (showCloudImportModal) {
            var cloudUrl by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCloudImportModal = false },
                title = { Text("Import from Cloud") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste the cloud URL (e.g., https://paste.rs/xyz)", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = cloudUrl,
                            onValueChange = { cloudUrl = it },
                            label = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (cloudUrl.isNotBlank()) {
                            onImportClipboard(cloudUrl)
                            showCloudImportModal = false
                        }
                    }) {
                        Text("Import")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCloudImportModal = false }) { Text("Cancel") }
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
        "SSH" to "SSH",
        "SSH-DIRECT" to "SSH-DIRECT",
        "SSH-PROXY" to "SSH-PROXY",
        "SSH-PAYLOAD" to "SSH-PAYLOAD",
        "SSH-PROXY-PAYLOAD" to "SSH-PROXY-PAYLOAD",
        "SSH-TLS" to "SSH-TLS",
        "SSH-TLS-PROXY" to "SSH-TLS-PROXY",
        "SSH-TLS-PAYLOAD" to "SSH-TLS-PAYLOAD",
        "SSH-TLS-PROXY-PAYLOAD" to "SSH-TLS-PROXY-PAYLOAD",
        "SSH-DNSTT" to "SSH-DNSTT",
        "SSH-DNSTT-PAYLOAD" to "SSH-DNSTT-PAYLOAD",
        "SSH-DNSTT-PROXY" to "SSH-DNSTT-PROXY",
        "SSH-DNSTT-PROXY-PAYLOAD" to "SSH-DNSTT-PROXY-PAYLOAD",
        "SSH-DNSTT-TLS" to "SSH-DNSTT-TLS",
        "SSH-DNSTT-TLS-PAYLOAD" to "SSH-DNSTT-TLS-PAYLOAD",
        "SSH-DNSTT-TLS-PROXY" to "SSH-DNSTT-TLS-PROXY",
        "SSH-DNSTT-TLS-PROXY-PAYLOAD" to "SSH-DNSTT-TLS-PROXY-PAYLOAD",
        "VMESS" to "VMESS",
        "VLESS" to "VLESS",
        "TROJAN" to "TROJAN",
        "SHADOWSOCKS" to "SHADOWSOCKS",
        "WIREGUARD" to "WIREGUARD",
        "OPENVPN" to "OPENVPN",
        "HYSTERIA2" to "HYSTERIA2"
    )

    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(initialProfile?.protocol?.uppercase() ?: "SSH-DIRECT") }

    var name by remember { mutableStateOf(initialProfile?.name ?: "New Config") }
    var server by remember { mutableStateOf(initialProfile?.server ?: "") }
    var port by remember { mutableStateOf((initialProfile?.port ?: 22).toString()) }
    var udpgwPort by remember { mutableStateOf((initialProfile?.udpgwPort ?: 7300).toString()) }
    var enableUdpgwDns by remember { mutableStateOf(initialProfile?.enableUdpgwDns ?: false) }
    var sshUser by remember { mutableStateOf(initialProfile?.sshUser ?: "") }
    var sshPass by remember { mutableStateOf(initialProfile?.sshPass ?: "") }
    var authenticateProxy by remember { mutableStateOf(initialProfile?.proxyAuth ?: false) }
    var httpProxy by remember { mutableStateOf(initialProfile?.proxyHost?.let { if (it.isNotBlank()) "$it:${initialProfile.proxyPort}" else "" } ?: "") }
    var payload by remember { mutableStateOf(initialProfile?.payload ?: "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]") }
    var tlsVersion by remember { mutableStateOf(initialProfile?.tlsVersion ?: "DEFAULT") }
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }
    var dnsttMode by remember { mutableStateOf(initialProfile?.dnsttMode ?: "UDP") }
    var udpResolver by remember { mutableStateOf(initialProfile?.slowDnsServerIp ?: "8.8.8.8:53") }
    var nameserver by remember { mutableStateOf(initialProfile?.slowDnsDomain ?: "") }
    var publicKey by remember { mutableStateOf(initialProfile?.slowDnsPubKey ?: "") }

    var uuid by remember { mutableStateOf(initialProfile?.uuid ?: "") }
    var network by remember { mutableStateOf(initialProfile?.network ?: "tcp") }
    var security by remember { mutableStateOf(initialProfile?.security ?: "none") }
    var alpn by remember { mutableStateOf(initialProfile?.alpn ?: "h2, http/1.1") }
    var pinSha256 by remember { mutableStateOf(initialProfile?.pinSha256 ?: "") }
    var verifyCertName by remember { mutableStateOf(initialProfile?.verifyCertName ?: "") }
    var wsHost by remember { mutableStateOf(initialProfile?.wsHost ?: "") }
    var wsPath by remember { mutableStateOf(initialProfile?.path ?: "/") }
    var allowInsecure by remember { mutableStateOf(initialProfile?.allowInsecure ?: true) }

    val hasProxy = selectedProtocol.contains("PROXY")
    val hasPayload = selectedProtocol.contains("PAYLOAD")
    val hasTls = selectedProtocol.contains("TLS")
    val hasDnstt = selectedProtocol.contains("DNSTT")
    val isXrayProto = selectedProtocol in listOf("VMESS", "VLESS", "TROJAN", "SHADOWSOCKS")
    val isWireGuard = selectedProtocol == "WIREGUARD"
    val isOpenVpn = selectedProtocol == "OPENVPN"
    val isHysteria2 = selectedProtocol == "HYSTERIA2"

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(if (initialProfile == null) "New Configuration" else "Edit Configuration") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) } },
                    actions = {
                        Button(
                            onClick = {
                                val resolvedPort = port.toIntOrNull() ?: if (isXrayProto) 443 else 22
                                val pHost = httpProxy.substringBefore(":", "").trim()
                                val pPort = httpProxy.substringAfter(":", "8080").toIntOrNull() ?: 8080

                                onSave(
                                    VpnProfile(
                                        id = initialProfile?.id ?: 0,
                                        name = name,
                                        protocol = selectedProtocol,
                                        server = server.ifBlank { sni },
                                        port = resolvedPort,
                                        udpgwPort = udpgwPort.toIntOrNull() ?: 7300,
                                        enableUdpgwDns = enableUdpgwDns,
                                        sshUser = sshUser,
                                        sshPass = sshPass,
                                        proxyAuth = authenticateProxy,
                                        proxyHost = pHost,
                                        proxyPort = pPort,
                                        payload = payload,
                                        tlsVersion = tlsVersion,
                                        sni = sni,
                                        dnsttMode = dnsttMode,
                                        slowDnsServerIp = udpResolver,
                                        slowDnsDomain = nameserver,
                                        slowDnsPubKey = publicKey,
                                        uuid = uuid,
                                        network = network,
                                        security = security,
                                        alpn = alpn,
                                        pinSha256 = pinSha256,
                                        verifyCertName = verifyCertName,
                                        wsHost = wsHost,
                                        path = wsPath,
                                        allowInsecure = allowInsecure,
                                        isSelected = true
                                    )
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00B8))
                        ) {
                            Text("SAVE CONFIGURATION", color = Color.White, fontWeight = FontWeight.Bold)
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

                    if (!isXrayProto && !isWireGuard && !isOpenVpn && !isHysteria2) {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text(if (hasDnstt) "SSH Host / Nameserver" else "SSH Host") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("SSH Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (hasTls && !hasDnstt) {
                            OutlinedTextField(
                                value = sni,
                                onValueChange = { sni = it },
                                label = { Text("SNI / Bug Host (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = udpgwPort,
                            onValueChange = { udpgwPort = it },
                            label = { Text("Udpgw Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = enableUdpgwDns, onCheckedChange = { enableUdpgwDns = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enable UDPGW transparent DNS. This may override any set custom DNS server.", style = MaterialTheme.typography.bodySmall)
                        }

                        OutlinedTextField(
                            value = sshUser,
                            onValueChange = { sshUser = it },
                            label = { Text("SSH Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = sshPass,
                            onValueChange = { sshPass = it },
                            label = { Text("SSH Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (hasTls) {
                            var tlsExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = tlsExpanded, onExpandedChange = { tlsExpanded = !tlsExpanded }) {
                                OutlinedTextField(
                                    value = tlsVersion,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("TLS Version") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tlsExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = tlsExpanded, onDismissRequest = { tlsExpanded = false }) {
                                    listOf("DEFAULT", "TLSv1.0", "TLSv1.1", "TLSv1.2", "TLSv1.3").forEach { ver ->
                                        DropdownMenuItem(text = { Text(ver) }, onClick = { tlsVersion = ver; tlsExpanded = false })
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = sni,
                                onValueChange = { sni = it },
                                label = { Text("SNI (Bug Host)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (hasProxy) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = authenticateProxy, onCheckedChange = { authenticateProxy = it })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Authenticate Proxy")
                            }
                            OutlinedTextField(
                                value = httpProxy,
                                onValueChange = { httpProxy = it },
                                label = { Text("HTTP Proxy (ip:port or host:port)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (hasPayload) {
                            OutlinedTextField(
                                value = payload,
                                onValueChange = { payload = it },
                                label = { Text("Payload") },
                                minLines = 4,
                                textStyle = FontFamily.Monospace.let { LocalTextStyle.current.copy(fontFamily = it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (hasDnstt) {
                            var dnsttExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = dnsttExpanded, onExpandedChange = { dnsttExpanded = !dnsttExpanded }) {
                                OutlinedTextField(
                                    value = dnsttMode,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("DNSTT Mode") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dnsttExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = dnsttExpanded, onDismissRequest = { dnsttExpanded = false }) {
                                    listOf("UDP", "DoH", "DoT").forEach { m ->
                                        DropdownMenuItem(text = { Text(m) }, onClick = { dnsttMode = m; dnsttExpanded = false })
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = udpResolver,
                                onValueChange = { udpResolver = it },
                                label = { Text("UDP Resolver (e.g. 8.8.8.8:53)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = nameserver,
                                onValueChange = { nameserver = it },
                                label = { Text("Nameserver") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = publicKey,
                                onValueChange = { publicKey = it },
                                label = { Text("Public Key") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (isXrayProto) {
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

                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text(if (selectedProtocol == "SHADOWSOCKS") "Password" else "UUID") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = network,
                            onValueChange = { network = it },
                            label = { Text("Network (tcp, ws, grpc)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = security,
                            onValueChange = { security = it },
                            label = { Text("Security (none, tls, xtls, reality)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = alpn,
                            onValueChange = { alpn = it },
                            label = { Text("ALPN (e.g. h2, http/1.1)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text("SNI (Bug Host, multi-SNI comma separated)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = pinSha256,
                            onValueChange = { pinSha256 = it },
                            label = { Text("pinSHA256") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = verifyCertName,
                            onValueChange = { verifyCertName = it },
                            label = { Text("verifyCertName") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = wsHost,
                            onValueChange = { wsHost = it },
                            label = { Text("Host (WebSocket) / Authority (gRPC)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = wsPath,
                            onValueChange = { wsPath = it },
                            label = { Text("Path (WebSocket) / ServiceName (gRPC)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Allow Insecure (Bypass TLS Cert Verification)")
                        }
                    }

                    if (isWireGuard) {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("WireGuard Endpoint IP / Host") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("WireGuard Endpoint Port (e.g. 51820)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("Client Private Key (SecretKey)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isOpenVpn) {
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
                            label = { Text("Server Port (1194)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = sshUser,
                            onValueChange = { sshUser = it },
                            label = { Text("OpenVPN Username (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = sshPass,
                            onValueChange = { sshPass = it },
                            label = { Text("OpenVPN Password (Optional)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isHysteria2) {
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
                            label = { Text("Server Port (443)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("Auth Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
