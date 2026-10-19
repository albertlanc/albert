package com.albert.smartvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.albert.smartvpn.ui.screens.ConfigsScreen
import com.albert.smartvpn.ui.screens.HomeScreen
import com.albert.smartvpn.ui.screens.LogsScreen
import com.albert.smartvpn.ui.screens.NetworkToolsScreen
import com.albert.smartvpn.ui.theme.SmartVpnTheme
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import com.albert.smartvpn.ui.viewmodel.VpnViewModel
import com.albert.smartvpn.vpn.SmartVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

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
                var showSplash by remember { mutableStateOf(!VpnStateBridge.hasShownSplash) }

                LaunchedEffect(Unit) {
                    if (showSplash) {
                        delay(2200L)
                        VpnStateBridge.hasShownSplash = true
                        showSplash = false
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    MainDashboardContent()

                    AnimatedVisibility(visible = showSplash, enter = fadeIn(), exit = fadeOut()) {
                        SmartVpnSplashScreen(onEnterNow = { showSplash = false })
                    }
                }
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
        var showQuickImportMenu by remember { mutableStateOf(false) }
        var showCloudImportModal by remember { mutableStateOf(false) }

        val isConnected by viewModel.isConnected.collectAsState()
        val connectionState by viewModel.connectionState.collectAsState()
        val profiles by viewModel.profiles.collectAsState()
        val currentThemeMode by VpnStateBridge.themeMode.collectAsState()
        val activeProfile = profiles.firstOrNull { it.isSelected } ?: profiles.firstOrNull()

        val themeOptions = listOf("Synthwave", "Default", "Cyberpunk", "Minimalist", "Corporate blue", "Matte cyan", "Monochrome")

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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFFFF00B8), Color(0xFF7000FF), Color(0xFF00E5FF))
                                    )
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            Column {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.35f),
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("SMART v3", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Color.White)
                                Text("VPN TUNNEL • v3.0.0 - PRO", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
                            }
                        }

                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            NavigationDrawerItem(
                                label = { Text("Settings") },
                                selected = false,
                                icon = { Icon(Icons.Default.Settings, null) },
                                onClick = { scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) } }
                            )
                            NavigationDrawerItem(
                                label = { Text("Response Checker") },
                                selected = false,
                                icon = { Icon(Icons.Default.NetworkCheck, null) },
                                onClick = { scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) } }
                            )
                            NavigationDrawerItem(
                                label = { Text("ShareNet") },
                                selected = false,
                                icon = { Icon(Icons.Default.WifiTethering, null) },
                                onClick = { scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) } }
                            )
                            NavigationDrawerItem(
                                label = { Text("User Manual & Guide") },
                                selected = false,
                                icon = { Icon(Icons.Default.MenuBook, null) },
                                onClick = { scope.launch { drawerState.close() }; showUserManual = true }
                            )
                            NavigationDrawerItem(
                                label = { Text("About") },
                                selected = false,
                                icon = { Icon(Icons.Default.Info, null) },
                                onClick = { scope.launch { drawerState.close() } }
                            )

                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Text("Extras", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))

                            NavigationDrawerItem(
                                label = { Text("Telegram Channel") },
                                selected = false,
                                icon = { Icon(Icons.Default.Send, null) },
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Smartking4luV"))
                                        context.startActivity(intent)
                                    } catch (_: Throwable) {}
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("Telegram Group") },
                                selected = false,
                                icon = { Icon(Icons.Default.Groups, null) },
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/lancecreation"))
                                        context.startActivity(intent)
                                    } catch (_: Throwable) {}
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("WhatsApp Community") },
                                selected = false,
                                icon = { Icon(Icons.Default.Chat, null) },
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.whatsapp.com/G9Jb6bgmvFqDAei8oVFU5k?s=cl&p=a&mlu=4&ilr=4"))
                                        context.startActivity(intent)
                                    } catch (_: Throwable) {}
                                }
                            )
                            NavigationDrawerItem(label = { Text("Network Tools") }, selected = false, icon = { Icon(Icons.Default.Build, null) }, onClick = { scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) } })
                            NavigationDrawerItem(label = { Text("Payload Generator") }, selected = false, icon = { Icon(Icons.Default.Code, null) }, onClick = { scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) } })
                            NavigationDrawerItem(
                                label = { Text("Theme Settings") },
                                selected = false,
                                icon = { Icon(Icons.Default.Palette, null) },
                                onClick = { scope.launch { drawerState.close() }; showThemeMenu = true }
                            )
                            NavigationDrawerItem(
                                label = { Text("Share App") },
                                selected = false,
                                icon = { Icon(Icons.Default.Share, null) },
                                onClick = { scope.launch { drawerState.close() }; shareSmartVpnApp() }
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107), contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.WorkspacePremium, null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("VIP ALL-ACCESS UNLOCKED", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                                Text("Smart v3", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, letterSpacing = 0.5.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFFFC107)
                                ) {
                                    Text(
                                        text = "PRO",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Open Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showUserManual = true }) {
                                Icon(Icons.Default.MenuBook, "User Manual", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { showQuickImportMenu = true }) {
                                Icon(Icons.Default.Add, "Add Config Menu", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(selected = pagerState.currentPage == 0, onClick = { scope.launch { pagerState.animateScrollToPage(0) } }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                        NavigationBarItem(selected = pagerState.currentPage == 1, onClick = { scope.launch { pagerState.animateScrollToPage(1) } }, icon = { Icon(Icons.Default.List, null) }, label = { Text("Configs") })
                        NavigationBarItem(selected = pagerState.currentPage == 2, onClick = { scope.launch { pagerState.animateScrollToPage(2) } }, icon = { Icon(Icons.Default.Article, null) }, label = { Text("Logs") })
                        NavigationBarItem(selected = pagerState.currentPage == 3, onClick = { scope.launch { pagerState.animateScrollToPage(3) } }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
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

        // QUICK IMPORT / CONFIG MENU DROPDOWN (Matches video at 00:05)[span_2](start_span)[span_2](end_span)[span_3](start_span)[span_3](end_span)
        if (showQuickImportMenu) {
            AlertDialog(
                onDismissRequest = { showQuickImportMenu = false },
                title = { Text("Configuration Options") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                showQuickImportMenu = false
                                clipboardManager.getText()?.text?.let { viewModel.importFromClipboard(it) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Import from Clipboard", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                        TextButton(
                            onClick = {
                                showQuickImportMenu = false
                                showCloudImportModal = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Import from Cloud", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                        TextButton(
                            onClick = {
                                showQuickImportMenu = false
                                scope.launch { pagerState.animateScrollToPage(1) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Manual Configuration", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showQuickImportMenu = false }) { Text("Cancel") } }
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
                            viewModel.importFromCloudUrl(cloudUrl)
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

        if (showThemeMenu) {
            AlertDialog(
                onDismissRequest = { showThemeMenu = false },
                title = { Text("App Theme") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        themeOptions.forEach { th ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { VpnStateBridge.themeMode.value = th; showThemeMenu = false }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = currentThemeMode == th, onClick = { VpnStateBridge.themeMode.value = th; showThemeMenu = false })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(th, fontWeight = if (currentThemeMode == th) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showThemeMenu = false }) { Text("Close") } }
            )
        }

        if (showUserManual) {
            AlertDialog(
                onDismissRequest = { showUserManual = false },
                title = { Text("Smart v3 VPN User Manual") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Important Tips — Read Carefully", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("• Disable Battery Optimization: Crucial to keep the tunnel active in background.\n• Turn Off Android Private DNS: Prevents upstream DNS interception.\n• Keep Device Time Set to Automatic: Required for TLS and WebSocket handshakes.\n• Mobile Data vs Wi-Fi Lock: HWID-locked configs will self-destruct if unauthorized.", style = MaterialTheme.typography.bodySmall)
                        Divider()
                        Text("Speed Acceleration & Multiplexing Engine", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("• Multiplexing (Any Mux): Multiplex concurrent TCP requests over a single persistent connection.\n• SSH Multiplexing & TCP_NODELAY: Disables Nagle algorithm for instant packet delivery.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = { showUserManual = false }) {
                        Text("I Understand & Got It")
                    }
                }
            )
        }
    }

    @Composable
    private fun SmartVpnSplashScreen(onEnterNow: () -> Unit) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF160724), Color(0xFF2B1045), Color(0xFF12061E))
            )),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(56.dp))
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("SMART v3", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 2.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("PRO VPN TUNNEL • v3.0.0", fontSize = 12.sp, color = Color(0xFF00E5FF), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(36.dp))
                Button(onClick = onEnterNow, shape = RoundedCornerShape(12.dp)) {
                    Text("Enter Dashboard →", fontWeight = FontWeight.Bold)
                }
            }
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
                putExtra(Intent.EXTRA_TEXT, "Download Smart v3 PRO — Advanced Multi-Protocol Stealth Tunnel (SSH, VLESS, VMess, Trojan, ShadowSocks, WireGuard, OpenVPN, Hysteria2 & SlowDNS).")
            }
            startActivity(Intent.createChooser(sendIntent, "Share Smart v3"))
        } catch (_: Throwable) {}
    }
}
