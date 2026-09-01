package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.model.Entry
import de.lino.cloud.platform.desktop.utils.formatBytes
import de.lino.cloud.platform.desktop.utils.iconFor
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
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

/**
 * The header's current-location display: a clickable "Home / Folder1 / Folder2" trail mirroring
 * [AppViewModel.breadcrumbs] - clicking any earlier segment (including "Home") navigates straight
 * there via [AppViewModel.navigateToBreadcrumb], the same call the sidebar's own breadcrumb trail
 * already uses (see `Sidebar.kt`) so both stay in sync. The current (last) segment renders bold
 * and non-interactive - already where the user is, so nothing to navigate to.
 */
@Composable
private fun BreadcrumbTrail(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BreadcrumbSegment("Home", current = viewModel.breadcrumbs.isEmpty(), onClick = { viewModel.navigateToBreadcrumb(-1) })
        viewModel.breadcrumbs.forEachIndexed { index, folder ->
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            BreadcrumbSegment(
                folder.name(),
                current = index == viewModel.breadcrumbs.lastIndex,
                onClick = { viewModel.navigateToBreadcrumb(index) },
            )
        }
    }
}

@Composable
private fun BreadcrumbSegment(label: String, current: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (current) Modifier else Modifier.clickable(onClick = onClick),
    )
}

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

    // The entry a right-click "Move to..." was requested for, if any - drives the dialog
    // rendered below, outside AuthenticatedShell's own content so it overlays the whole screen
    // (sidebar included) rather than just the file listing. Declared here, at this composable's
    // own top level, rather than inside AuthenticatedShell's content lambda, precisely so it's
    // still in scope down there.
    var moveDialogEntry by remember { mutableStateOf<Entry?>(null) }

    AuthenticatedShell(viewModel) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreadcrumbTrail(viewModel, modifier = Modifier.weight(1f))
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

            // Drag-and-drop-to-move state, local to this screen - none of it is persisted, so it
            // lives here rather than on AppViewModel. rowBounds is a plain (non-Compose-state) map:
            // it's only ever read/written imperatively from inside drag-gesture callbacks, never
            // during composition, so making it observable state would just cost recompositions for
            // no benefit. draggedEntries/hoveredFolderId, in contrast, ARE read during composition
            // (to render the dragged-row dim/drop-target highlight below), so those stay Compose state.
            val rowBounds = remember { mutableMapOf<String, Rect>() }
            var draggedEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
            var hoveredFolderId by remember { mutableStateOf<String?>(null) }

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
                        enabled = !viewModel.busy,
                        isBeingDragged = draggedEntries.any { it.id == entry.id },
                        isDropTarget = entry.id == hoveredFolderId,
                        onToggleSelect = { viewModel.toggleSelected(entry) },
                        onOpen = { if (entry is Entry.FolderEntry) viewModel.openFolder(entry.folder) },
                        onRegisterBounds = { bounds -> if (entry is Entry.FolderEntry) rowBounds[entry.id] = bounds },
                        onUnregisterBounds = { if (entry is Entry.FolderEntry) rowBounds.remove(entry.id) },
                        // Dragging an entry that's part of the current multi-selection moves the
                        // whole selection, the same "drag what's under the pointer, unless a
                        // multi-selection is already active" convention Finder uses; dragging
                        // anything else moves only that one entry, leaving the selection untouched.
                        onDragStart = {
                            draggedEntries = if (viewModel.selected.contains(entry)) viewModel.selected.toList() else listOf(entry)
                        },
                        onDragMove = { windowPosition ->
                            hoveredFolderId = rowBounds.entries
                                .firstOrNull { (folderId, bounds) -> bounds.contains(windowPosition) && draggedEntries.none { it.id == folderId } }
                                ?.key
                        },
                        onDragEnd = {
                            val target = hoveredFolderId
                            val moving = draggedEntries
                            draggedEntries = emptyList()
                            hoveredFolderId = null
                            if (target != null && moving.isNotEmpty()) viewModel.moveEntriesToFolder(moving, target)
                        },
                        onDragCancel = {
                            draggedEntries = emptyList()
                            hoveredFolderId = null
                        },
                        onDownloadRequest = {
                            chooseDirectory("Select download destination", DEFAULT_DOWNLOAD_DIRECTORY)?.let {
                                viewModel.downloadEntries(listOf(entry), it)
                            }
                        },
                        onMoveRequest = { moveDialogEntry = entry },
                        onDeleteRequest = { viewModel.deleteEntries(listOf(entry)) },
                    )
                }
            }
        }
    }

    moveDialogEntry?.let { entry ->
        MoveToFolderDialog(viewModel = viewModel, entry = entry, onDismiss = { moveDialogEntry = null })
    }
}

