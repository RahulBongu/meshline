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
 */
enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

/**
 * Persistent theme storage.
 */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("meshline_theme_prefs", Context.MODE_PRIVATE)

    fun load(): AppThemeMode {
        val name = prefs.getString("theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        return runCatching { AppThemeMode.valueOf(name) }.getOrDefault(AppThemeMode.DARK)
    }

    fun save(mode: AppThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
    }
}

/**
 * Meshline semantic color design tokens extracted from Google Stitch.
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
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val danger: Color,
    val bubbleOutgoing: Color,
    val bubbleOutgoingText: Color,
    val bubbleIncoming: Color,
    val bubbleIncomingText: Color,
    val isDark: Boolean,
)

val DarkMeshlineColors = MeshlineColors(
    background = Color(0xFF111316),
    surface = Color(0xFF1A1D21),
    surfaceElevated = Color(0xFF22272E),
    surfaceContainerLow = Color(0xFF16191D),
    border = Color(0xFF252930),
    primary = Color(0xFF00E599), // Mint Emerald
    onPrimary = Color(0xFF0B0D0F), // Deep Obsidian
    secondary = Color(0xFF10B981), // Deep Mint
    textPrimary = Color(0xFFF3F4F6),
    textSecondary = Color(0xFF9CA3AF),
    textMuted = Color(0xFF6B7280),
    danger = Color(0xFFEF4444),
    bubbleOutgoing = Color(0xFF00E599),
    bubbleOutgoingText = Color(0xFF0B0D0F),
    bubbleIncoming = Color(0xFF1A1D21),
    bubbleIncomingText = Color(0xFFF3F4F6),
    isDark = true,
)

val LightMeshlineColors = MeshlineColors(
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F9),
    border = Color(0xFFE2E8F0),
    primary = Color(0xFF059669), // Readable Emerald in daylight
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF10B981),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF94A3B8),
    danger = Color(0xFFDC2626),
    bubbleOutgoing = Color(0xFFD1FAE5), // Mint tint for daylight
    bubbleOutgoingText = Color(0xFF065F46),
    bubbleIncoming = Color(0xFFFFFFFF),
    bubbleIncomingText = Color(0xFF0F172A),
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
    mode: AppThemeMode = AppThemeMode.DARK,
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
            background = meshlineColors.background,
            surface = meshlineColors.surface,
            onSurface = meshlineColors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = meshlineColors.primary,
            onPrimary = meshlineColors.onPrimary,
            background = meshlineColors.background,
            surface = meshlineColors.surface,
            onSurface = meshlineColors.textPrimary,
        )
    }

    CompositionLocalProvider(LocalMeshlineColors provides meshlineColors) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content,
        )
    }
}
