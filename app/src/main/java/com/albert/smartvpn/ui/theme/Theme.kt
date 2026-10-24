package com.albert.smartvpn.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge

private val MidnightObsidianScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF001F24),
    primaryContainer = Color(0xFF004F58),
    secondary = Color(0xFFFFB300),
    background = Color(0xFF060B16),
    surface = Color(0xFF0D1627),
    surfaceVariant = Color(0xFF162238),
    onBackground = Color(0xFFEAF4FF),
    onSurface = Color(0xFFEAF4FF),
    onSurfaceVariant = Color(0xFF9FB3D6)
)

private val DaylightSapphireScheme = lightColorScheme(
    primary = Color(0xFF005AC1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFF006874),
    background = Color(0xFFF8FAFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE1E8F5),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569)
)

private val SynthwaveNeonScheme = darkColorScheme(
    primary = Color(0xFFFF2A85),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A002C),
    secondary = Color(0xFF00F0FF),
    background = Color(0xFF120424),
    surface = Color(0xFF1D0B36),
    surfaceVariant = Color(0xFF2E1452),
    onBackground = Color(0xFFFFEBF5),
    onSurface = Color(0xFFFFEBF5),
    onSurfaceVariant = Color(0xFFD9A7C7)
)

private val EmeraldMatrixScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF00210E),
    primaryContainer = Color(0xFF005227),
    secondary = Color(0xFF69F0AE),
    background = Color(0xFF041008),
    surface = Color(0xFF0A1F11),
    surfaceVariant = Color(0xFF12331D),
    onBackground = Color(0xFFE0F7E9),
    onSurface = Color(0xFFE0F7E9),
    onSurfaceVariant = Color(0xFF8AC79E)
)

private val RoyalGoldAmethystScheme = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color(0xFF261900),
    primaryContainer = Color(0xFF574300),
    secondary = Color(0xFFB388FF),
    background = Color(0xFF11091C),
    surface = Color(0xFF1C102E),
    surfaceVariant = Color(0xFF2B1945),
    onBackground = Color(0xFFFDF6FF),
    onSurface = Color(0xFFFDF6FF),
    onSurfaceVariant = Color(0xFFCBB6E2)
)

private val CrimsonPhantomScheme = darkColorScheme(
    primary = Color(0xFFFF1744),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C0011),
    secondary = Color(0xFFFF8A80),
    background = Color(0xFF120507),
    surface = Color(0xFF1F0A0E),
    surfaceVariant = Color(0xFF331218),
    onBackground = Color(0xFFFFECEE),
    onSurface = Color(0xFFFFECEE),
    onSurfaceVariant = Color(0xFFD69EA4)
)

private val ArcticGlacierScheme = lightColorScheme(
    primary = Color(0xFF00838F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2EBF2),
    secondary = Color(0xFF0277BD),
    background = Color(0xFFF0F9FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFD9EEF0),
    onBackground = Color(0xFF092327),
    onSurface = Color(0xFF092327),
    onSurfaceVariant = Color(0xFF3F6267)
)

private val OceanTitaniumScheme = darkColorScheme(
    primary = Color(0xFF40C4FF),
    onPrimary = Color(0xFF001E2F),
    primaryContainer = Color(0xFF004B70),
    secondary = Color(0xFF80D8FF),
    background = Color(0xFF07131E),
    surface = Color(0xFF0F2133),
    surfaceVariant = Color(0xFF19334D),
    onBackground = Color(0xFFE6F4FF),
    onSurface = Color(0xFFE6F4FF),
    onSurfaceVariant = Color(0xFF9AB8D1)
)

private val SunsetAmberScheme = darkColorScheme(
    primary = Color(0xFFFF9100),
    onPrimary = Color(0xFF2B1400),
    primaryContainer = Color(0xFF663300),
    secondary = Color(0xFFFFD180),
    background = Color(0xFF160C05),
    surface = Color(0xFF241409),
    surfaceVariant = Color(0xFF382010),
    onBackground = Color(0xFFFFF3E6),
    onSurface = Color(0xFFFFF3E6),
    onSurfaceVariant = Color(0xFFD9B38C)
)

private val MonochromeSlateScheme = darkColorScheme(
    primary = Color(0xFFE2E8F0),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF334155),
    secondary = Color(0xFF94A3B8),
    background = Color(0xFF090D16),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1F2937),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8)
)

@Composable
fun SmartVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val selectedTheme by VpnStateBridge.themeMode.collectAsState()

    val isLightMode = when {
        selectedTheme.startsWith("2.") || selectedTheme.startsWith("7.") -> true
        selectedTheme.startsWith("Auto") -> !darkTheme
        else -> false
    }

    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isLightMode) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
        }
        selectedTheme.startsWith("1.") -> MidnightObsidianScheme
        selectedTheme.startsWith("2.") -> DaylightSapphireScheme
        selectedTheme.startsWith("3.") -> SynthwaveNeonScheme
        selectedTheme.startsWith("4.") -> EmeraldMatrixScheme
        selectedTheme.startsWith("5.") -> RoyalGoldAmethystScheme
        selectedTheme.startsWith("6.") -> CrimsonPhantomScheme
        selectedTheme.startsWith("7.") -> ArcticGlacierScheme
        selectedTheme.startsWith("8.") -> OceanTitaniumScheme
        selectedTheme.startsWith("9.") -> SunsetAmberScheme
        selectedTheme.startsWith("10.") -> MonochromeSlateScheme
        else -> if (darkTheme) MidnightObsidianScheme else DaylightSapphireScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLightMode
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