/**
 * The context menu's "Move to..." action: lets the user navigate the caller's own folder tree
 * (starting at the root) and move [entry] into whichever folder they land on, including the root
 * itself - unlike drag-and-drop (which can only target a folder already visible in the current
 * listing), this reaches any folder in the account. Loads one level of subfolders at a time via
 * [AppViewModel.client] directly (a listings-only call, not a full [AppViewModel] action) since
 * this dialog's own navigation is local, transient state that has no reason to go through
 * [AppViewModel.run]'s busy-guarded action machinery.
 */
@Composable
private fun MoveToFolderDialog(viewModel: AppViewModel, entry: Entry, onDismiss: () -> Unit) {
    var targetFolderId by remember { mutableStateOf<String?>(null) }
    var targetFolderName by remember { mutableStateOf("Home") }
    val path = remember { mutableStateListOf<FolderResponse>() }
    var subfolders by remember { mutableStateOf<List<FolderResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(targetFolderId) {
        loading = true
        loadError = null
        try {
            subfolders = viewModel.client.listFolders(targetFolderId)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load folders"
        } finally {
            loading = false
        }
    }

    fun navigateUp() {
        if (path.isEmpty()) return
        path.removeAt(path.lastIndex)
        targetFolderId = path.lastOrNull()?.folderId()
        targetFolderName = path.lastOrNull()?.name() ?: "Home"
    }

    fun navigateInto(folder: FolderResponse) {
        path.add(folder)
        targetFolderId = folder.folderId()
        targetFolderName = folder.name()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"${entry.name}\"") },
        text = {
            Column(Modifier.fillMaxWidth().height(320.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path.isNotEmpty()) {
                        IconButton(onClick = ::navigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    }
                    Text(targetFolderName, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                    else -> {
                        // A folder can't be moved into itself - excluded here so it's never an
                        // option to navigate into in the first place, rather than relying solely
                        // on the server's own cycle check (which still guards deeper cases, e.g.
                        // moving into a descendant of entry, that this shallow filter doesn't).
                        val selectable = subfolders.filter { it.folderId() != entry.id }
                        if (selectable.isEmpty()) {
                            Text("No subfolders here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                items(selectable, key = { it.folderId() }) { folder ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { navigateInto(folder) }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(folder.name(), modifier = Modifier.weight(1f))
                                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.moveEntryToFolder(entry, targetFolderId)
                onDismiss()
            }) {
                Text("Move here")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/**
 * One row in the file browser. Also doubles as a drag-and-drop source (any entry) and, if it's a
 * [Entry.FolderEntry], a drop target: press-and-hold-then-move (`detectDragGesturesAfterLongPress`,
 * rather than a plain drag detector) so a quick tap still reaches the `.clickable` below it
 * unchanged - opening a folder, or toggling selection via the checkbox, works exactly as before.
 * [onRegisterBounds]/[onUnregisterBounds] track this row's own on-screen position (in window
 * coordinates) so the caller can resolve which folder row, if any, a drag is currently hovering.
 *
 * Right-clicking anywhere on the row opens a context menu (Download/Move to.../Delete) at the
 * cursor - detected via a second, independent `pointerInput` block reading raw pointer events
 * rather than [androidx.compose.foundation.ContextMenuArea] (which replaces the platform's own
 * text-selection context menu, not a good fit for a whole row) - it only *observes* the press
 * (never calls `change.consume()`), so left-click/drag handling above is untouched.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EntryRow(
    entry: Entry,
    selected: Boolean,
    enabled: Boolean,
    isBeingDragged: Boolean,
    isDropTarget: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onRegisterBounds: (Rect) -> Unit,
    onUnregisterBounds: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDownloadRequest: () -> Unit,
    onMoveRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var rowBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(entry.id) {
        onDispose { onUnregisterBounds() }
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    rowBoundsInWindow = coordinates.boundsInWindow()
                    onRegisterBounds(rowBoundsInWindow)
                }
                .background(
                    when {
                        isDropTarget -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(10.dp),
                )
                .let { base -> if (isDropTarget) base.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else base }
                .alpha(if (isBeingDragged) 0.4f else 1f)
                .pointerInput(entry.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            onDragMove(rowBoundsInWindow.topLeft + change.position)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() },
                    )
                }
                .pointerInput(entry.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                                contextMenuOffset = event.changes.first().position
                                contextMenuExpanded = true
                            }
                        }
                    }
                }
                .clickable(enabled = enabled, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, enabled = enabled, modifier = Modifier.width(40.dp))
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

        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = { contextMenuExpanded = false },
            offset = with(LocalDensity.current) { DpOffset(contextMenuOffset.x.toDp(), contextMenuOffset.y.toDp()) },
        ) {
            DropdownMenuItem(
                text = { Text("Download") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                enabled = enabled,
                onClick = { contextMenuExpanded = false; onDownloadRequest() },
            )
            DropdownMenuItem(
                text = { Text("Move to...") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                enabled = enabled,
                onClick = { contextMenuExpanded = false; onMoveRequest() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                enabled = enabled,
                onClick = { contextMenuExpanded = false; onDeleteRequest() },
            )
        }
    }
}
