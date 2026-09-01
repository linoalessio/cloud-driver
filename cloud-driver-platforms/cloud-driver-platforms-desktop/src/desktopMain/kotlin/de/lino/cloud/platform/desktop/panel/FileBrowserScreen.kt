package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.model.Entry
import de.lino.cloud.platform.desktop.utils.formatBytes
import de.lino.cloud.platform.desktop.utils.iconFor
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.JOptionPane

// DateTimeFormatter is immutable/thread-safe (unlike SimpleDateFormat), so one shared instance is safe.
private val ENTRY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochMilli(epochMilli: Long): String = ENTRY_DATE_FORMAT.format(Instant.ofEpochMilli(epochMilli))

/** Native "open file(s)" dialog, multi-select enabled. */
private fun chooseFiles(): List<Path> {
    val dialog = FileDialog(null as Frame?, "Select file(s) to upload", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files.map { it.toPath() }
}

/**
 * The default destination directory for a download - the user's own Downloads folder. Mirrors
 * `Constraints.USER_DOWNLOADS_PATH` (`cloud-driver-api`) exactly (`Path.of(user.home)`),
 * reimplemented locally rather than depended on, same "client never depends on cloud-driver-api"
 * reasoning as `ByteFormat.kt`/`FileDownloader.kt`.
 */
private val DEFAULT_DOWNLOAD_DIRECTORY: Path = Path.of(System.getProperty("user.home"))

/** Native "choose a directory" dialog - used both for picking a local folder to upload and a download destination. */
private fun chooseDirectory(title: String, initialDirectory: Path? = null): Path? {
    val chooser = JFileChooser(initialDirectory?.toFile())
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

private fun promptForName(title: String, message: String): String? =
    JOptionPane.showInputDialog(null, message, title, JOptionPane.PLAIN_MESSAGE)?.takeIf { it.isNotBlank() }

@Composable
fun FileBrowserScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadCurrentFolder() }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    viewModel.breadcrumbs.lastOrNull()?.name() ?: "Home",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (viewModel.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // Re-fetches this folder's listing straight from the server (so, transitively,
                // straight from the database) rather than trusting whatever this client last
                // saw - useful after a change made from elsewhere (another device, a teammate,
                // the terminal package's own Command implementations) that this client's own
                // listFolders/listFiles calls wouldn't otherwise have a reason to re-run.
                OutlinedButton(
                    onClick = { viewModel.loadCurrentFolder() },
                    enabled = !viewModel.busy,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }

                OutlinedButton(
                    onClick = { promptForName("New folder", "Folder name")?.let { viewModel.createFolder(it) } },
                    enabled = !viewModel.busy,
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New folder")
                }

                UploadMenuButton(viewModel)

                // Only shown once something is selected - an empty toolbar slot for an action
                // with nothing to act on just adds visual noise, per this app's own spec.
                if (viewModel.selected.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { chooseDirectory("Select download destination", DEFAULT_DOWNLOAD_DIRECTORY)?.let { viewModel.downloadSelected(it) } },
                        enabled = !viewModel.busy,
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download selected")
                    }

                    Button(
                        onClick = { viewModel.deleteSelected() },
                        enabled = !viewModel.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete selected")
                    }
                }
            }

            viewModel.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                Spacer(Modifier.width(40.dp))
                Text("Name", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Created", modifier = Modifier.width(160.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Updated", modifier = Modifier.width(160.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Size", modifier = Modifier.width(100.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            // derivedStateOf, not a plain val: this composable also reads busy/errorMessage/breadcrumbs
            // directly (for the header/toolbar/error text above), so a plain val here would
            // reallocate both lists on every one of those unrelated recompositions too. derivedStateOf
            // only re-runs its block (and only then triggers downstream recomposition) when folders/files
            // themselves actually change - real savings once a folder holds more than a handful of entries.
            val entries: List<Entry> by remember {
                derivedStateOf { viewModel.folders.map { Entry.FolderEntry(it) } + viewModel.files.map { Entry.FileEntry(it) } }
            }

            if (entries.isEmpty() && !viewModel.busy) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.FolderOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("This folder is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        selected = viewModel.selected.contains(entry),
                        onToggleSelect = { viewModel.toggleSelected(entry) },
                        onOpen = { if (entry is Entry.FolderEntry) viewModel.openFolder(entry.folder) },
                    )
                }
            }
        }
    }
}

/** "Upload" merges the file(s)/folder pickers behind one dropdown button, rather than two separate always-visible buttons. */
@Composable
private fun UploadMenuButton(viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Button(onClick = { expanded = true }, enabled = !viewModel.busy) {
            Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Upload")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Upload file(s)") },
                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                onClick = {
                    expanded = false
                    chooseFiles().takeIf { it.isNotEmpty() }?.let { viewModel.uploadFiles(it) }
                },
            )
            DropdownMenuItem(
                text = { Text("Upload folder") },
                leadingIcon = { Icon(Icons.Filled.DriveFolderUpload, contentDescription = null) },
                onClick = {
                    expanded = false
                    chooseDirectory("Select folder to upload (will be zipped)")?.let { viewModel.uploadFolderAsZip(it) }
                },
            )
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, selected: Boolean, onToggleSelect: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, modifier = Modifier.width(40.dp))
        Icon(
            iconFor(entry),
            contentDescription = null,
            tint = if (entry is Entry.FolderEntry) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(entry.name, modifier = Modifier.weight(1f))
        Text(formatEpochMilli(entry.createdAtEpochMilli), modifier = Modifier.width(160.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatEpochMilli(entry.updatedAtEpochMilli), modifier = Modifier.width(160.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.sizeBytes?.let { formatBytes(it) } ?: "-", modifier = Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
