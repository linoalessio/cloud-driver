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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Screen-local formatter, same "not shared code" convention DashboardScreen.kt/FileBrowserScreen.kt
// each already follow for their own near-identical ENTRY_DATE_FORMAT/JOINED_DATE_FORMAT.
private val TRASH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochMilli(epochMilli: Long): String = TRASH_DATE_FORMAT.format(Instant.ofEpochMilli(epochMilli))

/**
 * The trash - every file/folder [AppViewModel.trashFiles]/[AppViewModel.trashFolders] currently
 * holds, each restorable back to where it was via [AppViewModel.restoreFile]/[AppViewModel.restoreFolder].
 * Deliberately much simpler than `FileBrowserScreen` (no drag-and-drop, multi-select, previews,
 * or nested browsing - a trashed folder's own contents aren't independently reachable here, only
 * the folder itself, matching this app's read-only-until-restored trash model) since restoring is
 * the only action a trashed item supports; there is no "delete forever" here either - that's a
 * server-side purge job (`TrashPurgeScheduler`), not something this client triggers directly.
 */
@Composable
fun TrashScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadTrash() }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Text("Trash", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Deleted files and folders. Restore anything you didn't mean to delete.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
            }

            val isEmpty = viewModel.trashFolders.isEmpty() && viewModel.trashFiles.isEmpty()
            if (isEmpty && viewModel.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Loading trash...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (isEmpty) {
                EmptyTrashNotice()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(viewModel.trashFolders, key = { it.folderId() }) { folder ->
                        TrashRow(
                            entry = Entry.FolderEntry(folder),
                            enabled = !viewModel.busy,
                            onRestore = { viewModel.restoreFolder(folder.folderId()) },
                        )
                    }
                    items(viewModel.trashFiles, key = { it.fileId() }) { file ->
                        TrashRow(
                            entry = Entry.FileEntry(file),
                            enabled = !viewModel.busy,
                            onRestore = { viewModel.restoreFile(file.fileId()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTrashNotice() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.DeleteSweep,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Trash is empty", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One trashed file/folder row - icon, name, when it was last modified, size (files only), and a "Restore" button. Unlike `FileBrowserScreen`'s `EntryRow`, there is no click-to-open/select/drag - restoring is the only interaction a trashed row supports. */
@Composable
private fun TrashRow(entry: Entry, enabled: Boolean, onRestore: () -> Unit) {
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
            Icon(iconFor(entry), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Medium)
                Text(
                    "Deleted ${formatEpochMilli(entry.updatedAtEpochMilli)}" + (entry.sizeBytes?.let { " - ${formatBytes(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onRestore, enabled = enabled) {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Restore")
            }
        }
    }
}
