package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.model.Screen
import de.lino.cloud.platform.desktop.theme.ThemeMode
import de.lino.cloud.platform.desktop.utils.formatBytes
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platform.desktop.viewmodel.TransferKind
import de.lino.cloud.platform.desktop.viewmodel.TransferProgress
import de.lino.cloud.platforms.desktop.cloud_driver_platforms_desktop.generated.resources.Res
import de.lino.cloud.platforms.desktop.cloud_driver_platforms_desktop.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

/**
 * Wraps [content] with the shared post-login layout: [Sidebar] on the left, [content] filling
 * the rest, and (while [AppViewModel.transferProgress] is non-`null`) a [TransferProgressBar]
 * pinned across the full width of the bottom - below both the sidebar and the content, not just
 * the content, so an upload/download started from the file browser stays visible even if the user
 * navigates to the Dashboard mid-transfer. Both [FileBrowserScreen] and [DashboardScreen] use this
 * rather than each building their own layout, so neither the sidebar nor the progress bar is ever
 * duplicated or allowed to drift between the two.
 */
@Composable
fun AuthenticatedShell(viewModel: AppViewModel, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            Sidebar(viewModel)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                content()
            }
        }
        viewModel.transferProgress?.let { TransferProgressBar(it) }
    }
}

/**
 * A status bar fixed to the bottom of the window while [progress] is non-`null` - a determinate
 * [LinearProgressIndicator] (real byte-level progress, not an indeterminate spinner; see
 * [TransferProgress.fraction]) plus a short label ("Uploading 2 of 5 files - 3.10 MB / 7.40 MB").
 * [TransferKind.EXTRACT] (`AppViewModel.extractArchive`'s two-phase download-then-upload) shows
 * as "Extracting" throughout both phases, rather than switching between "Downloading"/"Uploading"
 * mid-operation - from the user's perspective it's one "unarchive" action, not two.
 */
@Composable
private fun TransferProgressBar(progress: TransferProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (progress.kind) {
                    TransferKind.UPLOAD -> Icons.Filled.CloudUpload
                    TransferKind.DOWNLOAD -> Icons.Filled.CloudDownload
                    TransferKind.EXTRACT -> Icons.Filled.FolderZip
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            val verb = when (progress.kind) {
                TransferKind.UPLOAD -> "Uploading"
                TransferKind.DOWNLOAD -> "Downloading"
                TransferKind.EXTRACT -> "Extracting"
            }
            Text(
                "$verb ${progress.completedFiles} of ${progress.totalFiles} file${if (progress.totalFiles == 1) "" else "s"} - " +
                    "${formatBytes(progress.transferredBytes)} / ${formatBytes(progress.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The left navigation panel every after-login screen shares (via [AuthenticatedShell]) - app
 * branding, "Dashboard"/"Home" as the two primary destinations, the current folder path nested
 * under "Home" as a clickable vertical trail (mirroring [AppViewModel.breadcrumbs] - one entry
 * per nested folder, deepest selected, only shown while browsing), and account/theme actions
 * pinned to the bottom. Styled after iCloud Drive's/Finder's own sidebar rather than a top
 * toolbar, matching this app's "modern cloud system" brief.
 */
@Composable
fun Sidebar(viewModel: AppViewModel) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Image(painterResource(Res.drawable.app_icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("cloud-driver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(28.dp))

        SidebarItem(
            icon = Icons.Filled.Dashboard,
            label = "Dashboard",
            selected = viewModel.screen == Screen.Dashboard,
            onClick = { viewModel.showDashboard() },
        )
        SidebarItem(
            icon = Icons.Filled.Home,
            label = "Home",
            selected = viewModel.screen == Screen.Browser && viewModel.breadcrumbs.isEmpty(),
            onClick = { viewModel.navigateToBreadcrumb(-1) },
        )
        if (viewModel.screen == Screen.Browser) {
            viewModel.breadcrumbs.forEachIndexed { index, folder ->
                SidebarItem(
                    icon = Icons.Filled.Folder,
                    label = folder.name(),
                    selected = index == viewModel.breadcrumbs.lastIndex,
                    indent = (index + 1) * 16,
                    onClick = { viewModel.navigateToBreadcrumb(index) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))

        SidebarItem(
            icon = if (viewModel.themeMode == ThemeMode.DARK) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            label = if (viewModel.themeMode == ThemeMode.DARK) "Light mode" else "Dark mode",
            selected = false,
            onClick = { viewModel.toggleTheme() },
        )
        SidebarItem(
            icon = Icons.AutoMirrored.Filled.Logout,
            label = "Sign out",
            selected = false,
            onClick = { viewModel.logout() },
        )
        SidebarItem(
            icon = Icons.Filled.PowerSettingsNew,
            label = "Quit",
            selected = false,
            onClick = { viewModel.quit() },
        )
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, indent: Int = 0, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (8 + indent).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}
