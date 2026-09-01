package de.lino.cloud.platform.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A light/dark color pair styled after iCloud/Apple's own system apps - a clean, airy light
 * theme (off-white background, pure-white cards, a systemBlue-ish accent) and a true dark theme
 * (near-black background, elevated dark-gray surfaces), rather than Material's own default
 * purple-leaning palette. [ThemeMode] (persisted via [AppSettingsStore]) selects between them.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EBFF),
    onPrimaryContainer = Color(0xFF00305F),
    secondary = Color(0xFF5AC8FA),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF0F0F3),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD2D2D7),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFFF3B30),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF409CFF),
    onPrimary = Color(0xFF00284D),
    primaryContainer = Color(0xFF00447A),
    onPrimaryContainer = Color(0xFFD9EBFF),
    secondary = Color(0xFF64D2FF),
    background = Color(0xFF1C1C1E),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF2C2C2E),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0xFF3A3A3C),
    error = Color(0xFFFF453A),
)

/** Which persisted color scheme is active - see [AppSettingsStore] for where this is read from/written to. */
enum class ThemeMode { LIGHT, DARK }

/** Applies [LightColors]/[DarkColors] depending on [themeMode], wrapping [content] in a plain [MaterialTheme]. */
@Composable
fun CloudDriverTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (themeMode == ThemeMode.DARK) DarkColors else LightColors,
        content = content,
    )
}
