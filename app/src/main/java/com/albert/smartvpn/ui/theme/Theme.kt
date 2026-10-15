package com.albert.smartvpn.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.util.Calendar

// Theme 1: Cyber Obsidian (Dark)
private val CyberObsidianScheme = darkColorScheme(
    primary = Color(0xFF00F0FF),
    onPrimary = Color(0xFF001F26),
    primaryContainer = Color(0xFF0A2E46),
    onPrimaryContainer = Color(0xFFA6F8FF),
    secondary = Color(0xFF00E676),
    onSecondary = Color(0xFF00210E),
    tertiary = Color(0xFF7C4DFF),
    background = Color(0xFF040814),
    onBackground = Color(0xFFF0F6FC),
    surface = Color(0xFF09122C),
    onSurface = Color(0xFFF0F6FC),
    surfaceVariant = Color(0xFF111F48),
    onSurfaceVariant = Color(0xFF9FB3C8),
    error = Color(0xFFFF5252)
)

// Theme 2: Solar Crystal (Daylight)
private val SolarCrystalLightScheme = lightColorScheme(
    primary = Color(0xFF0052CC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B4E),
    secondary = Color(0xFF00875A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5E35B1),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF0A192F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A192F),
    surfaceVariant = Color(0xFFE3ECF8),
    onSurfaceVariant = Color(0xFF334E68),
    error = Color(0xFFD32F2F)
)

// Theme 3: Royal Amethyst & Gold
private val RoyalAmethystGoldScheme = darkColorScheme(
    primary = Color(0xFFFFC107),
    onPrimary = Color(0xFF261900),
    primaryContainer = Color(0xFF3D2652),
    onPrimaryContainer = Color(0xFFFFECB3),
    secondary = Color(0xFF00E676),
    onSecondary = Color(0xFF00210E),
    tertiary = Color(0xFFFF2E93),
    background = Color(0xFF0D061A),
    onBackground = Color(0xFFFDF8FF),
    surface = Color(0xFF170B2C),
    onSurface = Color(0xFFFDF8FF),
    surfaceVariant = Color(0xFF251442),
    onSurfaceVariant = Color(0xFFD1C4E9),
    error = Color(0xFFFF5252)
)

// Theme 4: Emerald Matrix (Cyber Green)
private val EmeraldMatrixScheme = darkColorScheme(
    primary = Color(0xFF00FF66),
    onPrimary = Color(0xFF00240C),
    primaryContainer = Color(0xFF063B1E),
    onPrimaryContainer = Color(0xFF99FFBE),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF002026),
    tertiary = Color(0xFFB2FF59),
    background = Color(0xFF030B06),
    onBackground = Color(0xFFE6F9ED),
    surface = Color(0xFF07170D),
    onSurface = Color(0xFFE6F9ED),
    surfaceVariant = Color(0xFF0E2918),
    onSurfaceVariant = Color(0xFF8SC9A3.let { Color(0xFF8CC9A3) }),
    error = Color(0xFFFF5252)
)

// Theme 5: Crimson Stealth (Red Ops)
private val CrimsonStealthScheme = darkColorScheme(
    primary = Color(0xFFFF1744),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A0A18),
    onPrimaryContainer = Color(0xFFFFB2C1),
    secondary = Color(0xFFFF9100),
    onSecondary = Color(0xFF261400),
    tertiary = Color(0xFFFF4081),
    background = Color(0xFF0C0507),
    onBackground = Color(0xFFFCEEEF),
    surface = Color(0xFF18090D),
    onSurface = Color(0xFFFCEEEF),
    surfaceVariant = Color(0xFF2B1018),
    onSurfaceVariant = Color(0xFFD8A8B2),
    error = Color(0xFFFF5252)
)

// Theme 6: Arctic Aurora (Nordic Frost)
private val ArcticAuroraScheme = darkColorScheme(
    primary = Color(0xFF64FFDA),
    onPrimary = Color(0xFF00241D),
    primaryContainer = Color(0xFF0E3A47),
    onPrimaryContainer = Color(0xFFB2FFF0),
    secondary = Color(0xFF40C4FF),
    onSecondary = Color(0xFF001E2E),
    tertiary = Color(0xFF80D8FF),
    background = Color(0xFF07131A),
    onBackground = Color(0xFFECF8FD),
    surface = Color(0xFF0D202B),
    onSurface = Color(0xFFECF8FD),
    surfaceVariant = Color(0xFF163242),
    onSurfaceVariant = Color(0xFF9AC5D8),
    error = Color(0xFFFF5252)
)

fun resolveActiveThemeKey(selectedMode: String): String {
    if (selectedMode != "AUTO") return selectedMode
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (hour in 6..18) "SOLAR_LIGHT" else "CYBER_DARK"
}

@Composable
fun SmartVpnTheme(
    content: @Composable () -> Unit
) {
    val selectedThemeMode by VpnStateBridge.themeMode.collectAsState()
    val effectiveTheme = resolveActiveThemeKey(selectedThemeMode)
    val isLightTheme = (effectiveTheme == "SOLAR_LIGHT")

    val colorScheme = when (effectiveTheme) {
        "SOLAR_LIGHT" -> SolarCrystalLightScheme
        "ROYAL_GOLD" -> RoyalAmethystGoldScheme
        "EMERALD_MATRIX" -> EmeraldMatrixScheme
        "CRIMSON_STEALTH" -> CrimsonStealthScheme
        "ARCTIC_AURORA" -> ArcticAuroraScheme
        else -> CyberObsidianScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLightTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
