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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
                var showLaunchWelcomeSplash by rememberSaveable { mutableStateOf(true) }
                if (showLaunchWelcomeSplash) {
                    SmartVpnWelcomeSplashScreen(
                        onContinue = { showLaunchWelcomeSplash = false }
                    )
                } else {
                    MainDashboardContent()
                }
            }
        }
    }

    @Composable
    private fun SmartVpnBrandCrest(sizeDp: Int = 46, animated: Boolean = false) {
        val infiniteTransition = rememberInfiniteTransition(label = "CrestSpin")
        val spinAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing)
            ),
            label = "Spin"
        )
        val effectiveAngle = if (animated) spinAngle else 45f

        Box(
            modifier = Modifier.size(sizeDp.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w / 2f

                rotate(effectiveAngle) {
                    drawArc(
                        color = Color(0xFF00E5FF),
                        startAngle = 0f,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = Stroke(width = w * 0.045f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color(0xFFFFB300),
                        startAngle = 180f,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = Stroke(width = w * 0.045f, cap = StrokeCap.Round)
                    )
                }

                val shieldPath = Path().apply {
                    moveTo(cx, h * 0.10f)
                    lineTo(w * 0.86f, h * 0.25f)
                    lineTo(w * 0.86f, h * 0.56f)
                    cubicTo(w * 0.86f, h * 0.76f, w * 0.70f, h * 0.88f, cx, h * 0.93f)
                    cubicTo(w * 0.30f, h * 0.88f, w * 0.14f, h * 0.76f, w * 0.14f, h * 0.56f)
                    lineTo(w * 0.14f, h * 0.25f)
                    close()
                }

                drawPath(
                    path = shieldPath,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF061329), Color(0xFF0E254D), Color(0xFF040B18))
                    )
                )
                drawPath(
                    path = shieldPath,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF00E5FF), Color(0xFF0077FF), Color(0xFFFFB300))
                    ),
                    style = Stroke(width = w * 0.04f)
                )

                drawArc(
                    color = Color(0xFF00E5FF),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.37f, h * 0.27f),
                    size = Size(w * 0.26f, h * 0.24f),
                    style = Stroke(width = w * 0.055f, cap = StrokeCap.Round)
                )

                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFE082), Color(0xFFFFB300), Color(0xFFFF8F00))
                    ),
                    topLeft = Offset(w * 0.31f, h * 0.42f),
                    size = Size(w * 0.38f, h * 0.30f),
                    cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
                )

                drawCircle(
                    color = Color(0xFF061329),
                    radius = w * 0.038f,
                    center = Offset(cx, h * 0.53f)
                )
                drawRoundRect(
                    color = Color(0xFF061329),
                    topLeft = Offset(cx - w * 0.02f, h * 0.54f),
                    size = Size(w * 0.04f, h * 0.10f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
        }
    }

    @Composable
    private fun SmartVpnWelcomeSplashScreen(onContinue: () -> Unit) {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val hwid = remember { VpnStateBridge.getDeviceHardwareId(context) }
        var copiedHwid by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF030712), Color(0xFF0A1833), Color(0xFF040914))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFFFFB300))),
                        shape = RoundedCornerShape(30.dp)
                    ),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF091326).copy(alpha = 0.96f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SmartVpnBrandCrest(sizeDp = 96, animated = true)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Smart™vPN",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF00E5FF).copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f))
                        ) {
                            Text(
                                text = "QUANTUM STEALTH CORE • v3.5.1 PRO",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // System Readiness Diagnostic HUD
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF060E1E),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "SYSTEM DIAGNOSTIC HUD",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFFB300)
                            )
                            Text(
                                text = "✓ [OK] 24-Protocol Stealth Engine Armed\n" +
                                    "✓ [OK] Live SHA-256 Server Fingerprint Verifier\n" +
                                    "✓ [OK] Open-File Lock & Share Vault Ready",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF00E676),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SplashFeatureTile(
                            icon = "🛡️",
                            title = "24 Protocols",
                            desc = "SSH, TLS, Payload, DNSTT, VLESS Reality, HY2 & WG",
                            modifier = Modifier.weight(1f)
                        )
                        SplashFeatureTile(
                            icon = "🔒",
                            title = "Smart Lock Vault",
                            desc = "Lock & share open configs; locked imports stay protected",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SplashFeatureTile(
                            icon = "🧬",
                            title = "SHA-256 Verify",
                            desc = "Real-time server host key & TLS fingerprint logging",
                            modifier = Modifier.weight(1f)
                        )
                        SplashFeatureTile(
                            icon = "⚖️",
                            title = "Multi-Tick Core",
                            desc = "Tick multiple open/manual nodes for combined speed",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF060E1E),
                        border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboardManager.setText(AnnotatedString(hwid))
                                copiedHwid = true
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "🆔 DEVICE HARDWARE ID (TAP TO COPY)",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF00E676)
                                )
                                Text(
                                    text = hwid,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = if (copiedHwid) "✓ COPIED" else "📋 COPY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF00E676)
                            )
                        }
                    }

                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color(0xFF001B26)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text(
                            text = "INITIALIZE SMART™vPN v3.5.1 ⚡",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SplashFeatureTile(
        icon: String,
        title: String,
        desc: String,
        modifier: Modifier = Modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F1D38),
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f)),
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White)
                }
                Text(desc, fontSize = 10.sp, color = Color(0xFF9FB3D6), lineHeight = 14.sp)
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

        val selectedProfiles = remember(profiles) { profiles.filter { it.isSelected } }
        val activeProfile = selectedProfiles.firstOrNull() ?: profiles.firstOrNull()
        val hwid = remember { VpnStateBridge.getDeviceHardwareId(context) }

        LaunchedEffect(triggerQr) {
            if (triggerQr) {
                VpnStateBridge.triggerQrScannerDialog.value = false
                showQrScannerModal = true
            }
        }

        val themeOptions = listOf(
            "Auto (Day / Night)",
            "1. Midnight Obsidian (Dark)",
            "2. Daylight Sapphire (Light)",
            "3. Synthwave Neon (Violet)",
            "4. Emerald Matrix (Cyber Green)",
            "5. Royal Gold & Amethyst",
            "6. Crimson Phantom (Red Ops)",
            "7. Arctic Glacier (Ice Light)",
            "8. Ocean Titanium (Deep Navy)",
            "9. Sunset Amber (Warm Dark)",
            "10. Monochrome Slate (Minimal)"
        )

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(320.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF050E22), Color(0xFF102854), Color(0xFF083D56))
                                    )
                                )
                                .padding(18.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SmartVpnBrandCrest(sizeDp = 56, animated = true)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Smart™vPN",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 21.sp,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "SMART v3.5.1 PRO EDITION",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFFB300),
                                            letterSpacing = 1.1.sp
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.Black.copy(alpha = 0.42f),
                                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.65f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(hwid))
                                            viewModel.showToastPill("Hardware 🆔 copied: $hwid")
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "🆔 HARDWARE ID (TAP TO COPY)",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF00E5FF)
                                            )
                                            Text(
                                                text = hwid,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF00E5FF)
                                        ) {
                                            Text(
                                                text = "COPY",
                                                color = Color(0xFF001F29),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "CORE ENGINE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            NavigationDrawerItem(
                                label = { Text("⚙️  Engine Settings", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "SETTINGS"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("📡  Response Checker", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "RESPONSE_CHECKER"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("📶  ShareNet Hotspot & Proxy", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "SHARENET"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("🛠️  Network Tools", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "NETWORK_TOOLS"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("⚡  Payload Generator", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    VpnStateBridge.activeDrawerScreen.value = "PAYLOAD_GEN"
                                    scope.launch { drawerState.close(); pagerState.animateScrollToPage(3) }
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("🎨  Themes (10 + Auto Day/Night)", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showThemeMenu = true
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("📖  User Manual & Guide", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showUserManual = true
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("ℹ️  About Smart™vPN v3.5.1", fontWeight = FontWeight.SemiBold) },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showAboutModal = true
                                }
                            )

                            Divider(modifier = Modifier.padding(vertical = 6.dp))
                            Text(
                                text = "COMMUNITY & SUPPORT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            NavigationDrawerItem(
                                label = { Text("📢  Telegram Channel") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    openExternalUrl("https://t.me/Smartking4luV")
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("💬  Telegram Group") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    openExternalUrl("https://t.me/lancecreation")
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("🟢  WhatsApp Community") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    openExternalUrl("https://chat.whatsapp.com/G9Jb6bgmvFqDAei8oVFU5k?s=cl&p=a&mlu=4&ilr=4")
                                }
                            )
                            NavigationDrawerItem(
                                label = { Text("🔗  Share Smart™vPN") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    shareSmartVpnApp()
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Text("👑  SMART v3.5.1 PRO UNLOCKED", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
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
                                SmartVpnBrandCrest(sizeDp = 36, animated = isConnected)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Smart™vPN",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFFB300)
                                ) {
                                    Text(
                                        text = "v3.5.1",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (isConnected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF00E676).copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, Color(0xFF00E676))
                                    ) {
                                        Text(
                                            text = "🔒 VPN",
                                            color = Color(0xFF00E676),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
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
                                Text("📖", fontSize = 18.sp)
                            }

                            Box {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (showPlusMenu) Color(0xFFD50000) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(38.dp)
                                        .clickable { showPlusMenu = !showPlusMenu }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (showPlusMenu) Icons.Default.Close else Icons.Default.Add,
                                            contentDescription = "Add Menu",
                                            tint = if (showPlusMenu) Color.White else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showPlusMenu,
                                    onDismissRequest = { showPlusMenu = false },
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface)
                                        .width(250.dp)
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
                                            try { filePickerLauncher.launch("*/*") } catch (_: Throwable) {}
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
                                        text = { Text("Lock & Export Active Config 🔒", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            if (activeProfile == null) {
                                                viewModel.showToastPill("No active configuration to export")
                                            } else if (activeProfile.isImported && activeProfile.isLocked) {
                                                viewModel.showToastPill("Locked imported configurations cannot be shared or exported")
                                            } else {
                                                scope.launch {
                                                    pagerState.animateScrollToPage(1)
                                                    VpnStateBridge.triggerExportActiveDialog.value = true
                                                }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export Load Balance Bundle ⚖️", fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showPlusMenu = false
                                            val openList = profiles.filter { !(it.isImported && it.isLocked) }
                                            if (openList.size < 2) {
                                                viewModel.showToastPill("You need at least 2 open/manual configs to export a bundle.")
                                            } else {
                                                val bundleUri = ConfigCryptoHelper.exportLoadBalanceBundle(openList)
                                                clipboardManager.setText(AnnotatedString(bundleUri))
                                                viewModel.showToastPill("Load Balance Bundle copied to clipboard")
                                            }
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                            selectedCount = selectedProfiles.size.coerceAtLeast(1),
                            onToggleConnection = {
                                if (activeProfile == null) scope.launch { pagerState.animateScrollToPage(1) }
                                else toggleVpn(connectionState != "DISCONNECTED")
                            },
                            onPingClick = { viewModel.executeConnectedPing(activeProfile) }
                        )
                        1 -> ConfigsScreen(
                            profiles = profiles,
                            onSelectProfile = { viewModel.toggleProfileSelection(it) },
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

        if (showCloudImportModal) {
            var cloudUrl by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCloudImportModal = false },
                title = { Text("Import from Cloud", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Paste the cloud URL (e.g., https://paste.rs/xyz)", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = cloudUrl, onValueChange = { cloudUrl = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (cloudUrl.isNotBlank()) {
                            viewModel.importFromCloudUrl(cloudUrl)
                            showCloudImportModal = false
                        }
                    }) { Text("Import", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showCloudImportModal = false }) { Text("Cancel") }
                }
            )
        }

        if (showQrScannerModal) {
            var manualQrPayload by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showQrScannerModal = false },
                title = { Text("Scan / Decode QR Config", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Paste decoded QR URI or select a config file:", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = manualQrPayload, onValueChange = { manualQrPayload = it }, label = { Text("svpn:// / vless:// / vmess:// / trojan://") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = {
                                showQrScannerModal = false
                                try { filePickerLauncher.launch("*/*") } catch (_: Throwable) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Select Config File from Device") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (manualQrPayload.isNotBlank()) viewModel.importFromClipboard(manualQrPayload)
                        showQrScannerModal = false
                    }) { Text("Import", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showQrScannerModal = false }) { Text("Close") }
                }
            )
        }

        if (showThemeMenu) {
            AlertDialog(
                onDismissRequest = { showThemeMenu = false },
                title = { Text("Smart™vPN v3.5.1 Themes (10 + Auto Day/Night)", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        themeOptions.forEach { th ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        VpnStateBridge.themeMode.value = th
                                        showThemeMenu = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentThemeMode == th,
                                    onClick = {
                                        VpnStateBridge.themeMode.value = th
                                        showThemeMenu = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(th, fontSize = 13.sp, fontWeight = if (currentThemeMode == th) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeMenu = false }) { Text("Close") }
                }
            )
        }

        if (showAboutModal) {
            AlertDialog(
                onDismissRequest = { showAboutModal = false },
                title = { Text("Smart™vPN v3.5.1 PRO", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Version: 3.5.1 (Build 351)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Hardware 🆔: $hwid", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("Enterprise Multi-Protocol Stealth VPN supporting 24 protocols, Live Server SHA-256 Fingerprint Verification, Open-Config Lock & Share, and Auto Day/Night Adaptive Themes.", fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(hwid))
                        viewModel.showToastPill("Copied Hardware 🆔: $hwid")
                        showAboutModal = false
                    }) { Text("Copy HWID & Close") }
                }
            )
        }

        if (showUserManual) {
            SmartV3UserManualDialog(onDismiss = { showUserManual = false })
        }
    }

    @Composable
    private fun SmartV3UserManualDialog(onDismiss: () -> Unit) {
        var selectedTab by remember { mutableStateOf("All") }
        val tabs = listOf("All", "⚠️ Tips", "⚡ Speed & Mux", "🌐 Protocols", "⚖️ Multi-Tick", "🔒 Lock & Share")

        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📖 Smart™vPN v3.5.1 User Manual", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tabs.forEach { tab ->
                                val isSel = selectedTab == tab
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { selectedTab = tab }
                                ) {
                                    Text(
                                        text = tab,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (selectedTab in listOf("All", "⚠️ Tips")) {
                                ManualCard("💡 Important Tips", "• Set Smart™vPN battery usage to Unrestricted.\n• Check the Logs tab during connection to view the server's verified SHA-256 Host Key & TLS Fingerprint.")
                            }
                            if (selectedTab in listOf("All", "⚡ Speed & Mux")) {
                                ManualCard("🚀 Turbo Speed & Multiplexing", "• Turbo Mode doubles internal socket buffers and opens parallel SSH/Xray streams so all apps open without delay.")
                            }
                            if (selectedTab in listOf("All", "🌐 Protocols")) {
                                ManualCard("🌐 24 Supported Protocols", "• 17 SSH/TLS/Payload/Proxy/DNSTT modes, VMess, VLESS Reality, Trojan, ShadowSocks, WireGuard, OpenVPN, and Hysteria2.")
                            }
                            if (selectedTab in listOf("All", "⚖️ Multi-Tick")) {
                                ManualCard("⚖️ Open vs. Locked Multi-Tick", "• Open imported configs and manual configs can be cloned, edited, locked & shared, and multi-ticked together.\n• Locked imported configs (.svpn with lock enabled) are single-tick only and cannot be shared or edited.")
                            }
                            if (selectedTab in listOf("All", "🔒 Lock & Share")) {
                                ManualCard("🔒 Open File Lock & Share", "• Import any open URI via Clipboard, tap 🔗 on the card, and select 'Lock & Export Config 🔒' to lock it and share with your users.")
                            }
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✓ Got It", fontWeight = FontWeight.Bold)
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
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
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
                putExtra(Intent.EXTRA_SUBJECT, "Smart™vPN v3.5.1 PRO")
                putExtra(Intent.EXTRA_TEXT, "Download Smart™vPN v3.5.1 PRO — Multi-Protocol Stealth VPN Tunnel (https://t.me/Smartking4luV)")
            }
            startActivity(Intent.createChooser(sendIntent, "Share Smart™vPN"))
        } catch (_: Throwable) {}
    }
}
// === END OF MainActivity.kt ===
