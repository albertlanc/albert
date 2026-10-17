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
import androidx.compose.ui.geometry.Offset
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
            VpnStateBridge.updateState("DISCONNECTED", "Permission Denied")
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
        var activeToolPage by remember { mutableStateOf("PING") }
        var showThemeMenu by remember { mutableStateOf(false) }

        val isConnected by viewModel.isConnected.collectAsState()
        val connectionState by viewModel.connectionState.collectAsState()
        val profiles by viewModel.profiles.collectAsState()
        val currentThemeMode by VpnStateBridge.themeMode.collectAsState()
        val activeProfile = profiles.firstOrNull { it.isSelected } ?: profiles.firstOrNull()
        val deviceId = remember { VpnStateBridge.getDeviceHardwareId(context) }

        val themeOptions = listOf(
            "AUTO" to "Auto (Day/Night)",
            "CYBER_DARK" to "Cyber Obsidian (Dark)",
            "SOLAR_LIGHT" to "Solar Crystal (Day)",
            "ROYAL_GOLD" to "Royal Amethyst & Gold",
            "EMERALD_MATRIX" to "Emerald Matrix"
        )

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(305.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            SmartVpnCrestLogo(size = 50.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("SMART VPN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                Text("v2.0 • Pro Tunnel Core", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("HARDWARE ID (HWID)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(deviceId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(deviceId)) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.ContentCopy, "Copy ID", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        val drawerTools = listOf(
                            Triple("PING", "Ping / Host Checker", Icons.Default.NetworkCheck),
                            Triple("GEOIP", "My IP & GeoIP Lookup", Icons.Default.Public),
                            Triple("HOTSHARE", "Wi-Fi Hotshare", Icons.Default.WifiTethering),
                            Triple("SSH_SETTINGS", "Advanced Settings", Icons.Default.Tune),
                            Triple("PAYLOAD_GEN", "Payload Generator", Icons.Default.Code),
                            Triple("SNI_HUNTER", "SNI / Bug Hunter", Icons.Default.BugReport)
                        )
                        drawerTools.forEach { (key, label, icon) ->
                            NavigationDrawerItem(
                                label = { Text(label) },
                                selected = pagerState.currentPage == 3 && activeToolPage == key,
                                icon = { Icon(icon, null) },
                                onClick = {
                                    activeToolPage = key
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
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
                                SmartVpnCrestLogo(size = 28.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("SMART VPN v2.0", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 1.sp)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Menu", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showThemeMenu = true }) {
                                    Icon(Icons.Default.Palette, "Theme", tint = MaterialTheme.colorScheme.primary)
                                }
                                DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                                    themeOptions.forEach { (key, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label, fontWeight = if (currentThemeMode == key) FontWeight.Bold else FontWeight.Normal) },
                                            onClick = { VpnStateBridge.themeMode.value = key; showThemeMenu = false }
                                        )
                                    }
                                }
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
                        NavigationBarItem(selected = pagerState.currentPage == 3, onClick = { scope.launch { pagerState.animateScrollToPage(3) } }, icon = { Icon(Icons.Default.Build, null) }, label = { Text("Tools") })
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
                            onImportClipboard = { viewModel.importFromClipboard(it) },
                            onSaveProfile = { viewModel.saveManualProfile(it) }
                        )
                        2 -> LogsScreen(logs = viewModel.logs.collectAsState().value)
                        3 -> NetworkToolsScreen(initialToolPage = activeToolPage)
                    }
                }
            }
        }
    }

    @Composable
    private fun SmartVpnSplashScreen(onEnterNow: () -> Unit) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF030714), Color(0xFF0A183D), Color(0xFF050B1E)))
            ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SmartVpnCrestLogo(size = 110.dp)
                Spacer(modifier = Modifier.height(20.dp))
                Text("SMART VPN v2.0", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 2.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("High-Speed Secure Tunnel & Multi-Protocol Core", fontSize = 12.sp, color = Color(0xFF00F0FF), textAlign = TextAlign.Center)
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
            VpnStateBridge.updateState("DISCONNECTED", "Idle")
        } else {
            requestNotificationPermissionIfNeeded()
            val intent = VpnService.prepare(this)
            if (intent != null) vpnRequest.launch(intent) else startVpnService()
        }
    }

    private fun startVpnService() {
        VpnStateBridge.updateState("CONNECTING", "Connecting...")
        ContextCompat.startForegroundService(this, Intent(this, SmartVpnService::class.java).apply { action = SmartVpnService.ACTION_START })
    }
}

@Composable
fun SmartVpnCrestLogo(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.46f

        val hexPath = Path().apply {
            for (i in 0..5) {
                val a = Math.toRadians((60.0 * i) - 90.0)
                val x = cx + (r * cos(a)).toFloat()
                val y = cy + (r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(hexPath, Brush.linearGradient(listOf(Color(0xFF0A2463), Color(0xFF040D26))))
        drawPath(hexPath, Brush.linearGradient(listOf(Color(0xFFFFC107), Color(0xFF00E676), Color(0xFF00F0FF))), style = Stroke(w * 0.06f, join = StrokeJoin.Round))

        val shieldPath = Path().apply {
            moveTo(cx, h * 0.20f)
            lineTo(w * 0.76f, h * 0.31f)
            lineTo(w * 0.76f, h * 0.53f)
            cubicTo(w * 0.76f, h * 0.69f, cx + w * 0.12f, h * 0.79f, cx, h * 0.84f)
            cubicTo(cx - w * 0.12f, h * 0.79f, w * 0.24f, h * 0.69f, w * 0.24f, h * 0.53f)
            lineTo(w * 0.24f, h * 0.31f)
            close()
        }
        drawPath(shieldPath, color = Color(0xFF00E676), style = Stroke(w * 0.04f, join = StrokeJoin.Round))

        val boltPath = Path().apply {
            moveTo(cx + w * 0.04f, h * 0.28f)
            lineTo(cx - w * 0.11f, h * 0.52f)
            lineTo(cx + w * 0.01f, h * 0.52f)
            lineTo(cx - w * 0.04f, h * 0.74f)
            lineTo(cx + w * 0.12f, h * 0.47f)
            lineTo(cx, h * 0.47f)
            close()
        }
        drawPath(boltPath, color = Color(0xFFFFC107))
    }
}
// === END OF FILE ===
