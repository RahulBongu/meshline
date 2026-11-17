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
 * Target: Deep cool night charcoal, slate containers, soft periwinkle brand accent,
 * and restrained emerald green strictly for online presence.
 */
val DarkMeshlineColors = MeshlineColors(
    background = Color(0xFF11141A),
    surface = Color(0xFF1A1F28),
    surfaceElevated = Color(0xFF222834),
    surfaceContainerLow = Color(0xFF181C24),
    border = Color(0xFF303746),
    primary = Color(0xFFA4B2FF), // Refined Soft Indigo Periwinkle
    onPrimary = Color(0xFF11141A),
    primarySoft = Color(0xFF282F49), // Subdued indigo tint container
    secondary = Color(0xFFA0A8B7),
    textPrimary = Color(0xFFF1F3F8),
    textSecondary = Color(0xFFA0A8B7),
    textMuted = Color(0xFF6E788B),
    statusConnected = Color(0xFF34D399), // Semantic emerald green for peer presence
    statusWarning = Color(0xFFFBBF24), // Semantic amber
    danger = Color(0xFFF87171), // Semantic red
    bubbleOutgoing = Color(0xFF282F49),
    bubbleOutgoingText = Color(0xFFF1F3F8),
    bubbleIncoming = Color(0xFF1A1F28),
    bubbleIncomingText = Color(0xFFF1F3F8),
    isDark = true,
)

/**
 * Refined Light Theme Palette
 * Target: Clean cool light gray, crisp white surfaces, royal indigo brand accent,
 * soft indigo tints, and natural emerald green strictly for online presence.
 */
val LightMeshlineColors = MeshlineColors(
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2F8),
    border = Color(0xFFE3E7EF),
    primary = Color(0xFF5368C8), // Refined Royal Indigo
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFE9EDFF), // Pale indigo wash container
    secondary = Color(0xFF687386),
    textPrimary = Color(0xFF1B2028),
    textSecondary = Color(0xFF687386),
    textMuted = Color(0xFF9AA4B2),
    statusConnected = Color(0xFF10B981), // Semantic emerald green for peer presence
    statusWarning = Color(0xFFF59E0B), // Semantic amber
    danger = Color(0xFFEF4444), // Semantic red
    bubbleOutgoing = Color(0xFF5368C8),
    bubbleOutgoingText = Color(0xFFFFFFFF),
    bubbleIncoming = Color(0xFFFFFFFF),
    bubbleIncomingText = Color(0xFF1B2028),
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
