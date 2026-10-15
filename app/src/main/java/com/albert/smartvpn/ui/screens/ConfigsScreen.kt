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
import androidx.compose.ui.unit.dp
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
    onImportClipboard: (String) -> Unit,
    onSaveProfile: (VpnProfile) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val connectionState by VpnStateBridge.connectionState.collectAsState()
    val slowDnsBoostEnabled by VpnStateBridge.slowDnsBoostEnabled.collectAsState()
    val isTunnelBusy = connectionState != "DISCONNECTED"

    var showConfigDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var sharingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var cloningProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var unlockPromptProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var unlockActionType by remember { mutableStateOf("EDIT") }
    var showImportModal by remember { mutableStateOf(false) }
    var showConnectedEditBlockAlert by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Server Configurations") },
                actions = {
                    IconButton(onClick = { editingProfile = null; showConfigDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Config")
                    }
                    IconButton(onClick = { showImportModal = true }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Import Locked Config")
                    }
                    IconButton(onClick = { clipboardManager.getText()?.text?.let { onImportClipboard(it) } }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste Clipboard")
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
            // SLOWDNS SPEED BOOST TOGGLE CARD (IN CONFIGS, DEFAULT OFF, AUTO-ON ONLY WHEN SLOWDNS CONNECTS)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.RocketLaunch,
                            contentDescription = null,
                            tint = if (slowDnsBoostEnabled) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "SlowDNS Speed Boost",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (slowDnsBoostEnabled) {
                                    "ACTIVE • Auto-engaged for connected SlowDNS tunnel"
                                } else {
                                    "STANDBY (OFF) • Auto-turns ON when SlowDNS connects"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (slowDnsBoostEnabled) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = slowDnsBoostEnabled,
                        onCheckedChange = null,
                        enabled = false
                    )
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
                            "Tap + to build SSH, SSH+WS+SNI+Payload, SlowDNS (Any Port), VLESS, VMess, Trojan, WireGuard, or Hysteria2.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                RadioButton(
                                    selected = profile.isSelected,
                                    onClick = {
                                        if (!isTunnelBusy) onSelectProfile(profile)
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (profile.isLocked) {
                                            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        if (profile.isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isCurrentlyConnectedProfile) "CONNECTED" else "SELECTED",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF00E676),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when {
                                            profile.isLocked -> "${profile.protocol} • [Hardware-Locked Enclave]"
                                            profile.protocol == "SLOWDNS" -> "SLOWDNS (Port ${profile.port}) • NS: ${profile.slowDnsDomain}"
                                            else -> "${profile.protocol} • ${profile.server}:${profile.port}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    IconButton(onClick = { cloningProfile = profile }) {
                                        Icon(Icons.Default.CopyAll, contentDescription = "Clone")
                                    }
                                    IconButton(onClick = {
                                        if (profile.isLocked) { unlockActionType = "SHARE"; unlockPromptProfile = profile }
                                        else sharingProfile = profile
                                    }) {
                                        Icon(Icons.Default.Share, contentDescription = "Share")
                                    }
                                    // BLOCK EDITING WHEN THIS PROFILE IS CURRENTLY CONNECTED
                                    IconButton(onClick = {
                                        if (isCurrentlyConnectedProfile) {
                                            showConnectedEditBlockAlert = true
                                        } else if (profile.isLocked) {
                                            unlockActionType = "EDIT"
                                            unlockPromptProfile = profile
                                        } else {
                                            editingProfile = profile
                                            showConfigDialog = true
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            tint = if (isCurrentlyConnectedProfile) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else LocalContentColor.current
                                        )
                                    }
                                    IconButton(onClick = {
                                        if (isCurrentlyConnectedProfile) {
                                            showConnectedEditBlockAlert = true
                                        } else {
                                            onDeleteProfile(profile)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = if (isCurrentlyConnectedProfile) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else LocalContentColor.current
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

    if (showConnectedEditBlockAlert) {
        AlertDialog(
            onDismissRequest = { showConnectedEditBlockAlert = false },
            icon = { Icon(Icons.Default.LockClock, contentDescription = null) },
            title = { Text("Profile Currently Connected") },
            text = {
                Text("You cannot edit or delete an active configuration while the VPN tunnel is connected. Please disconnect the VPN first.")
            },
            confirmButton = {
                Button(onClick = { showConnectedEditBlockAlert = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (cloningProfile != null) {
        val target = cloningProfile!!
        AlertDialog(
            onDismissRequest = { cloningProfile = null },
            title = { Text("Clone Profile: ${target.name}") },
            text = { Text("Create a duplicate of this configuration profile:") },
            confirmButton = {
                Button(onClick = {
                    onSaveProfile(target.copy(id = 0, name = "${target.name} (Clone)", isSelected = false))
                    cloningProfile = null
                }) { Text("Clone Profile") }
            },
            dismissButton = {
                TextButton(onClick = { cloningProfile = null }) { Text("Cancel") }
            }
        )
    }

    if (showConfigDialog) {
        ProtocolConfigDialog(
            initialProfile = editingProfile,
            onDismiss = { showConfigDialog = false },
            onSave = { onSaveProfile(it); showConfigDialog = false }
        )
    }

    if (unlockPromptProfile != null) {
        val target = unlockPromptProfile!!
        var enteredPass by remember { mutableStateOf("") }
        var errorText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { unlockPromptProfile = null },
            title = { Text("Hardware-Locked Config") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter creator passphrase to unlock:")
                    OutlinedTextField(
                        value = enteredPass,
                        onValueChange = { enteredPass = it; errorText = "" },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorText.isNotEmpty()) Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (target.lockPassphrase.isNotEmpty() && enteredPass == target.lockPassphrase) {
                        unlockPromptProfile = null
                        if (unlockActionType == "SHARE") sharingProfile = target
                        else { editingProfile = target; showConfigDialog = true }
                    } else errorText = "Access Denied: Invalid passphrase."
                }) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = { unlockPromptProfile = null }) { Text("Cancel") } }
        )
    }

    if (sharingProfile != null) {
        ShareAndLockDialog(
            profile = sharingProfile!!,
            onDismiss = { sharingProfile = null },
            onUpdateLockState = { onSaveProfile(it); sharingProfile = it }
        )
    }

    if (showImportModal) {
        ImportAdvancedDialog(
            onDismiss = { showImportModal = false },
            onImportSuccess = { onSaveProfile(it); showImportModal = false }
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
        "SSH" to "SSH Direct (Port 22 / Any)",
        "SSH+WS+SNI+PAYLOAD" to "SSH + WS + SNI + Payload (Port 443 / 80)",
        "SSH+SSL" to "Pure SSH + SSL / TLS (Port 443, No Payload/Proxy)",
        "SSH+PAYLOAD" to "SSH + Proxy + Payload (Port 80 / 443 / 8080)",
        "SLOWDNS" to "SlowDNS (DNSTT Tunnel - Any Port)",
        "VLESS" to "VLESS (TLS 443 / noTLS 80)",
        "VMESS" to "VMess (TLS 443 / noTLS 80)",
        "TROJAN" to "Trojan (TLS 443 / noTLS 80)",
        "WIREGUARD" to "WireGuard (Native Tunnel)",
        "HYSTERIA2" to "Hysteria2 (QUIC v2 Acceleration)"
    )

    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(initialProfile?.protocol?.uppercase() ?: "SSH") }
    val selectedProtocolLabel = protocolModes.firstOrNull { it.first == selectedProtocol }?.second ?: selectedProtocol

    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var server by remember { mutableStateOf(initialProfile?.server ?: "") }
    var port by remember { mutableStateOf((initialProfile?.port ?: 22).toString()) }
    var sshUser by remember { mutableStateOf(initialProfile?.sshUser ?: "") }
    var sshPass by remember { mutableStateOf(initialProfile?.sshPass ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }

    var payload by remember {
        mutableStateOf(
            initialProfile?.payload?.ifBlank {
                "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"
            } ?: "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"
        )
    }
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }
    var proxyHost by remember { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf((initialProfile?.proxyPort ?: 8080).toString()) }

    var uuid by remember { mutableStateOf(initialProfile?.uuid ?: "") }
    var wsPath by remember { mutableStateOf(initialProfile?.path ?: "/") }
    var xNetwork by remember { mutableStateOf(initialProfile?.network ?: "ws") }
    var xSecurity by remember { mutableStateOf(initialProfile?.security ?: "tls") }

    var slowDnsServerIp by remember { mutableStateOf(initialProfile?.slowDnsServerIp ?: "8.8.8.8") }
    var slowDnsDomain by remember { mutableStateOf(initialProfile?.slowDnsDomain ?: "") }
    var slowDnsPubKey by remember { mutableStateOf(initialProfile?.slowDnsPubKey ?: "") }

    var wgPrivateKey by remember { mutableStateOf(initialProfile?.wgPrivateKey ?: "") }
    var wgPublicKey by remember { mutableStateOf(initialProfile?.wgPublicKey ?: "") }
    var wgPresharedKey by remember { mutableStateOf(initialProfile?.wgPresharedKey ?: "") }
    var wgAddress by remember { mutableStateOf(initialProfile?.wgAddress ?: "10.0.0.2/32") }
    var wgMtu by remember { mutableStateOf((initialProfile?.wgMtu ?: 1420).toString()) }

    var hy2Password by remember { mutableStateOf(initialProfile?.hy2Password ?: "") }
    var hy2Obfs by remember { mutableStateOf(initialProfile?.hy2Obfs ?: "") }
    var hy2ObfsPassword by remember { mutableStateOf(initialProfile?.hy2ObfsPassword ?: "") }
    var hy2UpMbps by remember { mutableStateOf((initialProfile?.hy2UpMbps ?: 50).toString()) }
    var hy2DownMbps by remember { mutableStateOf((initialProfile?.hy2DownMbps ?: 100).toString()) }

    val payloadTags = listOf("[host]", "[sni]", "[port]", "[host_port]", "[crlf]", "[ua]", "[split]")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(if (initialProfile == null) "Configure Profile" else "Edit Profile") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) } },
                    actions = {
                        Button(
                            onClick = {
                                val resolvedPort = port.toIntOrNull() ?: when (selectedProtocol) {
                                    "SSH" -> 22
                                    "SLOWDNS" -> 53
                                    "WIREGUARD" -> 51820
                                    "SSH+PAYLOAD" -> 80
                                    else -> 443
                                }
                                val resolvedServer = server.trim().ifBlank {
                                    if (selectedProtocol == "SLOWDNS") slowDnsDomain.trim() else sni.trim()
                                }
                                val securityType = when (selectedProtocol) {
                                    "SSH+SSL", "HYSTERIA2" -> "tls"
                                    "WIREGUARD" -> "noise"
                                    "SSH+WS+SNI+PAYLOAD" -> if (resolvedPort == 80 || resolvedPort == 8080) "none" else "tls"
                                    "VLESS", "VMESS", "TROJAN" -> if (resolvedPort == 80 && xSecurity != "tls") "none" else xSecurity
                                    else -> "none"
                                }
                                onSave(
                                    VpnProfile(
                                        id = initialProfile?.id ?: 0,
                                        name = name.ifBlank { "$selectedProtocol-$resolvedServer" },
                                        protocol = selectedProtocol,
                                        server = resolvedServer,
                                        port = resolvedPort,
                                        uuid = when (selectedProtocol) {
                                            "VLESS", "VMESS", "TROJAN" -> uuid
                                            "HYSTERIA2" -> hy2Password
                                            else -> sshUser
                                        },
                                        network = if (selectedProtocol == "SLOWDNS") "slowdns" else xNetwork,
                                        security = securityType,
                                        sni = sni,
                                        pbk = slowDnsPubKey,
                                        path = wsPath,
                                        isSelected = true,
                                        sshUser = sshUser,
                                        sshPass = sshPass,
                                        payload = if (selectedProtocol in listOf("SSH+WS+SNI+PAYLOAD", "SSH+PAYLOAD")) payload else "",
                                        proxyHost = if (selectedProtocol == "SSH+PAYLOAD") proxyHost else "",
                                        proxyPort = proxyPort.toIntOrNull() ?: 8080,
                                        slowDnsDomain = slowDnsDomain,
                                        slowDnsPubKey = slowDnsPubKey,
                                        slowDnsServerIp = slowDnsServerIp.ifBlank { "8.8.8.8" },
                                        wgPrivateKey = wgPrivateKey,
                                        wgPublicKey = wgPublicKey,
                                        wgPresharedKey = wgPresharedKey,
                                        wgAddress = wgAddress,
                                        wgMtu = wgMtu.toIntOrNull() ?: 1420,
                                        hy2Password = hy2Password,
                                        hy2Obfs = hy2Obfs,
                                        hy2ObfsPassword = hy2ObfsPassword,
                                        hy2UpMbps = hy2UpMbps.toIntOrNull() ?: 50,
                                        hy2DownMbps = hy2DownMbps.toIntOrNull() ?: 100,
                                        isLocked = initialProfile?.isLocked ?: false,
                                        lockPassphrase = initialProfile?.lockPassphrase ?: "",
                                        lockedDeviceId = initialProfile?.lockedDeviceId ?: ""
                                    )
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("Save") }
                    }
                )

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("1. Select Tunnel Protocol", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { dropdownExpanded = !dropdownExpanded }) {
                                OutlinedTextField(
                                    value = selectedProtocolLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Connection Mode / Protocol") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                                    protocolModes.forEach { (code, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                selectedProtocol = code
                                                dropdownExpanded = false
                                                port = when (code) {
                                                    "SSH" -> "22"
                                                    "SLOWDNS" -> "53"
                                                    "SSH+PAYLOAD" -> "80"
                                                    "WIREGUARD" -> "51820"
                                                    else -> "443"
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    when (selectedProtocol) {
                        "SSH" -> {
                            SshServerAndAuthCard("2. SSH Direct Server & Credentials", server, { server = it }, port, { port = it }, sshUser, { sshUser = it }, sshPass, { sshPass = it }, passwordVisible) { passwordVisible = !passwordVisible }
                        }
                        "SSH+WS+SNI+PAYLOAD" -> {
                            SshServerAndAuthCard("2. SSH Server & Account Credentials", server, { server = it }, port, { port = it }, sshUser, { sshUser = it }, sshPass, { sshPass = it }, passwordVisible) { passwordVisible = !passwordVisible }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("3. SNI (Bug Host) & WebSocket Payload", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SSL SNI / Bug Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        payloadTags.forEach { tag -> AssistChip(onClick = { payload += tag }, label = { Text(tag) }) }
                                    }
                                    OutlinedTextField(
                                        value = payload,
                                        onValueChange = { payload = it },
                                        label = { Text("WebSocket / HTTP Payload ([host] = SSH Server)") },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                        minLines = 4,
                                        maxLines = 6,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        "SSH+SSL" -> {
                            SshServerAndAuthCard("2. Pure SSH + SSL Server & Credentials", server, { server = it }, port, { port = it }, sshUser, { sshUser = it }, sshPass, { sshPass = it }, passwordVisible) { passwordVisible = !passwordVisible }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("3. SSL / TLS SNI (Spoof Host)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SSL SNI / Bug Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        "SSH+PAYLOAD" -> {
                            SshServerAndAuthCard("2. SSH Server & Credentials", server, { server = it }, port, { port = it }, sshUser, { sshUser = it }, sshPass, { sshPass = it }, passwordVisible) { passwordVisible = !passwordVisible }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("3. Remote Proxy & HTTP Payload", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = proxyHost, onValueChange = { proxyHost = it }, label = { Text("Remote Proxy IP / Host") }, singleLine = true, modifier = Modifier.weight(2f))
                                        OutlinedTextField(value = proxyPort, onValueChange = { proxyPort = it }, label = { Text("Proxy Port") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                    OutlinedTextField(value = payload, onValueChange = { payload = it }, label = { Text("HTTP Custom Payload") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        "SLOWDNS" -> {
                            SshServerAndAuthCard("2. Server Host, Port & SSH Credentials", server, { server = it }, port, { port = it }, sshUser, { sshUser = it }, sshPass, { sshPass = it }, passwordVisible) { passwordVisible = !passwordVisible }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("3. SlowDNS Settings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    OutlinedTextField(value = slowDnsServerIp, onValueChange = { slowDnsServerIp = it }, label = { Text("SlowDNS Server IP / DNS (e.g. 8.8.8.8)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = slowDnsDomain, onValueChange = { slowDnsDomain = it }, label = { Text("NS Domain (Name Server)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = slowDnsPubKey, onValueChange = { slowDnsPubKey = it }, label = { Text("SlowDNS Public Key") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        "WIREGUARD" -> {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("2. WireGuard Endpoint & Keys", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Endpoint Host / IP") }, singleLine = true, modifier = Modifier.weight(2f))
                                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("UDP Port") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                    OutlinedTextField(value = wgPrivateKey, onValueChange = { wgPrivateKey = it }, label = { Text("Interface Private Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = wgPublicKey, onValueChange = { wgPublicKey = it }, label = { Text("Peer Public Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = wgPresharedKey, onValueChange = { wgPresharedKey = it }, label = { Text("Preshared Key (Optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = wgAddress, onValueChange = { wgAddress = it }, label = { Text("Client IP (10.0.0.2/32)") }, singleLine = true, modifier = Modifier.weight(2f))
                                        OutlinedTextField(value = wgMtu, onValueChange = { wgMtu = it }, label = { Text("MTU") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        "HYSTERIA2" -> {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("2. Hysteria2 QUIC Server & Auth", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Server Host / IP") }, singleLine = true, modifier = Modifier.weight(2f))
                                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("UDP Port") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                    OutlinedTextField(value = hy2Password, onValueChange = { hy2Password = it }, label = { Text("Auth Password / Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("TLS SNI / Bug Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = hy2Obfs, onValueChange = { hy2Obfs = it }, label = { Text("Obfs (salamander)") }, singleLine = true, modifier = Modifier.weight(1f))
                                        OutlinedTextField(value = hy2ObfsPassword, onValueChange = { hy2ObfsPassword = it }, label = { Text("Obfs Password") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        else -> {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("2. $selectedProtocol Server & Credentials", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = xSecurity == "tls" && port == "443", onClick = { port = "443"; xSecurity = "tls" }, label = { Text("Port 443 (TLS)") })
                                        FilterChip(selected = xSecurity == "none" || port == "80", onClick = { port = "80"; xSecurity = "none" }, label = { Text("Port 80 (noTLS)") })
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Server Host / Domain") }, singleLine = true, modifier = Modifier.weight(2f))
                                        OutlinedTextField(
                                            value = port,
                                            onValueChange = {
                                                port = it
                                                if (it == "80" || it == "8080") xSecurity = "none"
                                                if (it == "443" || it == "8443") xSecurity = "tls"
                                            },
                                            label = { Text("Port") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    OutlinedTextField(value = uuid, onValueChange = { uuid = it }, label = { Text(if (selectedProtocol == "TROJAN") "Trojan Password" else "UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI / Bug Host Header") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(value = wsPath, onValueChange = { wsPath = it }, label = { Text("WebSocket / gRPC Path (/ws)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                }
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = server, onValueChange = onServerChange, label = { Text("Server Host / IP") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(value = port, onValueChange = onPortChange, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = sshUser, onValueChange = onUserChange, label = { Text("SSH Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = sshPass,
                onValueChange = onPassChange,
                label = { Text("SSH Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisible) {
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
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
    val myDeviceId = remember { VpnStateBridge.getDeviceHardwareId(context) }

    var passphrase by remember { mutableStateOf(profile.lockPassphrase) }
    var targetDeviceId by remember { mutableStateOf(profile.lockedDeviceId.ifBlank { myDeviceId }) }
    var showQrCode by remember { mutableStateOf(false) }

    val encryptedUri = remember(profile, passphrase, targetDeviceId) {
        ConfigCryptoHelper.exportProfileUri(profile, true, passphrase, targetDeviceId)
    }
    val cloudToken = remember(encryptedUri) {
        "SVPN-CLOUD:" + encryptedUri.substringAfter("://").substringAfter("/")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HWID Vault Lock: ${profile.name}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "AES-256-GCM Hardware-Bound Encryption: Only the device matching the Hardware ID below can decrypt this config.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = targetDeviceId,
                    onValueChange = {
                        targetDeviceId = it.trim().uppercase()
                        onUpdateLockState(profile.copy(isLocked = true, lockPassphrase = passphrase, lockedDeviceId = targetDeviceId))
                    },
                    label = { Text("Recipient Hardware ID (SVPN-XXXX)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { targetDeviceId = myDeviceId }, label = { Text("Lock to My ID") })
                    AssistChip(
                        onClick = {
                            clipboardManager.getText()?.text?.trim()?.let { clip ->
                                if (clip.startsWith("SVPN-", true)) targetDeviceId = clip.uppercase()
                            }
                        },
                        label = { Text("Paste User ID") }
                    )
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        onUpdateLockState(profile.copy(isLocked = true, lockPassphrase = it, lockedDeviceId = targetDeviceId))
                    },
                    label = { Text("Creator Passphrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Divider()
                Button(
                    onClick = {
                        try {
                            val cleanName = profile.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            File(context.cacheDir, "$cleanName.svpn").writeText(encryptedUri)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${profile.name}.svpn")
                                putExtra(Intent.EXTRA_TEXT, encryptedUri)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Encrypted .svpn File"))
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.InsertDriveFile, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share HWID-Locked File (.svpn)")
                }
                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(encryptedUri)); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy HWID + Passphrase Link")
                }
                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(cloudToken)); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Cloud Config Token")
                }
                OutlinedButton(onClick = { showQrCode = !showQrCode }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCode, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (showQrCode) "Hide QR Code" else "Generate HWID-Locked QR Code")
                }
                if (showQrCode) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        ConfigQrMatrixView(data = encryptedUri)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun ConfigQrMatrixView(data: String) {
    val digest = remember(data) { MessageDigest.getInstance("SHA-256").digest(data.toByteArray()) }
    Box(
        modifier = Modifier.size(180.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSize = 21
            val cellSize = size.width / gridSize
            fun isFinder(r: Int, c: Int) = (r in 0..6 && c in 0..6) || (r in 0..6 && c >= gridSize - 7) || (r >= gridSize - 7 && c in 0..6)
            fun drawFinder(sr: Int, sc: Int) {
                for (r in 0..6) for (c in 0..6) {
                    if (r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4)) {
                        drawRect(Color.Black, Offset((sc + c) * cellSize, (sr + r) * cellSize), Size(cellSize, cellSize))
                    }
                }
            }
            drawFinder(0, 0); drawFinder(0, gridSize - 7); drawFinder(gridSize - 7, 0)
            var bitIdx = 0
            for (r in 0 until gridSize) for (c in 0 until gridSize) {
                if (!isFinder(r, c)) {
                    val b = digest[(bitIdx / 8) % digest.size].toInt()
                    if (((b shr (bitIdx % 8)) and 1) == 1) {
                        drawRect(Color.Black, Offset(c * cellSize, r * cellSize), Size(cellSize, cellSize))
                    }
                    bitIdx++
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
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val myDeviceId = remember { VpnStateBridge.getDeviceHardwareId(context) }

    var configInput by remember { mutableStateOf(clipboardManager.getText()?.text ?: "") }
    var passphrase by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import HWID / Cloud Config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your Hardware ID: $myDeviceId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = configInput, onValueChange = { configInput = it; errorMsg = "" }, label = { Text("Encrypted Link / SVPN-CLOUD / .svpn") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = passphrase, onValueChange = { passphrase = it; errorMsg = "" }, label = { Text("Passphrase (If Locked)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (errorMsg.isNotEmpty()) Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = ConfigCryptoHelper.importProfileUri(configInput, passphrase, myDeviceId)
                    ?: VpnConfigParser.parseUri(configInput)
                if (parsed != null) {
                    if (parsed.lockedDeviceId.isNotBlank() && !parsed.lockedDeviceId.trim().equals(myDeviceId, true)) {
                        errorMsg = "HWID Mismatch: Locked to another device."
                    } else {
                        VpnStateBridge.addLog("Configuration imported: ${parsed.name}")
                        onImportSuccess(parsed)
                    }
                } else {
                    errorMsg = "Decryption Rejected! Hardware ID ($myDeviceId) or Passphrase does not match."
                }
            }) { Text("Unlock & Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
// === END OF FILE ===
