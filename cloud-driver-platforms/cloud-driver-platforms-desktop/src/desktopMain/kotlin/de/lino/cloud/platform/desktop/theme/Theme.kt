package de.lino.cloud.platform.desktop.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A light/dark color pair styled after iCloud/Apple's own system apps - a clean, airy light
 * theme (off-white background, pure-white cards, a systemBlue-ish accent) and a true dark theme
 * (near-black background, elevated dark-gray surfaces), rather than Material's own default
 * purple-leaning palette. [ThemeMode] (synced to the account via `CloudUserResponse.themeMode` -
 * see [de.lino.cloud.platform.desktop.viewmodel.AppViewModel.toggleTheme]) selects between them.
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

/**
 * Slightly bolder, tighter headline/title styles than Material3's own defaults - closer to San
 * Francisco's display weight, the way the real macOS iCloud app's section titles and the big
 * storage number read. Every size/line-height is left at the Material default; only weight and
 * (for the two largest sizes) letter-spacing change, so this stays a refinement of the existing
 * type scale rather than a replacement of it.
 */
private val CloudDriverTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Which color scheme is active - synced to the signed-in account (`CloudUser.themeMode`
 * server-side), not a local per-device setting, so a choice made on one device follows the
 * account to every other device it's signed into. See
 * [de.lino.cloud.platform.desktop.viewmodel.AppViewModel.themeMode]/[toggleTheme] for where this
 * is read from/written to.
 */
enum class ThemeMode { LIGHT, DARK }

/** Applies [LightColors]/[DarkColors] depending on [themeMode] plus [CloudDriverTypography], wrapping [content] in a plain [MaterialTheme]. */
@Composable
fun CloudDriverTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (themeMode == ThemeMode.DARK) DarkColors else LightColors,
        typography = CloudDriverTypography,
        content = content,
    )
}

/**
 * Shared rounded-square corner radius for [IconTile] - a "squircle" proportion close to a real
 * macOS app icon's own corner curvature, not a generic Material chip radius.
 */
val TileShape = RoundedCornerShape(9.dp)

/** Shared card corner radius across every dashboard/auth card in this app - one rounded language, not a mix of ad-hoc per-screen values. */
val CardShape = RoundedCornerShape(18.dp)

/** Fully-rounded capsule shape - [StorageBar]'s own pill ends, and the sidebar's navigation-row highlight. */
val PillShape = RoundedCornerShape(50)

/**
 * A fixed palette of Apple system-accent-style colors, one per app destination or file category -
 * see [IconTile]/[de.lino.cloud.platform.desktop.utils.colorFor]. Named after their closest
 * Apple-system-color counterpart so a future addition picks a color by *meaning* ("this is a
 * Files-blue action") rather than reaching for a raw hex value.
 */
object CloudColors {
    val Blue = Color(0xFF0A84FF)
    val Teal = Color(0xFF40C4E0)
    val Green = Color(0xFF30D158)
    val Indigo = Color(0xFF5E5CE6)
    val Purple = Color(0xFFBF5AF2)
    val Pink = Color(0xFFFF375F)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
    val Gray = Color(0xFF8E8E93)
}

/**
 * The rounded-square "app icon" tile every top-level destination and file-type badge in this app
 * is drawn as - modeled directly on the real macOS iCloud app, where every service (Photos,
 * Drive, Mail, ...) is its own distinctly colored rounded-square glyph rather than one repeated
 * monochrome outline icon. Used by [de.lino.cloud.platform.desktop.panel.Sidebar]'s primary nav
 * rows and [de.lino.cloud.platform.desktop.panel.DashboardScreen]'s stat/overview cards; [size]/
 * [iconSize] default to a sidebar-row scale, pass larger values for a dashboard card's own icon.
 */
@Composable
fun IconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 30.dp, iconSize: Dp = 17.dp) {
    Box(modifier = modifier.size(size).clip(TileShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/**
 * A capsule-ended, multi-segment horizontal bar - the iCloud app's own signature storage-usage
 * visualization: colored segments proportioned by category, laid end to end inside one pill,
 * rather than a single Material [androidx.compose.material3.LinearProgressIndicator] track.
 * Each entry in [segments] pairs a fraction of the total width (0f-1f) with the color that
 * fraction renders as, drawn in order; fractions should sum to at most `1f` - whatever's left
 * over renders as [trackColor], the "free space" portion. An empty or all-zero [segments] list
 * simply renders as a plain [trackColor] bar.
 */
@Composable
fun StorageBar(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val clamped = segments.map { (fraction, color) -> fraction.coerceIn(0f, 1f) to color }
    val used = clamped.sumOf { it.first.toDouble() }.toFloat().coerceIn(0f, 1f)
    val remainder = (1f - used).coerceAtLeast(0.0001f)
    Row(modifier.height(height).clip(PillShape).background(trackColor)) {
        clamped.forEach { (fraction, color) ->
            if (fraction > 0f) {
                Box(Modifier.weight(fraction).fillMaxHeight().background(color))
            }
        }
        Box(Modifier.weight(remainder).fillMaxHeight())
    }
}
