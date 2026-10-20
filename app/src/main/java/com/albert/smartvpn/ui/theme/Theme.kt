package com.albert.smartvpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge
import java.util.Calendar

// 1. Midnight Obsidian (Night Default Dark)
private val MidnightObsidianScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00262B),
    primaryContainer = Color(0xFF0B253D),
    onPrimaryContainer = Color(0xFF84FFFF),
    secondary = Color(0xFF00E676),
    onSecondary = Color(0xFF002912),
    tertiary = Color(0xFFFFB300),
    background = Color(0xFF060B16),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF0D1526),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF15223B),
    onSurfaceVariant = Color(0xFF9FB3D6),
    error = Color(0xFFFF5252)
)

// 2. Daylight Sapphire (Day Default Light)
private val DaylightSapphireScheme = lightColorScheme(
    primary = Color(0xFF0055D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF002B73),
    secondary = Color(0xFF00875A),
    onSecondary = Color.White,
    tertiary = Color(0xFFD97706),
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF0D172A),
    surface = Color.White,
    onSurface = Color(0xFF0D172A),
    surfaceVariant = Color(0xFFE6ECF7),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFDC2626)
)

// 3. Synthwave Neon
private val SynthwaveNeonScheme = darkColorScheme(
    primary = Color(0xFFE011CF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF391254),
    onPrimaryContainer = Color(0xFFFF80F5),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFFFB300),
    background = Color(0xFF160724),
    onBackground = Color(0xFFF5E9FF),
    surface = Color(0xFF1F0A33),
    onSurface = Color(0xFFF5E9FF),
    surfaceVariant = Color(0xFF2B1045),
    onSurfaceVariant = Color(0xFFC8B2E0),
    error = Color(0xFFFF4D6D)
)

// 4. Emerald Matrix
private val EmeraldMatrixScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF002410),
    primaryContainer = Color(0xFF09311E),
    onPrimaryContainer = Color(0xFF69F0AE),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFFFD600),
    background = Color(0xFF040E08),
    onBackground = Color(0xFFE5FFF0),
    surface = Color(0xFF091A10),
    onSurface = Color(0xFFE5FFF0),
    surfaceVariant = Color(0xFF11291B),
    onSurfaceVariant = Color(0xFF93C4A6)
)

// 5. Royal Gold & Amethyst
private val RoyalGoldScheme = darkColorScheme(
    primary = Color(0xFFFFC107),
    onPrimary = Color(0xFF261B00),
    primaryContainer = Color(0xFF3A2905),
    onPrimaryContainer = Color(0xFFFFE082),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFE040FB),
    background = Color(0xFF0F081D),
    onBackground = Color(0xFFFFF8E7),
    surface = Color(0xFF180F2E),
    onSurface = Color(0xFFFFF8E7),
    surfaceVariant = Color(0xFF251742),
    onSurfaceVariant = Color(0xFFCBB9E8)
)

// 6. Crimson Phantom (Red Ops)
private val CrimsonPhantomScheme = darkColorScheme(
    primary = Color(0xFFFF1744),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF420A16),
    onPrimaryContainer = Color(0xFFFF8A80),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFFFC107),
    background = Color(0xFF0F0507),
    onBackground = Color(0xFFFFECEE),
    surface = Color(0xFF1A090D),
    onSurface = Color(0xFFFFECEE),
    surfaceVariant = Color(0xFF2B1016),
    onSurfaceVariant = Color(0xFFD6A3AC)
)

// 7. Arctic Glacier (Ice Light)
private val ArcticGlacierScheme = lightColorScheme(
    primary = Color(0xFF00838F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF2F7),
    onPrimaryContainer = Color(0xFF00363C),
    secondary = Color(0xFF0284C7),
    tertiary = Color(0xFF0D9488),
    background = Color(0xFFF0FAFC),
    onBackground = Color(0xFF082226),
    surface = Color.White,
    onSurface = Color(0xFF082226),
    surfaceVariant = Color(0xFFDDF1F5),
    onSurfaceVariant = Color(0xFF3E646B)
)

// 8. Ocean Titanium (Deep Navy)
private val OceanTitaniumScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF002438),
    primaryContainer = Color(0xFF0D314D),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFF34D399),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF07131F),
    onBackground = Color(0xFFF0F9FF),
    surface = Color(0xFF0E2033),
    onSurface = Color(0xFFF0F9FF),
    surfaceVariant = Color(0xFF17304A),
    onSurfaceVariant = Color(0xFF94B8D7)
)

// 9. Sunset Amber (Warm Dark)
private val SunsetAmberScheme = darkColorScheme(
    primary = Color(0xFFFF6D00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF421C04),
    onPrimaryContainer = Color(0xFFFFB780),
    secondary = Color(0xFFFFD600),
    tertiary = Color(0xFF00E5FF),
    background = Color(0xFF140B06),
    onBackground = Color(0xFFFFF3EB),
    surface = Color(0xFF21120A),
    onSurface = Color(0xFFFFF3EB),
    surfaceVariant = Color(0xFF331D11),
    onSurfaceVariant = Color(0xFFD9B6A3)
)

// 10. Monochrome Slate (Minimalist)
private val MonochromeSlateScheme = darkColorScheme(
    primary = Color(0xFFF1F5F9),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF334155),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF94A3B8),
    tertiary = Color(0xFFCBD5E1),
    background = Color(0xFF090D16),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF131B2E),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8)
)

@Composable
fun SmartVpnTheme(
    content: @Composable () -> Unit
) {
    val themeMode by VpnStateBridge.themeMode.collectAsState()
    val isDaytime = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hour in 6..17
    }

    val colorScheme = when (themeMode) {
        "Auto (Day / Night)" -> if (isDaytime) DaylightSapphireScheme else MidnightObsidianScheme
        "1. Midnight Obsidian (Dark)" -> MidnightObsidianScheme
        "2. Daylight Sapphire (Light)" -> DaylightSapphireScheme
        "3. Synthwave Neon (Violet)" -> SynthwaveNeonScheme
        "4. Emerald Matrix (Cyber Green)" -> EmeraldMatrixScheme
        "5. Royal Gold & Amethyst" -> RoyalGoldScheme
        "6. Crimson Phantom (Red Ops)" -> CrimsonPhantomScheme
        "7. Arctic Glacier (Ice Light)" -> ArcticGlacierScheme
        "8. Ocean Titanium (Deep Navy)" -> OceanTitaniumScheme
        "9. Sunset Amber (Warm Dark)" -> SunsetAmberScheme
        "10. Monochrome Slate (Minimal)" -> MonochromeSlateScheme
        else -> if (isDaytime) DaylightSapphireScheme else MidnightObsidianScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
