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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * holds, each restorable back to where it was via [AppViewModel.restoreFile]/[AppViewModel.restoreFolder],
 * or permanently removed all at once via **"Empty trash bin"** (added 2026-09-02 - [AppViewModel.emptyTrash],
 * gated behind [EmptyTrashConfirmationDialog] the same "explicit, separate confirmation click"
 * pattern `DashboardScreen`'s own Uninstall action uses, since this is irreversible). Deliberately
 * much simpler than `FileBrowserScreen` (no drag-and-drop, multi-select, previews, or nested
 * browsing - a trashed folder's own contents aren't independently reachable here, only the folder
 * itself, matching this app's read-only-until-restored trash model) - restoring and emptying are
 * the only actions a trashed item supports. Each row also shows when it becomes eligible for
 * permanent removal under the server's configured retention window (added 2026-09-02 - see
 * `TrashedFileSummary`/`TrashedFolderSummary`'s own Javadoc server-side); `TrashPurgeScheduler`
 * itself is still never started automatically (see its own Javadoc), so on a deployment that
 * never wires it in, that timestamp reflects the *configured* window, not a guaranteed purge.
 */
@Composable
fun TrashScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadTrash() }

    var showEmptyConfirmation by remember { mutableStateOf(false) }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Trash", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Deleted files and folders. Restore anything you didn't mean to delete.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val isEmpty = viewModel.trashFolders.isEmpty() && viewModel.trashFiles.isEmpty()
                OutlinedButton(
                    onClick = { showEmptyConfirmation = true },
                    enabled = !viewModel.busy && !isEmpty,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Empty trash bin")
                }
            }

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
                    items(viewModel.trashFolders, key = { it.folder().folderId() }) { trashed ->
                        TrashRow(
                            entry = Entry.FolderEntry(trashed.folder()),
                            purgeAtEpochMillis = trashed.purgeAtEpochMillis(),
                            enabled = !viewModel.busy,
                            onRestore = { viewModel.restoreFolder(trashed.folder().folderId()) },
                        )
                    }
                    items(viewModel.trashFiles, key = { it.file().fileId() }) { trashed ->
                        TrashRow(
                            entry = Entry.FileEntry(trashed.file()),
                            purgeAtEpochMillis = trashed.purgeAtEpochMillis(),
                            enabled = !viewModel.busy,
                            onRestore = { viewModel.restoreFile(trashed.file().fileId()) },
                        )
                    }
                }
            }
        }
    }

    if (showEmptyConfirmation) {
        EmptyTrashConfirmationDialog(
            onConfirm = {
                showEmptyConfirmation = false
                viewModel.emptyTrash()
            },
            onDismiss = { showEmptyConfirmation = false },
        )
    }
}

/** Blocks "Empty trash bin" from ever firing without an explicit, separate confirmation click - the same pattern `DashboardScreen`'s `UninstallConfirmationDialog` uses for its own irreversible action. */
@Composable
private fun EmptyTrashConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Empty trash bin?") },
        text = {
            Text(
                "This permanently deletes every file and folder currently in your trash, right now - " +
                    "bypassing the usual retention window entirely. This cannot be undone.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Empty trash bin")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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

/**
 * One trashed file/folder row - icon, name, when it was last modified, size (files only), when it
 * becomes eligible for permanent removal ([purgeAtEpochMillis], added 2026-09-02), and a "Restore"
 * button. Unlike `FileBrowserScreen`'s `EntryRow`, there is no click-to-open/select/drag -
 * restoring is the only per-row interaction a trashed row supports (emptying the whole trash is a
 * separate, screen-level action).
 */
@Composable
private fun TrashRow(entry: Entry, purgeAtEpochMillis: Long, enabled: Boolean, onRestore: () -> Unit) {
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
                Text(
                    "Permanently deleted on ${formatEpochMilli(purgeAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
