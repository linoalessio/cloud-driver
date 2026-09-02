package de.lino.cloud.platform.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import de.lino.cloud.platform.desktop.theme.CloudDriverTheme
import de.lino.cloud.platform.desktop.utils.AppSettingsStore
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
 * Floor on how small the window can be resized/minimized to - Compose Desktop has no
 * `Window`-level "minimum size" parameter of its own, so this is applied imperatively via
 * `WindowScope.window` (the underlying `ComposeWindow`/AWT `Window`) below. Fixes a real bug:
 * without it, the window could be dragged smaller and smaller until it shrank to nothing and
 * effectively vanished, with no way to grab an edge to resize it back up.
 */
private val MINIMUM_WINDOW_SIZE = Dimension(1200, 800)

fun main() = application {
    val scope = rememberCoroutineScope()
    // Loaded synchronously - see AppSettingsStore#loadThemeMode's own Javadoc for why that's fine here.
    val viewModel = remember { AppViewModel(scope, DEFAULT_SERVER_URL, AppSettingsStore.loadThemeMode()) }

    Window(
        onCloseRequest = {
            viewModel.client.close()
            exitApplication()
        },
        title = "cloud-driver",
        state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
        icon = painterResource(Res.drawable.app_icon),
    ) {
        LaunchedEffect(Unit) { window.minimumSize = MINIMUM_WINDOW_SIZE }
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
