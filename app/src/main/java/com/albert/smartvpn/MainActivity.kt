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
import androidx.compose.animation.core.*
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
            viewModel.addLog("Disconnected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            SmartVpnTheme {
                // Stage 0 = Done, Stage 1 = Animated Cinema Video Graphics, Stage 2 = Feature Splash Screen
                var introStage by remember {
                    mutableIntStateOf(if (!VpnStateBridge.hasShownSplash && !VpnStateBridge.isConnected.value) 1 else 0)
                }

                LaunchedEffect(introStage) {
                    if (introStage == 1) {
                        delay(2200L)
                        introStage = 2
                    } else if (introStage == 2) {
                        delay(2800L)
                        VpnStateBridge.hasShownSplash = true
                        introStage = 0
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    MainDashboardContent()

                    AnimatedVisibility(visible = introStage == 1, enter = fadeIn(), exit = fadeOut()) {
                        CyberCinemaVideoIntroScreen(onSkip = { introStage = 2 })
                    }

                    AnimatedVisibility(visible = introStage == 2, enter = fadeIn(), exit = fadeOut()) {
                        QuantumCyberSplashScreen(
                            onEnterNow = {
                                VpnStateBridge.hasShownSplash = true
                                introStage = 0
                            }
                        )
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
            "AUTO" to "Auto (Morning Light / Night Dark)",
            "CYBER_DARK" to "Theme 1: Cyber Obsidian (Dark)",
            "SOLAR_LIGHT" to "Theme 2: Solar Crystal (Daylight)",
            "ROYAL_GOLD" to "Theme 3: Royal Amethyst & Gold",
            "EMERALD_MATRIX" to "Theme 4: Emerald Matrix (Green)",
            "CRIMSON_PHANTOM" to "Theme 5: Crimson Phantom (Red Ops)",
            "ARCTIC_FROST" to "Theme 6: Arctic Frost (Ice Light)"
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
                            SmartVpnCrestLogo(size = 52.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("SMART VPN ULTRA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                Text("v6.0 BBR • Multi-Protocol Core", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("HARDWARE ID (FOR CONFIG LOCK)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                            Triple("HOTSHARE", "Wi-Fi Hotshare (Share VPN)", Icons.Default.WifiTethering),
                            Triple("SSH_SETTINGS", "Advanced SSH Settings", Icons.Default.Tune),
                            Triple("PAYLOAD_GEN", "HTTP/WS Payload Generator", Icons.Default.Code),
                            Triple("SNI_HUNTER", "SNI / Bug Host Hunter", Icons.Default.BugReport)
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

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        NavigationDrawerItem(
                            label = { Text("Battery Keep-Alive (Anti-Kill)") },
                            selected = false,
                            icon = { Icon(Icons.Default.BatteryChargingFull, null) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                try {
                                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                                } catch (_: Throwable) {}
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("Share Smart VPN App") },
                            selected = false,
                            icon = { Icon(Icons.Default.Share, null) },
                            onClick = { scope.launch { drawerState.close() }; shareSmartVpnApp() }
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmartVpnCrestLogo(size = 30.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("SMART VPN ULTRA", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 1.sp)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Open Menu", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showThemeMenu = true }) {
                                    Icon(Icons.Default.Palette, "Select Theme", tint = MaterialTheme.colorScheme.primary)
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

    /**
     * STAGE 1: Pre-Splash Animated 3D Cyber-Globe & Warp Tunnel Motion Graphics Cinema Scene
     */
    @Composable
    private fun CyberCinemaVideoIntroScreen(onSkip: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition(label = "Cinema")
        val warpProgress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "Warp"
        )
        val globeAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4500, easing = LinearEasing)),
            label = "Globe"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF0E2A66), Color(0xFF04091A), Color(0xFF01030A))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = size.minDimension * 0.45f

                // Animated Warp-Speed Laser Beams radiating from the core
                for (i in 0 until 24) {
                    val angle = Math.toRadians((i * 15.0) + globeAngle * 0.4)
                    val innerR = maxR * 0.25f * warpProgress
                    val outerR = maxR * (0.45f + 0.55f * warpProgress)
                    val sx = cx + (innerR * cos(angle)).toFloat()
                    val sy = cy + (innerR * sin(angle)).toFloat()
                    val ex = cx + (outerR * cos(angle)).toFloat()
                    val ey = cy + (outerR * sin(angle)).toFloat()
                    drawLine(
                        color = if (i % 2 == 0) Color(0xFF00F0FF).copy(alpha = 0.65f) else Color(0xFF00E676).copy(alpha = 0.55f),
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // 3D Holographic Cyber Globe Rings
                for (ring in 1..4) {
                    drawCircle(
                        color = Color(0xFF00F0FF).copy(alpha = 0.25f),
                        radius = maxR * (ring / 5f),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                rotate(globeAngle) {
                    drawArc(
                        color = Color(0xFFFFC107),
                        startAngle = 0f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx - maxR * 0.6f, cy - maxR * 0.6f),
                        size = androidx.compose.ui.geometry.Size(maxR * 1.2f, maxR * 1.2f),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color(0xFF00E676),
                        startAngle = 180f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx - maxR * 0.6f, cy - maxR * 0.6f),
                        size = androidx.compose.ui.geometry.Size(maxR * 1.2f, maxR * 1.2f),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                SmartVpnCrestLogo(size = 112.dp)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "SMART VPN ULTRA",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 3.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "v6.0 BBR QUANTUM ACCELERATION ENGINE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFC107),
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(32.dp))
                TextButton(onClick = onSkip) {
                    Text("Continue →", color = Color(0xFF00F0FF), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    /**
     * STAGE 2: Detailed Feature & Capability Showcase Splash Screen
     */
    @Composable
    private fun QuantumCyberSplashScreen(onEnterNow: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition(label = "Splash")
        val orbitAngle by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "Orbit")
        var progressStep by remember { mutableIntStateOf(1) }
        LaunchedEffect(Unit) { for (i in 1..5) { progressStep = i; delay(480L) } }

        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF030714), Color(0xFF0A183D), Color(0xFF050B1E)))
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(116.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            rotate(orbitAngle) {
                                drawArc(Color(0xFFFFC107), 0f, 120f, false, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
                                drawArc(Color(0xFF00E676), 180f, 120f, false, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }
                        SmartVpnCrestLogo(size = 80.dp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("SMART VPN ULTRA v6.0", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 1.8.sp)
                    Text("WHAT SMART VPN CAN DO • CORE CAPABILITIES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F0FF), textAlign = TextAlign.Center)
                }

                Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    SplashFeatureRow(Icons.Default.RocketLaunch, Color(0xFFFFC107), "BBRv3 Overall Speed Congestion Engine", "Maximizes throughput on SSH+WS+SNI+Payload, VLESS, VMess (443 & 80), Trojan, WireGuard & Hysteria2.")
                    SplashFeatureRow(Icons.Default.Dns, Color(0xFF00E676), "Auto-Engaged SlowDNS Multi-Port Boost", "Connects SlowDNS on any port and automatically activates 15x parallel DNS session boosting on connect.")
                    SplashFeatureRow(Icons.Default.Fingerprint, Color(0xFF00F0FF), "Hardware ID (HWID) AES-256-GCM Vault", "Locks exported .svpn files, QR codes, and Cloud tokens to a specific phone's Hardware ID & passphrase.")
                    SplashFeatureRow(Icons.Default.Speed, Color(0xFF7C4DFF), "Live Tunnel Ping, GeoIP & Wi-Fi Hotshare", "One-tap live tunnel latency & speed ping on the home screen, plus Wi-Fi Hotspot VPN tethering.")
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(progress = progressStep / 5f, color = Color(0xFFFFC107), trackColor = Color(0xFF152657), modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("BBR CORE READY • ${progressStep * 20}%", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF9FB3C8))
                        TextButton(onClick = onEnterNow) { Text("Launch Dashboard →", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SplashFeatureRow(icon: ImageVector, accent: Color, title: String, subtitle: String) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1536)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
        ) {
            Row(modifier = Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text(subtitle, fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF9FB3C8))
                }
            }
        }
    }

    private fun shareSmartVpnApp() {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Smart VPN Ultra v6.0 BBR")
                putExtra(Intent.EXTRA_TEXT, "Download Smart VPN Ultra v6.0 — BBR Multi-Protocol Stealth Tunnel (SSH, WS+SNI+Payload, SlowDNS Auto-Boost, VLESS/VMess/Trojan, WireGuard & Hysteria2).")
            }
            startActivity(Intent.createChooser(sendIntent, "Share Smart VPN Ultra"))
        } catch (_: Throwable) {}
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
