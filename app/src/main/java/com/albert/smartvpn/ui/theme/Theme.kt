package com.albert.smartvpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge

private val SynthwaveColorScheme = darkColorScheme(
    primary = Color(0xFFE011CF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF391254),
    onPrimaryContainer = Color(0xFFFF80F5),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF00262B),
    tertiary = Color(0xFFFFB300),
    background = Color(0xFF160724),
    onBackground = Color(0xFFF5E9FF),
    surface = Color(0xFF1F0A33),
    onSurface = Color(0xFFF5E9FF),
    surfaceVariant = Color(0xFF2B1045),
    onSurfaceVariant = Color(0xFFC9B3DEC)
        .let { Color(0xFFC8B2E0) },
    error = Color(0xFFFF4D6D)
)

private val DefaultColorScheme = darkColorScheme(
    primary = Color(0xFF9C27B0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2D1242),
    onPrimaryContainer = Color(0xFFEFA8FF),
    secondary = Color(0xFF00E676),
    tertiary = Color(0xFF00E5FF),
    background = Color(0xFF12061E),
    onBackground = Color.White,
    surface = Color(0xFF1C0B2E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF281240),
    onSurfaceVariant = Color(0xFFBFA8D6)
)

private val CyberpunkColorScheme = darkColorScheme(
    primary = Color(0xFFFFEE00),
    onPrimary = Color(0xFF141200),
    primaryContainer = Color(0xFF332D00),
    onPrimaryContainer = Color(0xFFFFEE00),
    secondary = Color(0xFF00F0FF),
    tertiary = Color(0xFFFF0055),
    background = Color(0xFF090A10),
    onBackground = Color(0xFFF8F9FA),
    surface = Color(0xFF121420),
    onSurface = Color(0xFFF8F9FA),
    surfaceVariant = Color(0xFF1B1F30),
    onSurfaceVariant = Color(0xFFA0AAB8)
)

private val MinimalistColorScheme = lightColorScheme(
    primary = Color(0xFF212529),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9ECEF),
    onPrimaryContainer = Color(0xFF212529),
    secondary = Color(0xFF0D6EFD),
    tertiary = Color(0xFF198754),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF212529),
    surface = Color.White,
    onSurface = Color(0xFF212529),
    surfaceVariant = Color(0xFFE9ECEF),
    onSurfaceVariant = Color(0xFF495057)
)

private val CorporateBlueColorScheme = darkColorScheme(
    primary = Color(0xFF2979FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF102A56),
    onPrimaryContainer = Color(0xFF90CAF9),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFFFC107),
    background = Color(0xFF071124),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF0D1B36),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF15284C),
    onSurfaceVariant = Color(0xFF9EB3D6)
)

private val MatteCyanColorScheme = darkColorScheme(
    primary = Color(0xFF00BCD4),
    onPrimary = Color(0xFF002024),
    primaryContainer = Color(0xFF0E343D),
    onPrimaryContainer = Color(0xFF84FFFF),
    secondary = Color(0xFF1DE9B6),
    tertiary = Color(0xFFFFAB00),
    background = Color(0xFF08161A),
    onBackground = Color(0xFFE0F7FA),
    surface = Color(0xFF0E2228),
    onSurface = Color(0xFFE0F7FA),
    surfaceVariant = Color(0xFF16323B),
    onSurfaceVariant = Color(0xFF94B8C2)
)

private val MonochromeColorScheme = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF2C2C2C),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF9E9E9E),
    tertiary = Color(0xFFBDBDBD),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFF9E9E9E)
)

@Composable
fun SmartVpnTheme(
    content: @Composable () -> Unit
) {
    val themeMode by VpnStateBridge.themeMode.collectAsState()

    val colorScheme = when (themeMode) {
        "Default" -> DefaultColorScheme
        "Cyberpunk" -> CyberpunkColorScheme
        "Synthwave" -> SynthwaveColorScheme
        "Minimalist" -> MinimalistColorScheme
        "Corporate blue" -> CorporateBlueColorScheme
        "Matte cyan" -> MatteCyanColorScheme
        "Monochrome" -> MonochromeColorScheme
        else -> SynthwaveColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
// === END OF FILE 4 ===
