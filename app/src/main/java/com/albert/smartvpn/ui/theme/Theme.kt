package com.albert.smartvpn.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CyberNeonColorScheme = darkColorScheme(
    primary = Color(0xFF00F0FF),          // Electric Neon Cyan
    onPrimary = Color(0xFF001F26),
    primaryContainer = Color(0xFF0A2E46), // Deep Glowing Cyan Card
    onPrimaryContainer = Color(0xFFA6F8FF),
    secondary = Color(0xFF00E676),        // Cyber Emerald Connected
    onSecondary = Color(0xFF00210E),
    secondaryContainer = Color(0xFF1E2942),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFF7C4DFF),         // Electric Violet Accent
    background = Color(0xFF050A18),       // Deep Midnight Cyber Navy
    onBackground = Color(0xFFF0F6FC),
    surface = Color(0xFF0B132B),          // Rich Sapphire Surface
    onSurface = Color(0xFFF0F6FC),
    surfaceVariant = Color(0xFF131F42),   // Elevated Glass Card Surface
    onSurfaceVariant = Color(0xFF9FB3C8),
    error = Color(0xFFFF5252)
)

@Composable
fun SmartVpnTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = CyberNeonColorScheme.background.toArgb()
            window.navigationBarColor = CyberNeonColorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = CyberNeonColorScheme,
        content = content
    )
}
