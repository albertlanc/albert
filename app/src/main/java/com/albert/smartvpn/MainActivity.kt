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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.addLog("Note: Enable Notifications in App Settings to see the VPN status bar icon.")
        }
    }

    private val vpnRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            VpnStateBridge.updateState("DISCONNECTED", "Permission Denied")
            viewModel.addLog("VPN permission was denied by the user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            SmartVpnTheme {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(2800L)
                    showSplash = false
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    MainDashboardContent()

                    AnimatedVisibility(
                        visible = showSplash,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        QuantumCyberSplashScreen()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainDashboardContent() {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        var selectedTab by remember { mutableIntStateOf(0) }
        var activeToolPage by remember { mutableStateOf("PING") }

        val isConnected by viewModel.isConnected.collectAsState()
        val connectionState by viewModel.connectionState.collectAsState()
        val profiles by viewModel.profiles.collectAsState()
        val activeProfile = profiles.firstOrNull { it.isSelected } ?: profiles.firstOrNull()
        val deviceId = remember { VpnStateBridge.getDeviceHardwareId(context) }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color(0xFF070E24),
                    drawerContentColor = Color.White,
                    modifier = Modifier.width(310.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00F0FF).copy(alpha = 0.2f))
                                    .border(1.5.dp, Color(0xFF00F0FF), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFF00F0FF)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "SMART VPN ULTRA",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF00F0FF)
                                )
                                Text(
                                    text = "SSH • SlowDNS • Xray • WG • HY2",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF9FB3C8)
                                )
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF101D42)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "DEVICE ID (FOR CONFIG LOCK)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF00F0FF),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = deviceId,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(deviceId))
                                            viewModel.addLog("Copied Device ID: $deviceId")
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy Device ID",
                                            tint = Color(0xFF00F0FF),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF1E2D5A))

                        NavigationDrawerItem(
                            label = { Text("Ping / Host Checker") },
                            selected = selectedTab == 3 && activeToolPage == "PING",
                            icon = { Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = Color(0xFF00F0FF)) },
                            onClick = {
                                activeToolPage = "PING"
                                selectedTab = 3
                                scope.launch { drawerState.close() }
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("My IP & GeoIP Lookup") },
                            selected = selectedTab == 3 && activeToolPage == "GEOIP",
                            icon = { Icon(Icons.Default.Public, contentDescription = null, tint = Color(0xFF00E676)) },
                            onClick = {
                                activeToolPage = "GEOIP"
                                selectedTab = 3
                                scope.launch { drawerState.close() }
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("Advanced SSH Settings") },
                            selected = selectedTab == 3 && activeToolPage == "SSH_SETTINGS",
                            icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFFFFAB00)) },
                            onClick = {
                                activeToolPage = "SSH_SETTINGS"
                                selectedTab = 3
                                scope.launch { drawerState.close() }
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("HTTP/WS Payload Generator") },
                            selected = selectedTab == 3 && activeToolPage == "PAYLOAD_GEN",
                            icon = { Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFF7C4DFF)) },
                            onClick = {
                                activeToolPage = "PAYLOAD_GEN"
                                selectedTab = 3
                                scope.launch { drawerState.close() }
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("SNI / Bug Host Hunter") },
                            selected = selectedTab == 3 && activeToolPage == "SNI_HUNTER",
                            icon = { Icon(Icons.Default.BugReport, contentDescription = null, tint = Color(0xFF00F0FF)) },
                            onClick = {
                                activeToolPage = "SNI_HUNTER"
                                selectedTab = 3
                                scope.launch { drawerState.close() }
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("Hotshare / Wi-Fi Tether") },
                            selected = selectedTab == 3 && activeToolPage == "HOTSHARE",
                            icon = { Icon(Icons.Default.WifiTethering, contentDescription = null, tint = Color(0xFF00E676)) },
                            onClick = {
                                activeToolPage = "HOTSHARE"
                                selectedTab = 3
                                scope.launch { drawerState.close() }
                            }
                        )

                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF1E2D5A))

                        NavigationDrawerItem(
                            label = { Text("Battery Keep-Alive (Anti-Kill)") },
                            selected = false,
                            icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color(0xFFFFAB00)) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName")
                                    )
                                    startActivity(intent)
                                } catch (_: Throwable) {
                                    viewModel.addLog("Opened Battery Optimization settings.")
                                }
                            }
                        )

                        NavigationDrawerItem(
                            label = { Text("Share Smart VPN App") },
                            selected = false,
                            icon = { Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF00F0FF)) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                shareSmartVpnApp()
                            }
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = "SMART VPN ULTRA",
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open Menu",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFF070E24)
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF070E24)) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.List, contentDescription = null) },
                            label = { Text("Configs") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Article, contentDescription = null) },
                            label = { Text("Logs") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Build, contentDescription = null) },
                            label = { Text("Tools") }
                        )
                    }
                }
            ) { innerPadding ->
                when (selectedTab) {
                    0 -> HomeScreen(
                        isConnected = isConnected,
                        activeProfile = activeProfile,
                        onToggleConnection = {
                            if (activeProfile == null) {
                                viewModel.addLog("Cannot connect: Select or create a profile in Configs first.")
                                selectedTab = 1
                            } else {
                                toggleVpn(connectionState != "DISCONNECTED")
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                    1 -> ConfigsScreen(
                        profiles = profiles,
                        onSelectProfile = { viewModel.selectProfile(it.id) },
                        onDeleteProfile = { viewModel.deleteProfile(it) },
                        onImportClipboard = { viewModel.importFromClipboard(it) },
                        onSaveProfile = { viewModel.saveManualProfile(it) },
                        modifier = Modifier.padding(innerPadding)
                    )
                    2 -> LogsScreen(
                        logs = viewModel.logs.collectAsState().value,
                        modifier = Modifier.padding(innerPadding)
                    )
                    3 -> NetworkToolsScreen(
                        initialToolPage = activeToolPage,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    @Composable
    private fun QuantumCyberSplashScreen() {
        val infiniteTransition = rememberInfiniteTransition(label = "SplashRadar")
        val radarAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing)
            ),
            label = "RadarSweep"
        )
        val reverseAngle by infiniteTransition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing)
            ),
            label = "ReverseRing"
        )

        var bootStep by remember { mutableIntStateOf(1) }
        LaunchedEffect(Unit) {
            for (step in 1..5) {
                bootStep = step
                delay(480L)
            }
        }

        val bootMessages = listOf(
            "[OK] Initializing Quantum Stealth Core v4.0...",
            "[OK] Mounting Go Xray-Core (VLESS / VMess / Trojan)...",
            "[OK] Activating SSH + WS + SNI + Payload Engine...",
            "[OK] Starting Native SlowDNS (DNSTT) & Hysteria2 QUIC...",
            "[OK] Binding WireGuard & Hardware Enclave Lock..."
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF02050D),
                            Color(0xFF0A1940),
                            Color(0xFF04091A)
                        )
                    )
                )
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.size(164.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        rotate(radarAngle) {
                            drawArc(
                                color = Color(0xFF00F0FF),
                                startAngle = 0f,
                                sweepAngle = 135f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = Color(0xFF00E676),
                                startAngle = 180f,
                                sweepAngle = 135f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        rotate(reverseAngle) {
                            drawArc(
                                color = Color(0xFF7C4DFF),
                                startAngle = 30f,
                                sweepAngle = 100f,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(18.dp.toPx(), 18.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - 36.dp.toPx(),
                                    size.height - 36.dp.toPx()
                                ),
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = Color(0xFFFFAB00),
                                startAngle = 210f,
                                sweepAngle = 100f,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(18.dp.toPx(), 18.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - 36.dp.toPx(),
                                    size.height - 36.dp.toPx()
                                ),
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF00F0FF).copy(alpha = 0.45f),
                                        Color(0xFF0A1940)
                                    )
                                )
                            )
                            .border(2.dp, Color(0xFF00F0FF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF00F0FF),
                            modifier = Modifier.size(52.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = "SMART VPN ULTRA",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 2.5.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "QUANTUM MULTI-PROTOCOL STEALTH ARCHITECTURE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00F0FF),
                    letterSpacing = 1.2.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    listOf("SSH+WS", "SLOWDNS", "XRAY", "WIREGUARD", "HY2").forEach { badge ->
                        Surface(
                            color = Color(0xFF112252),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.border(1.dp, Color(0xFF00F0FF).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        ) {
                            Text(
                                text = badge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060E24)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1E326E), RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        bootMessages.take(bootStep).forEach { msg ->
                            Text(
                                text = msg,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF00E676)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                LinearProgressIndicator(
                    progress = bootStep / 5f,
                    color = Color(0xFF00F0FF),
                    trackColor = Color(0xFF13224C),
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "LOADING ENGINES • ${bootStep * 20}%",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF9FB3C8)
                )
            }
        }
    }

    private fun shareSmartVpnApp() {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Smart VPN Ultra")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Download Smart VPN Ultra — Multi-Protocol Stealth Tunnel (SSH, WS+SNI+Payload, SlowDNS, VLESS/VMess/Trojan, WireGuard & Hysteria2)."
                )
            }
            startActivity(Intent.createChooser(sendIntent, "Share Smart VPN Ultra"))
        } catch (t: Throwable) {
            viewModel.addLog("Share app error: ${t.message}")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun toggleVpn(isActiveOrConnecting: Boolean) {
        if (isActiveOrConnecting) {
            viewModel.addLog("Stopping tunnel...")
            val stopIntent = Intent(this, SmartVpnService::class.java).apply {
                action = SmartVpnService.ACTION_STOP
            }
            startService(stopIntent)
            VpnStateBridge.updateState("DISCONNECTED", "Idle")
        } else {
            requestNotificationPermissionIfNeeded()
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnRequest.launch(intent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        VpnStateBridge.updateState("CONNECTING", "Starting engine...")
        val startIntent = Intent(this, SmartVpnService::class.java).apply {
            action = SmartVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, startIntent)
    }
}
