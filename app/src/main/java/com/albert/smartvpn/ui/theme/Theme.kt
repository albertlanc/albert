package com.albert.smartvpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.albert.smartvpn.ui.viewmodel.VpnStateBridge

// The exact 12 theme colors shown in the 4x3 grid in the video (08:02 - 08:14)
val SmartThemePaletteColors = listOf(
    Color(0xFFE91E63), // 0: Crimson Rose / Pink
    Color(0xFF9C27B0), // 1: Royal Purple
    Color(0xFF673AB7), // 2: Deep Indigo Violet
    Color(0xFF3F51B5), // 3: Sapphire Blue
    Color(0xFF2196F3), // 4: Sky Blue
    Color(0xFF00BCD4), // 5: Cyan
    Color(0xFF009688), // 6: Teal
    Color(0xFF4CAF50), // 7: Green
    Color(0xFF8BC34A), // 8: Lime Green
    Color(0xFFCDDC39), // 9: Chartreuse
    Color(0xFFFFC107), // 10: Amber Gold
    Color(0xFFFF9800)  // 11: Warm Orange (Default in video!)
)

private fun blend(c1: Color, c2: Color, ratio: Float): Color {
    val r = c1.red * (1f - ratio) + c2.red * ratio
    val g = c1.green * (1f - ratio) + c2.green * ratio
    val b = c1.blue * (1f - ratio) + c2.blue * ratio
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 1f)
}

@Composable
fun SmartVpnTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeMode by VpnStateBridge.themeMode.collectAsState()
    val useDynamicColor by VpnStateBridge.dynamicColorEnabled.collectAsState()
    val colorIndex by VpnStateBridge.themeColorIndex.collectAsState()
    val contrastLevel by VpnStateBridge.themeContrast.collectAsState() // "N", "M", "H"

    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "Light" -> false
        "Dark" -> true
        else -> systemDark
    }

    val seedColor = SmartThemePaletteColors.getOrElse(colorIndex) { Color(0xFFFF9800) }

    val primaryAdjusted = when (contrastLevel) {
        "H" -> if (isDark) blend(seedColor, Color.White, 0.25f) else blend(seedColor, Color.Black, 0.38f)
        "M" -> if (isDark) blend(seedColor, Color.White, 0.12f) else blend(seedColor, Color.Black, 0.22f)
        else -> if (isDark) seedColor else blend(seedColor, Color.Black, 0.08f)
    }

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> {
            val bg = blend(Color(0xFF141210), seedColor, 0.06f)
            val surf = blend(Color(0xFF1E1B18), seedColor, 0.10f)
            val surfVar = blend(Color(0xFF2B2520), seedColor, 0.16f)
            darkColorScheme(
                primary = primaryAdjusted,
                onPrimary = if (primaryAdjusted.luminance() > 0.45f) Color(0xFF1F1400) else Color.White,
                primaryContainer = blend(seedColor, Color(0xFF2A2118), 0.55f),
                onPrimaryContainer = Color(0xFFFFEDE0),
                secondary = seedColor,
                onSecondary = Color.Black,
                tertiary = Color(0xFF2E7D32),
                background = bg,
                onBackground = Color(0xFFF5EFEA),
                surface = surf,
                onSurface = Color(0xFFF5EFEA),
                surfaceVariant = surfVar,
                onSurfaceVariant = Color(0xFFD5C4B8),
                outline = blend(Color(0xFF8D7B6D), seedColor, 0.25f)
            )
        }
        else -> {
            // Exact warm cream/tinted paper background & card aesthetic from the video
            val creamBg = blend(Color(0xFFFFFBF7), seedColor, if (contrastLevel == "H") 0.06f else 0.03f)
            val creamSurface = blend(Color(0xFFFAF2EA), seedColor, 0.06f)
            val creamSurfaceVar = blend(Color(0xFFF3E5D8), seedColor, 0.14f)
            val containerFill = when (contrastLevel) {
                "H" -> blend(seedColor, Color.Black, 0.28f)
                "M" -> blend(seedColor, Color.White, 0.55f)
                else -> blend(seedColor, Color.White, 0.65f)
            }
            val onContainerText = if (contrastLevel == "H") Color.White else Color(0xFF2B1704)

            lightColorScheme(
                primary = primaryAdjusted,
                onPrimary = if (primaryAdjusted.luminance() > 0.5f) Color(0xFF241500) else Color.White,
                primaryContainer = containerFill,
                onPrimaryContainer = onContainerText,
                secondary = blend(seedColor, Color(0xFF4E342E), 0.35f),
                onSecondary = Color.White,
                tertiary = Color(0xFF2E7D32),
                background = creamBg,
                onBackground = Color(0xFF241A12),
                surface = creamSurface,
                onSurface = Color(0xFF241A12),
                surfaceVariant = creamSurfaceVar,
                onSurfaceVariant = Color(0xFF5C4938),
                outline = blend(Color(0xFFB8A28E), seedColor, 0.28f)
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
// === END OF Theme.kt ===
