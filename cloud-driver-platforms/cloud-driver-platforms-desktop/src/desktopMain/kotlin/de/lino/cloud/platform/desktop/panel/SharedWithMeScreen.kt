package de.lino.cloud.platform.desktop.panel

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

// Screen-local formatter/download-destination/chooser - same "not shared code" convention
// FileBrowserScreen.kt/TrashScreen.kt/DashboardScreen.kt each already follow for their own
// near-identical helpers, rather than exposing FileBrowserScreen.kt's private internals here.
private val SHARED_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochMilli(epochMilli: Long): String = SHARED_DATE_FORMAT.format(Instant.ofEpochMilli(epochMilli))

private val DEFAULT_DOWNLOAD_DIRECTORY: Path = Path.of(System.getProperty("user.home"))

private fun chooseDirectory(title: String, initialDirectory: Path? = null): Path? {
    val chooser = JFileChooser(initialDirectory?.toFile())
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

/**
 * Files/folders other accounts have directly shared with the signed-in account (item 9, see
 * `architecture/SERVICES.md`) - read-only, much simpler than `FileBrowserScreen`: no selection,
 * drag-and-drop, previews, or nested browsing. A shared folder's own contents aren't independently
 * reachable here (browsing a shared folder's contents is a documented server-side future
 * extension, not implemented yet - see `CloudUserService#listSharedFoldersWithMe`'s own Javadoc),
 * so a shared folder row is display-only; a shared file can be downloaded directly - the server's
 * `GET /files/{id}/content` route already honors a share the exact same way it honors ownership.
 * Each row shows who shared it (`SharedRow`'s `ownerEmail`, added 2026-09-02).
 */
@Composable
fun SharedWithMeScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadSharedWithMe() }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Text("Shared with me", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Files and folders other accounts have shared with you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
            }

            val isEmpty = viewModel.sharedWithMeFolders.isEmpty() && viewModel.sharedWithMeFiles.isEmpty()
            if (isEmpty && viewModel.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (isEmpty) {
                EmptySharedNotice()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(viewModel.sharedWithMeFolders, key = { it.folder().folderId() }) { shared ->
                        SharedRow(entry = Entry.FolderEntry(shared.folder()), ownerEmail = shared.ownerEmail(), enabled = !viewModel.busy, onDownload = null)
                    }
                    items(viewModel.sharedWithMeFiles, key = { it.file().fileId() }) { shared ->
                        val file = shared.file()
                        SharedRow(
                            entry = Entry.FileEntry(file),
                            ownerEmail = shared.ownerEmail(),
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

@Composable
private fun EmptySharedNotice() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FolderShared,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Nothing has been shared with you yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One shared file/folder row - icon, name, who shared it ([ownerEmail], added 2026-09-02 - the
 * whole reason a recipient can tell entries apart when several people share with them), and size
 * (files only). [onDownload] is `null` for a folder row (not independently browsable/downloadable
 * here).
 */
@Composable
private fun SharedRow(entry: Entry, ownerEmail: String, enabled: Boolean, onDownload: (() -> Unit)?) {
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
            Icon(
                iconFor(entry),
                contentDescription = null,
                tint = if (entry is Entry.FolderEntry) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Medium)
                Text(
                    "Shared by $ownerEmail - updated ${formatEpochMilli(entry.updatedAtEpochMilli)}"
                        + (entry.sizeBytes?.let { " - ${formatBytes(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDownload != null) {
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = onDownload, enabled = enabled) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
                }
            }
        }
    }
}
