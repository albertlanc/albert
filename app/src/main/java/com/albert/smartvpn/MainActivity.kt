package com.albert.smartvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.albert.smartvpn.ui.screens.ConfigsScreen
import com.albert.smartvpn.ui.screens.HomeScreen
import com.albert.smartvpn.ui.screens.LogsScreen
import com.albert.smartvpn.ui.screens.NetworkToolsScreen
import com.albert.smartvpn.ui.theme.SmartVpnTheme
import com.albert.smartvpn.ui.viewmodel.ConfigCryptoHelper
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import com.albert.smartvpn.ui.viewmodel.VpnViewModel
import com.albert.smartvpn.vpn.SmartVpnService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (content.isNotBlank()) {
                    viewModel.importFromClipboard(content)
                }
            } catch (t: Throwable) {
                viewModel.showToastPill("Failed to read file: ${t.message}")
            }
        }
    }

    private val vpnRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
            VpnStateBridge.addLog("Disconnected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            SmartVpnTheme {
                MainDashboardContent()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun MainDashboardContent() {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
        var showThemeMenu by remember { mutableStateOf(false) }
        var showUserManual by remember { mutableStateOf(false) }
        var showPlusMenu by remember { mutableStateOf(false) }
        var showCloudImportModal by remember { mutableStateOf(false) }
        var showQrScannerModal by remember { mutableStateOf(false) }
        var showAboutModal by remember { mutableStateOf(false) }

        val isConnected by viewModel.isConnected.collectAsState()
        val connectionState by viewModel.connectionState.collectAsState()
        val profiles by viewModel.profiles.collectAsState()
        val currentThemeMode by VpnStateBridge.themeMode.collectAsState()
        val triggerQr by VpnStateBridge.triggerQrScannerDialog.collectAsState()
        val activeProfile = profiles.firstOrNull { it.isSelected } ?: profiles.firstOrNull()

        LaunchedEffect(triggerQr) {
            if (triggerQr) {
                VpnStateBridge.triggerQrScannerDialog.value = false
                showQrScannerModal = true
            }
        }

        val themeOptions = listOf(
            "Default",
            "Cyberpunk",
            "Synthwave",
            "Minimalist",
            "Corporate blue",
            "Matte cyan",
            "Monochrome"
        )

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(310.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Gradient Header matching vid88.mp4 at 00:06[cite: 3]
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(165.dp)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFFFF40E5), Color(0xFFA044FF), Color(0xFF00E5FF))
                                    )
                                )
                                .padding(18.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            Column {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.White.copy(alpha = 0.2f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("🛡️", fontSize = 26.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "SMART V3",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 21.sp,
                                    color = Color.White,
                                    letterSpacing = 1.5.sp
                                )
                                Text(
                                    text = "VPN TUNNEL",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.9f),
                                    letterSpacing = 2.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.Black.copy(alpha = 0.35f)
                                    ) {
                                        Text(
                                            text = "v3.0 - PRO",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("by Smart v3", fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f))
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            NavigationDrawerItem(
                                label = { Text("Settings") },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "SETTINGS"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("Response Checker") },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "RESPONSE_CHECKER"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("ShareNet") },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "SHARENET"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("📖  User Manual & Guide") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showUserManual = true
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("About") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showAboutModal = true
                                }
                            )

                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "Extras",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            )

                            NavigationDrawerItem(
                                label = { Text("Telegram Channel") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    openExternalUrl("https://t.me/Smartking4luV")
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("Telegram Group") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    openExternalUrl("https://t.me/lancecreation")
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("WhatsApp Community") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    openExternalUrl("https://chat.whatsapp.com/G9Jb6bgmvFqDAei8oVFU5k?s=cl&p=a&mlu=4&ilr=4")
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("Network Tools") },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "NETWORK_TOOLS"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("Payload Generator") },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "PAYLOAD_GEN"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("Theme Settings") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showThemeMenu = true
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("🔗  Share App") },
                                selected = false,
                                icon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    shareSmartVpnApp()
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .padding(horizontal = 6.dp)
                            ) {
                                Text("👑  VIP ALL-ACCESS UNLOCKED", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Smart v3",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    color = Color(0xFF00E5FF)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFFFB300),
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "PRO",
                                            color = Color.Black,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Open Menu", tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { showUserManual = true }) {
                                Text("📖", fontSize = 18.sp)
                            }

                            // Top-right '+' button that turns red '✕' when menu is open (Matches 91443.jpg)[cite: 5]
                            Box {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (showPlusMenu) Color(0xFFD50000) else Color(0xFF1F2430),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(38.dp)
                                        .clickable { showPlusMenu = !showPlusMenu }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (showPlusMenu) Icons.Default.Close else Icons.Default.Add,
                                            contentDescription = "Add Menu",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showPlusMenu,
                                    onDismissRequest = { showPlusMenu = false },
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface)
                                        .width(235.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Import from Clipboard", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            val clip = clipboardManager.getText()?.text ?: ""
                                            viewModel.importFromClipboard(clip)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Import from File", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            try {
                                                filePickerLauncher.launch("*/*")
                                            } catch (_: Throwable) {}
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Import from Cloud", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            showCloudImportModal = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Scan QR Code", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            showQrScannerModal = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Manual Configuration", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            scope.launch {
                                                pagerState.animateScrollToPage(1)
                                                VpnStateBridge.triggerManualConfigDialog.value = true
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export Active Config 🔒", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            scope.launch {
                                                pagerState.animateScrollToPage(1)
                                                VpnStateBridge.triggerExportActiveDialog.value = true
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export Load Balance Bundle ⚖️", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            if (profiles.size < 2) {
                                                viewModel.showToastPill("You need at least 2 configurations in your vault to create a load balance bundle.")
                                            } else {
                                                val bundleUri = ConfigCryptoHelper.exportLoadBalanceBundle(profiles)
                                                clipboardManager.setText(AnnotatedString(bundleUri))
                                                viewModel.showToastPill("Load Balance Bundle copied to clipboard")
                                            }
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF391F5B))
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF13091E)) {
                        NavigationBarItem(
                            selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            icon = { Text("🏠", fontSize = 18.sp) },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            icon = { Text("📁", fontSize = 18.sp) },
                            label = { Text("Configs") }
                        )
                        NavigationBarItem(
                            selected = pagerState.currentPage == 2,
                            onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                            icon = { Text("📝", fontSize = 18.sp) },
                            label = { Text("Logs") }
                        )
                        NavigationBarItem(
                            selected = pagerState.currentPage == 3,
                            onClick = {
                                VpnStateBridge.activeDrawerScreen.value = "SETTINGS"
                                scope.launch { pagerState.animateScrollToPage(3) }
                            },
                            icon = { Text("⚙️", fontSize = 18.sp) },
                            label = { Text("Settings") }
                        )
                    }
                }
            ) { innerPadding ->
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(innerPadding)) { page ->
                    when (page) {
                        0 -> HomeScreen(
                            isConnected = isConnected,
                            activeProfile = activeProfile,
                            onToggleConnection = {
                                if (activeProfile == null) scope.launch { pagerState.animateScrollToPage(1) }
                                else toggleVpn(connectionState != "DISCONNECTED")
                            },
                            onPingClick = { viewModel.executeConnectedPing(activeProfile) }
                        )
                        1 -> ConfigsScreen(
                            profiles = profiles,
                            onSelectProfile = { viewModel.selectProfile(it.id) },
                            onDeleteProfile = { viewModel.deleteProfile(it) },
                            onDuplicateProfile = { viewModel.duplicateProfile(it) },
                            onPingProfile = { viewModel.pingSingleProfile(it) },
                            onPingAllProfiles = { viewModel.pingAllProfiles() },
                            onImportClipboard = { viewModel.importFromClipboard(it) },
                            onSaveProfile = { viewModel.saveManualProfile(it) }
                        )
                        2 -> LogsScreen(logs = viewModel.logs.collectAsState().value)
                        3 -> NetworkToolsScreen()
                    }
                }
            }
        }

        // Import from Cloud Modal (Matches 91444.jpg & 91603.mp4)[cite: 6, 41]
        if (showCloudImportModal) {
            var cloudUrl by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCloudImportModal = false },
                title = { Text("Import from Cloud", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Paste the cloud URL (e.g., https://paste.rs/xyz)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    TextButton(onClick = {
                        if (cloudUrl.isNotBlank()) {
                            viewModel.importFromCloudUrl(cloudUrl)
                            showCloudImportModal = false
                        }
                    }) {
                        Text("Import", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCloudImportModal = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }

        // Scan QR Code Modal
        if (showQrScannerModal) {
            var manualQrPayload by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showQrScannerModal = false },
                title = { Text("Scan / Decode QR Config", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Scan a v2ray or SSH QR code or paste decoded QR URI:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = manualQrPayload,
                            onValueChange = { manualQrPayload = it },
                            label = { Text("svpn:// / vless:// / vmess:// / trojan://") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                showQrScannerModal = false
                                try { filePickerLauncher.launch("*/*") } catch (_: Throwable) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Select Config File from Device")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (manualQrPayload.isNotBlank()) {
                            viewModel.importFromClipboard(manualQrPayload)
                        }
                        showQrScannerModal = false
                    }) {
                        Text("Import", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showQrScannerModal = false }) { Text("Close") }
                }
            )
        }

        // App Theme Dialog (Matches vid88.mp4 at 00:29)[cite: 3]
        if (showThemeMenu) {
            AlertDialog(
                onDismissRequest = { showThemeMenu = false },
                title = { Text("App Theme", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        themeOptions.forEach { th ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        VpnStateBridge.themeMode.value = th
                                        showThemeMenu = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentThemeMode == th,
                                    onClick = {
                                        VpnStateBridge.themeMode.value = th
                                        showThemeMenu = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(th, fontWeight = if (currentThemeMode == th) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeMenu = false }) {
                        Text("Close", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }

        // About Dialog
        if (showAboutModal) {
            val hwid = remember { VpnStateBridge.getDeviceHardwareId(context) }
            AlertDialog(
                onDismissRequest = { showAboutModal = false },
                title = { Text("Smart v3 PRO VPN Tunnel", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Version: 3.0.0 (Build 300)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Hardware ID (HWID): $hwid", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("Multi-Protocol Stealth Engine supporting 17 SSH/TLS/DNSTT modes, VMess, VLESS Reality, Trojan, ShadowSocks, WireGuard, OpenVPN, and Hysteria2 QUIC.", fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(hwid))
                        viewModel.showToastPill("Copied HWID to clipboard")
                        showAboutModal = false
                    }) {
                        Text("Copy HWID & Close")
                    }
                }
            )
        }

        // 6-Tab User Manual Dialog (Matches vid88.mp4 at 01:20 - 01:57)[cite: 3]
        if (showUserManual) {
            SmartV3UserManualDialog(onDismiss = { showUserManual = false })
        }
    }

    @Composable
    private fun SmartV3UserManualDialog(onDismiss: () -> Unit) {
        var selectedTab by remember { mutableStateOf("All") }
        val dontShowAgain by VpnStateBridge.dontShowManualOnStartup.collectAsState()
        val tabs = listOf("All", "⚠️ Tips", "⚡ Speed & Mux", "🌐 Protocols", "⚖️ Clusters", "🔒 Security & Expiry")

        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(540.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📖", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Smart v3 VPN User Manual", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                    Text("Complete Architecture & Features Master Guide", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tabs.forEach { tab ->
                                val isSel = selectedTab == tab
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isSel) Color(0xFFFF00D6) else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { selectedTab = tab }
                                ) {
                                    Text(
                                        text = tab,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (selectedTab in listOf("All", "⚠️ Tips")) {
                                ManualCard(
                                    title = "⚠️ IMPORTANT TIPS — READ CAREFULLY",
                                    body = "• Disable Battery Optimization: Set Smart v3 Battery to 'Unrestricted'.\n• Turn Off Android Private DNS: Set Private DNS to 'Off' or 'Automatic'.\n• Keep Device Time Set to Automatic: Required for TLS & Reality handshakes.\n• Single-Use Burn-After-Use Profiles: Self-destruct upon disconnect."
                                )
                            }
                            if (selectedTab in listOf("All", "⚡ Speed & Mux")) {
                                ManualCard(
                                    title = "🚀 Speed Acceleration & Multiplexing Engine",
                                    body = "• Network Speed Booster (🚀 Turbo): Dynamically doubles internal TCP/UDP socket buffers (from 1 MB to 2 MB).\n• Multiplexing (Xray Mux): Transports multiple concurrent TCP requests over a single persistent TLS connection.\n• SSH Multiplexing & TCP_NODELAY: Disables Nagle's buffering algorithm for instant packet delivery."
                                )
                            }
                            if (selectedTab in listOf("All", "🌐 Protocols")) {
                                ManualCard(
                                    title = "🌐 Tunneling Protocols & Engines Guide",
                                    body = "• SSH (17 Modes): Direct, Proxy, Payload, TLS 1.3 SNI, and SlowDNS/DNSTT.\n• V2Ray / Xray: VMess, VLESS Reality (XTLS-Vision), Trojan, and ShadowSocks.\n• WireGuard & Hysteria2: High-speed UDP & QUIC v2 congestion acceleration."
                                )
                            }
                            if (selectedTab in listOf("All", "⚖️ Clusters")) {
                                ManualCard(
                                    title = "⚖️ Load Balancing & Cluster Bundles",
                                    body = "• Multi-Server Load Balancing: Enable Load Balancing on the Home screen to balance traffic across multiple nodes.\n• Load Balance Bundles: Export multiple servers into a single encrypted .svpn bundle."
                                )
                            }
                            if (selectedTab in listOf("All", "🔒 Security & Expiry")) {
                                ManualCard(
                                    title = "🔒 Advanced Security, Expiry & Quotas",
                                    body = "• Fixed Calendar & Sliding Duration (Trial Timer).\n• Active Tunnel Uptime Limit (Hours) & Data Quota Cap (GB).\n• Hardware ID (HWID), Root, Emulator & Sniffer Locks."
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dontShowAgain,
                                onCheckedChange = { VpnStateBridge.dontShowManualOnStartup.value = it }
                            )
                            Text("Don't show on startup", fontSize = 11.sp)
                        }
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00D6)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("✓ I Understand & Got It", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ManualCard(title: String, body: String) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            viewModel.showToastPill("Could not open link: ${t.message}")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun toggleVpn(isActiveOrConnecting: Boolean) {
        if (isActiveOrConnecting) {
            startService(Intent(this, SmartVpnService::class.java).apply { action = SmartVpnService.ACTION_STOP })
            VpnStateBridge.updateState("DISCONNECTED", "NOT CONNECTED")
        } else {
            requestNotificationPermissionIfNeeded()
            val intent = VpnService.prepare(this)
            if (intent != null) vpnRequest.launch(intent) else startVpnService()
        }
    }

    private fun startVpnService() {
        VpnStateBridge.updateState("CONNECTING", "CONNECTING...")
        ContextCompat.startForegroundService(this, Intent(this, SmartVpnService::class.java).apply { action = SmartVpnService.ACTION_START })
    }

    private fun shareSmartVpnApp() {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Smart v3 PRO VPN")
                putExtra(Intent.EXTRA_TEXT, "Download Smart v3 PRO — Multi-Protocol Stealth VPN Tunnel (https://t.me/Smartking4luV)")
            }
            startActivity(Intent.createChooser(sendIntent, "Share Smart v3"))
        } catch (_: Throwable) {}
    }
}
