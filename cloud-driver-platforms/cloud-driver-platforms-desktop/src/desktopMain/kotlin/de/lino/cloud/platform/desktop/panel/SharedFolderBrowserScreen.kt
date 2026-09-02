package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.model.Entry
import de.lino.cloud.platform.desktop.utils.formatBytes
import de.lino.cloud.platform.desktop.utils.iconFor
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

// Screen-local formatter/download-destination/chooser - same "not shared code" convention every
// other panel screen (SharedWithMeScreen.kt/TrashScreen.kt/FileBrowserScreen.kt) already follows.
private val SHARED_BROWSE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochMilli(epochMilli: Long): String = SHARED_BROWSE_DATE_FORMAT.format(Instant.ofEpochMilli(epochMilli))

private val DEFAULT_DOWNLOAD_DIRECTORY: Path = Path.of(System.getProperty("user.home"))

private fun chooseDirectory(title: String, initialDirectory: Path? = null): Path? {
    val chooser = JFileChooser(initialDirectory?.toFile())
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

/**
 * Browsing inside a folder reached via a share (item 9, added 2026-09-02 - finally implementing
 * the "browsing a shared folder's contents" extension this codebase's own docs used to describe as
 * out of scope). Reached by clicking a shared folder row in `SharedWithMeScreen`
 * ([AppViewModel.openSharedFolder]); clicking a subfolder here navigates one level deeper
 * ([AppViewModel.openSharedSubfolder]), tracked via [AppViewModel.sharedBrowseBreadcrumbs] the same
 * "current folder + breadcrumb list on the view model" shape `FileBrowserScreen` uses for the
 * caller's own folders. Read-only, like `SharedWithMeScreen` - no upload/create/move/delete/share,
 * only browsing and downloading (a single file via the existing "Download" icon, or an entire
 * subfolder recursively via [AppViewModel.downloadSharedFolder]).
 */
@Composable
fun SharedFolderBrowserScreen(viewModel: AppViewModel) {
    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SharedBreadcrumbTrail(viewModel)
                    viewModel.sharedBrowseOwnerEmail?.let {
                        Text(
                            "Shared by $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (viewModel.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(onClick = { viewModel.reloadSharedBrowseFolder() }, enabled = !viewModel.busy) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
            }

            Spacer(Modifier.height(16.dp))

            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
            }

            val isEmpty = viewModel.sharedBrowseFolders.isEmpty() && viewModel.sharedBrowseFiles.isEmpty()
            if (isEmpty && viewModel.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (isEmpty) {
                EmptySharedFolderNotice()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(viewModel.sharedBrowseFolders, key = { it.folderId() }) { folder ->
                        SharedFolderRow(
                            folder = folder,
                            enabled = !viewModel.busy,
                            onOpen = { viewModel.openSharedSubfolder(folder) },
                            onDownload = {
                                chooseDirectory("Select download destination", DEFAULT_DOWNLOAD_DIRECTORY)?.let {
                                    viewModel.downloadSharedFolder(folder.folderId(), folder.name(), it)
                                }
                            },
                        )
                    }
                    items(viewModel.sharedBrowseFiles, key = { it.fileId() }) { file ->
                        SharedBrowseFileRow(
                            file = file,
                            enabled = !viewModel.busy,
                            onDownload = {
                                chooseDirectory("Select download destination", DEFAULT_DOWNLOAD_DIRECTORY)?.let {
                                    viewModel.downloadEntries(listOf(Entry.FileEntry(file)), it)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Clickable breadcrumb trail across [AppViewModel.sharedBrowseBreadcrumbs] - mirrors `FileBrowserScreen`'s own `BreadcrumbTrail` shape, but with no "Home" segment (the shared folder itself is the topmost reachable node). */
@Composable
private fun SharedBreadcrumbTrail(viewModel: AppViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        viewModel.sharedBrowseBreadcrumbs.forEachIndexed { index, folder ->
            if (index > 0) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            val isCurrent = index == viewModel.sharedBrowseBreadcrumbs.lastIndex
            Text(
                folder.name(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = if (isCurrent) Modifier else Modifier.clickable { viewModel.navigateSharedBreadcrumb(index) },
            )
        }
    }
}

@Composable
private fun EmptySharedFolderNotice() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FolderOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("This folder is empty", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One shared subfolder row - click to browse into it, or use the download icon to fetch it (and everything nested inside it) all at once. */
@Composable
private fun SharedFolderRow(folder: FolderResponse, enabled: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name(), fontWeight = FontWeight.Medium)
                Text(
                    "Updated ${formatEpochMilli(folder.modifiedAtEpochMillis())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onDownload, enabled = enabled) {
                Icon(Icons.Filled.Download, contentDescription = "Download folder")
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** One shared file row within a browsed folder - name, size, and a "Download" icon. No preview/click-to-open here, matching `SharedWithMeScreen`'s own read-only row shape. */
@Composable
private fun SharedBrowseFileRow(file: StoredFileSummaryResponse, enabled: Boolean, onDownload: () -> Unit) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconFor(Entry.FileEntry(file)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(file.fileName(), fontWeight = FontWeight.Medium)
                Text(
                    "Updated ${formatEpochMilli(file.updatedAtEpochMilli())} - ${formatBytes(file.sizeBytes())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onDownload, enabled = enabled) {
                Icon(Icons.Filled.Download, contentDescription = "Download")
            }
        }
    }
}
