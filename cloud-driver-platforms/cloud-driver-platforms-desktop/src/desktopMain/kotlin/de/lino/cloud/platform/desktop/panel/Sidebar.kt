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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Close
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
import de.lino.cloud.platform.desktop.theme.CloudColors
import de.lino.cloud.platform.desktop.theme.IconTile
import de.lino.cloud.platform.desktop.theme.StorageBar
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
        if (viewModel.showKeychainFallbackNotice) {
            KeychainFallbackNotice(onDismiss = { viewModel.dismissKeychainFallbackNotice() })
        }
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
 * A dismissible warning banner shown across every authenticated screen (see [AuthenticatedShell])
 * while [AppViewModel.showKeychainFallbackNotice] is `true` - i.e. no real OS keychain/secret
 * service was found and the session token is instead persisted to a permission-restricted plain
 * file (see [de.lino.cloud.platform.desktop.client.CloudDriverClient.usedKeychainFallback]).
 * Surfacing this explicitly, rather than silently degrading to the less-secure fallback, matches
 * [de.lino.cloud.platform.rest.api.session.TokenStoreFactory]'s own Javadoc instruction to callers.
 */
@Composable
private fun KeychainFallbackNotice(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "No system keychain was found - your session is stored in a plain, permission-restricted file instead of a secure OS keychain.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
        }
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
 * branding, "Dashboard"/"Home" as the two primary destinations (each drawn as a colored [IconTile]
 * "app icon", not a flat monochrome glyph - modeled on the real macOS iCloud app's own Photos/
 * Drive/Mail-style icon grid), the current folder path nested under "Home" as a clickable vertical
 * trail (mirroring [AppViewModel.breadcrumbs] - one entry per nested folder, deepest selected,
 * only shown while browsing), a compact storage capsule mirroring Finder's own "used of total"
 * disk-space indicator for a mounted volume, and account/theme actions pinned to the bottom.
 */
@Composable
fun Sidebar(viewModel: AppViewModel) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(248.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Image(painterResource(Res.drawable.app_icon), contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("cloud-driver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))

            SidebarItem(
                label = "Dashboard",
                selected = viewModel.screen == Screen.Dashboard,
                onClick = { viewModel.showDashboard() },
            ) { IconTile(Icons.Filled.Dashboard, CloudColors.Blue) }

            if (viewModel.currentUserIsAdmin) {
                SidebarItem(
                    label = "Admin",
                    selected = viewModel.screen == Screen.Admin,
                    onClick = { viewModel.showAdmin() },
                ) { IconTile(Icons.Filled.AdminPanelSettings, CloudColors.Orange) }
            }
            SidebarItem(
                label = "Trash",
                selected = viewModel.screen == Screen.Trash,
                onClick = { viewModel.showTrash() },
            ) { IconTile(Icons.Filled.Delete, CloudColors.Gray) }
            SidebarItem(
                label = "Shared with me",
                selected = viewModel.screen == Screen.SharedWithMe || viewModel.screen == Screen.SharedFolderBrowser,
                onClick = { viewModel.showSharedWithMe() },
            ) { IconTile(Icons.Filled.FolderShared, CloudColors.Purple) }
            SidebarItem(
                label = "Home",
                selected = viewModel.screen == Screen.Browser && viewModel.breadcrumbs.isEmpty(),
                onClick = { viewModel.navigateToBreadcrumb(-1) },
            ) { IconTile(Icons.Filled.Home, CloudColors.Teal) }

            if (viewModel.screen == Screen.Browser) {
                viewModel.breadcrumbs.forEachIndexed { index, folder ->
                    SidebarItem(
                        label = folder.name(),
                        selected = index == viewModel.breadcrumbs.lastIndex,
                        indent = (index + 1) * 16,
                        onClick = { viewModel.navigateToBreadcrumb(index) },
                    ) { PlainSidebarIcon(Icons.Filled.Folder, tint = CloudColors.Blue) }
                }
            }

            Spacer(Modifier.weight(1f))

            SidebarStorageSummary(viewModel)

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))

            SidebarItem(
                label = if (viewModel.themeMode == ThemeMode.DARK) "Light mode" else "Dark mode",
                selected = false,
                onClick = { viewModel.toggleTheme() },
            ) { PlainSidebarIcon(if (viewModel.themeMode == ThemeMode.DARK) Icons.Filled.LightMode else Icons.Filled.DarkMode) }
            SidebarItem(
                label = "Sign out",
                selected = false,
                onClick = { viewModel.logout() },
            ) { PlainSidebarIcon(Icons.AutoMirrored.Filled.Logout) }
            SidebarItem(
                label = "Quit",
                selected = false,
                onClick = { viewModel.quit() },
            ) { PlainSidebarIcon(Icons.Filled.PowerSettingsNew) }
        }

        // A hairline separator between the sidebar and the content pane - real macOS sidebars
        // (Finder, Mail, System Settings) always draw one, even though the sidebar's own tinted
        // background already differs slightly from the content area's.
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/**
 * A compact "used of total" capsule mirroring Finder's own disk-space indicator for a mounted
 * volume, or the macOS iCloud app's own storage bar shrunk to sidebar scale - rendered only once
 * [AppViewModel.currentUserUploadedBytes]/[AppViewModel.currentUserMaxBytesToUpload] have actually
 * loaded (both are populated right after sign-in via `AppViewModel#refreshAccountInfo`, so this is
 * normally visible on every authenticated screen, not just the Dashboard).
 */
@Composable
private fun SidebarStorageSummary(viewModel: AppViewModel) {
    val uploaded = viewModel.currentUserUploadedBytes
    val max = viewModel.currentUserMaxBytesToUpload
    if (uploaded == null || max == null || max <= 0) return

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            "Storage",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        StorageBar(
            segments = listOf((uploaded.toFloat() / max.toFloat()) to MaterialTheme.colorScheme.primary),
            height = 6.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${formatBytes(uploaded)} of ${formatBytes(max)} used",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SidebarItem(label: String, selected: Boolean, indent: Int = 0, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (8 + indent).dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 156.dp),
        )
    }
}

/**
 * A plain, monochrome sidebar glyph (no [IconTile] backing) for a utility row - theme toggle,
 * sign out, quit, and a nested breadcrumb folder - mirroring macOS's own convention that only
 * destinations/services get a colorful icon tile, while a plain action keeps a flat outline
 * glyph. Sized to visually match [IconTile]'s own footprint so rows line up regardless of which
 * leading slot they use.
 */
@Composable
private fun PlainSidebarIcon(icon: ImageVector, tint: Color? = null) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
    }
}
