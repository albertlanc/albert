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

// Theme 1: Cyber Obsidian (Night / Stealth Dark Mode)
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

// Theme 2: Solar Crystal (Morning / Daylight Mode)
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

// Theme 3: Royal Amethyst & Cyber Gold (Executive Luxury Mode)
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

fun resolveActiveThemeKey(selectedMode: String): String {
    if (selectedMode != "AUTO") return selectedMode
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    // Morning / Daytime (6:00 AM to 6:59 PM) -> Solar Crystal Light
    // Evening / Nighttime (7:00 PM to 5:59 AM) -> Cyber Obsidian Dark
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
