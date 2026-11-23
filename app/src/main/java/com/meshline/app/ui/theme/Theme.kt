package com.meshline.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App theme mode preference.
 * Supports three persistent appearance options: System default, Dark, and Light.
 */
enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

/**
 * Persistent theme storage using SharedPreferences.
 */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("meshline_theme_prefs", Context.MODE_PRIVATE)

    fun load(): AppThemeMode {
        val name = prefs.getString("theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
        return runCatching { AppThemeMode.valueOf(name) }.getOrDefault(AppThemeMode.SYSTEM)
    }

    fun save(mode: AppThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
    }
}

/**
 * Meshline semantic color design tokens extracted from Google Stitch MCP.
 * Engineered for consumer messaging with premium indigo/periwinkle brand accents
 * and dedicated semantic status colors (no cyberpunk/hacker neon green).
 */
@Immutable
data class MeshlineColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceContainerLow: Color,
    val border: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val statusConnected: Color,
    val statusWarning: Color,
    val danger: Color,
    val bubbleOutgoing: Color,
    val bubbleOutgoingText: Color,
    val bubbleIncoming: Color,
    val bubbleIncomingText: Color,
    val isDark: Boolean,
)

/**
 * Refined Dark Theme Palette
 * Target: Warm charcoal surfaces, amber gold brand accent,
 * and restrained emerald green strictly for online presence.
 * (Coral-DeepLavender Rx dark variant)
 */
val DarkMeshlineColors = MeshlineColors(
    background = Color(0xFF1C1917), // Warm charcoal
    surface = Color(0xFF292524), // Charcoal card
    surfaceElevated = Color(0xFF292524),
    surfaceContainerLow = Color(0xFF231F1D),
    border = Color(0xFF44403C), // Charcoal border
    primary = Color(0xFFFF5733), // Warm coral accent
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFF3D2A24), // Dark coral tint
    secondary = Color(0xFFA8A29E), // Muted stone
    textPrimary = Color(0xFFFAFAF9), // Warm white
    textSecondary = Color(0xFFA8A29E),
    textMuted = Color(0xFF78716C),
    statusConnected = Color(0xFF22C55E), // Success green
    statusWarning = Color(0xFFF59E0B), // Amber
    danger = Color(0xFFEF4444), // Danger red
    bubbleOutgoing = Color(0xFFFF5733), // Coral
    bubbleOutgoingText = Color(0xFFFFFFFF), // White
    bubbleIncoming = Color(0xFF292524), // Charcoal card (dark mode)
    bubbleIncomingText = Color(0xFFFAFAF9), // Warm white
    isDark = true,
)

/**
 * Refined Light Theme Palette
 * Target: Warm cream surfaces, warm coral brand accent,
 * deep lavender incoming bubbles, and natural emerald green for online presence.
 * (Coral-DeepLavender Rx design)
 */
val LightMeshlineColors = MeshlineColors(
    background = Color(0xFFFAF7F2), // Warm cream
    surface = Color(0xFFF5F0E8), // Cream card
    surfaceElevated = Color(0xFFF5F0E8),
    surfaceContainerLow = Color(0xFFF0E8DC), // Slightly darker cream
    border = Color(0xFFE8E0D5), // Warm border
    primary = Color(0xFFFF5733), // Warm coral accent
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFFFE0D6), // Light coral tint
    secondary = Color(0xFF8B7E74), // Muted brown
    textPrimary = Color(0xFF2D2420), // Deep ink
    textSecondary = Color(0xFF8B7E74), // Muted brown
    textMuted = Color(0xFFA89888), // Lighter muted
    statusConnected = Color(0xFF28A745), // Success green
    statusWarning = Color(0xFFF59E0B), // Amber
    danger = Color(0xFFDC3545), // Danger red
    bubbleOutgoing = Color(0xFFFF5733), // Coral
    bubbleOutgoingText = Color(0xFFFFFFFF), // White
    bubbleIncoming = Color(0xFFC4B5E0), // Deep lavender
    bubbleIncomingText = Color(0xFF1E1B2E), // Deep purple-ink
    isDark = false,
)

val LocalMeshlineColors = staticCompositionLocalOf { DarkMeshlineColors }

object MeshlineDesign {
    val colors: MeshlineColors
        @Composable
        get() = LocalMeshlineColors.current
}

@Composable
fun MeshlineTheme(
    mode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val meshlineColors = if (isDark) DarkMeshlineColors else LightMeshlineColors

    val materialColors = if (isDark) {
        darkColorScheme(
            primary = meshlineColors.primary,
            onPrimary = meshlineColors.onPrimary,
            primaryContainer = meshlineColors.primarySoft,
            onPrimaryContainer = meshlineColors.primary,
            background = meshlineColors.background,
            surface = meshlineColors.surface,
            onSurface = meshlineColors.textPrimary,
            outline = meshlineColors.border,
            error = meshlineColors.danger,
        )
    } else {
        lightColorScheme(
            primary = meshlineColors.primary,
            onPrimary = meshlineColors.onPrimary,
            primaryContainer = meshlineColors.primarySoft,
            onPrimaryContainer = meshlineColors.primary,
            background = meshlineColors.background,
            surface = meshlineColors.surface,
            onSurface = meshlineColors.textPrimary,
            outline = meshlineColors.border,
            error = meshlineColors.danger,
        )
    }

    CompositionLocalProvider(LocalMeshlineColors provides meshlineColors) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content,
        )
    }
}
