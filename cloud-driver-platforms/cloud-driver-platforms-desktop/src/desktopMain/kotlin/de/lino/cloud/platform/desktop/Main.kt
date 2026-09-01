package de.lino.cloud.platform.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
        CloudDriverTheme(viewModel.themeMode) {
            App(viewModel)
        }
    }
}
