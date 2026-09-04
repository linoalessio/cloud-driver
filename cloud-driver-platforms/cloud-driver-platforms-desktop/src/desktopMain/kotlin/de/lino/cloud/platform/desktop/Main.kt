package de.lino.cloud.platform.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.lino.cloud.platform.desktop.theme.CloudDriverTheme
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platforms.desktop.cloud_driver_platforms_desktop.generated.resources.Res
import de.lino.cloud.platforms.desktop.cloud_driver_platforms_desktop.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

/**
 * Default server this client talks to. `ApiClient` takes two base URLs (an "auth-panel" one and
 * a "main API" one) but, as of this writing, both always resolve to the same single Javalin
 * server (`CloudRestExtension`) - see `cloud-driver-platforms-rest`'s own `ApiClient` Javadoc -
 * so [AppViewModel] passes this one value for both. Hardcoded deliberately - no in-app "Server"
 * field exists (there used to be one on the login screen; removed so a first-time user isn't
 * asked to configure anything before signing in).
 */
private const val DEFAULT_SERVER_URL = "https://api.cloud-driver.de"

/**
 * Floor on how small the window can be resized/minimized to, in `dp`. Fixes a real bug: without
 * some floor, the window could be dragged smaller and smaller until it shrank to nothing and
 * effectively vanished, with no way to grab an edge to resize it back up.
 *
 * <p><b>Enforced entirely through [rememberWindowState]'s own [androidx.compose.ui.window.WindowState.size],
 * not by mutating the underlying AWT `window.minimumSize` directly</b> - an earlier revision did
 * the latter (`WindowScope.window.minimumSize = ...`, from a `LaunchedEffect`), which caused a
 * real, confirmed bug (2026-09-04): reaching into the raw AWT peer outside Compose's own
 * layout/resize plumbing desynced the Skia rendering surface from the native window's actual
 * bounds, rendering every screen's content into a small, mispositioned patch (observed pinned to
 * the bottom-right corner) of an otherwise-blank, oversized window - reported as "the login
 * panel's size is destroyed" and reproduced identically in both `./gradlew run` and the packaged
 * `.app` (ruling out a jlink/packaging-specific cause). Confirmed fixed by removing that AWT
 * mutation entirely; this `WindowState`-based clamp is the replacement, staying inside Compose's
 * own state system so the resize goes through the normal recomposition path instead of bypassing
 * it. The trade-off: unlike a hard OS-level minimum, a drag can overshoot below this floor for one
 * frame before snapping back, rather than being physically blocked - acceptable given the
 * alternative broke rendering outright.
 */
private val MINIMUM_WINDOW_SIZE = DpSize(1200.dp, 800.dp)

fun main() = application {
    val scope = rememberCoroutineScope()
    // Starts at AppViewModel.themeMode's own default (ThemeMode.LIGHT) - the account's real,
    // synced preference (see CloudUser.themeMode server-side) is only fetched once a session is
    // established (tryRestoreSession below, or an explicit login), since there is no account to
    // ask before that point.
    val viewModel = remember { AppViewModel(scope, DEFAULT_SERVER_URL) }
    val windowState = rememberWindowState(size = MINIMUM_WINDOW_SIZE)

    Window(
        onCloseRequest = {
            viewModel.client.close()
            exitApplication()
        },
        title = "cloud-driver",
        state = windowState,
        icon = painterResource(Res.drawable.app_icon),
    ) {
        // Clamps windowState.size back up to MINIMUM_WINDOW_SIZE whenever a drag takes it below
        // that floor - see MINIMUM_WINDOW_SIZE's own Javadoc for why this goes through WindowState
        // rather than the raw AWT window object.
        LaunchedEffect(windowState.size) {
            val current = windowState.size
            val clampedWidth = current.width.coerceAtLeast(MINIMUM_WINDOW_SIZE.width)
            val clampedHeight = current.height.coerceAtLeast(MINIMUM_WINDOW_SIZE.height)
            if (clampedWidth != current.width || clampedHeight != current.height) {
                windowState.size = DpSize(clampedWidth, clampedHeight)
            }
        }
        // Session persistence (item 4, SERVICES.md): before the first real screen is meaningfully
        // interacted with, try to restore a session persisted from a previous run (OS
        // keychain/fallback file - see CloudDriverClient/SessionManager) so a returning user goes
        // straight to Screen.Browser instead of always starting at the login screen. A no-op if
        // there was no persisted session or it's no longer valid - viewModel.screen simply stays
        // at its initial Screen.Login.
        LaunchedEffect(Unit) { viewModel.tryRestoreSession() }

        CloudDriverTheme(viewModel.themeMode) {
            App(viewModel)
        }
    }
}
