package de.lino.cloud.platform.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.lino.cloud.platform.desktop.auth.LoginScreen
import de.lino.cloud.platform.desktop.auth.RegisterConfirmScreen
import de.lino.cloud.platform.desktop.auth.RegisterScreen
import de.lino.cloud.platform.desktop.auth.ResetPasswordConfirmScreen
import de.lino.cloud.platform.desktop.auth.ResetPasswordRequestScreen
import de.lino.cloud.platform.desktop.auth.TwoFactorLoginScreen
import de.lino.cloud.platform.desktop.model.Screen
import de.lino.cloud.platform.desktop.panel.DashboardScreen
import de.lino.cloud.platform.desktop.panel.FileBrowserScreen
import de.lino.cloud.platform.desktop.panel.TrashScreen
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel

/**
 * Root composable - dispatches on [AppViewModel.screen], the app's only "navigation."
 *
 * Wrapped in a [Surface] painting [MaterialTheme.colorScheme.background] behind everything - the
 * theme defines a `background`/`onBackground` pair, but nothing else in this app applies it as an
 * actual background (individual screens only paint `surface`/`surfaceVariant` on their own
 * cards/sidebar); without this wrapper, dark mode only visibly changed the parts of the screen
 * that happened to sit on an explicitly-colored `Card`/`Sidebar` background (most noticeably
 * [DashboardScreen]'s cards), while the surrounding canvas silently stayed Compose Desktop's
 * default white - not actually "every other screen is unthemed," just every *uncovered* pixel of
 * every screen.
 */
@Composable
fun App(viewModel: AppViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val screen = viewModel.screen) {
            Screen.Login -> LoginScreen(viewModel)
            Screen.Register -> RegisterScreen(viewModel)
            is Screen.RegisterConfirm -> RegisterConfirmScreen(viewModel, screen.email)
            Screen.ResetPasswordRequest -> ResetPasswordRequestScreen(viewModel)
            is Screen.ResetPasswordConfirm -> ResetPasswordConfirmScreen(viewModel, screen.email)
            is Screen.TwoFactorLogin -> TwoFactorLoginScreen(viewModel, screen.pendingToken, screen.email)
            Screen.Browser -> FileBrowserScreen(viewModel)
            Screen.Dashboard -> DashboardScreen(viewModel)
            Screen.Trash -> TrashScreen(viewModel)
        }
    }
}
