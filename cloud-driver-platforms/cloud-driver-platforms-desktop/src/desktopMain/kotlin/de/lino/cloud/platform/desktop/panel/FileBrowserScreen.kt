package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.desktop.model.Entry
import de.lino.cloud.platform.desktop.model.SortOption
import de.lino.cloud.platform.desktop.theme.FolderColorOption
import de.lino.cloud.platform.desktop.utils.colorFor
import de.lino.cloud.platform.desktop.utils.formatBytes
import de.lino.cloud.platform.desktop.utils.iconFor
import de.lino.cloud.platform.desktop.utils.isZipArchive
import de.lino.cloud.platform.desktop.utils.rememberThumbnail
import de.lino.cloud.platform.desktop.utils.sortedFiles
import de.lino.cloud.platform.desktop.utils.sortedFolders
import de.lino.cloud.platform.desktop.viewmodel.AppViewModel
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
import java.awt.FileDialog
import java.awt.Frame
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.JOptionPane

// DateTimeFormatter is immutable/thread-safe (unlike SimpleDateFormat), so one shared instance is safe.
private val ENTRY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochMilli(epochMilli: Long): String = ENTRY_DATE_FORMAT.format(Instant.ofEpochMilli(epochMilli))

/** Two clicks land within this window (system default double-click speed, roughly) to count as a double-click - see `EntryRow`'s own click handling. */
private const val DOUBLE_CLICK_THRESHOLD_MILLIS = 400L

/** How long `ShareDialog` waits after the last keystroke in its grantee-email field before firing a live existence check - avoids a network round trip on every keystroke. */
private const val EMAIL_CHECK_DEBOUNCE_MILLIS = 400L

/**
 * Fixed expiry choices offered by `ShareDialog` for both a share grant and a public link, rather
 * than a full date/time picker - matches the low-friction feel of the rest of this app's dialogs.
 * [epochMillisFrom] resolves a choice against the current wall-clock time at the moment it's used
 * (not when the enum constant was declared), so [NEVER] is the only one with no expiry at all.
 */
private enum class ExpiryPreset(val label: String, private val durationMillis: Long?) {
    NEVER("Never", null),
    ONE_DAY("1 day", 24L * 60 * 60 * 1000),
    SEVEN_DAYS("7 days", 7L * 24 * 60 * 60 * 1000),
    THIRTY_DAYS("30 days", 30L * 24 * 60 * 60 * 1000);

    fun epochMillisFrom(nowEpochMillis: Long): Long? = this.durationMillis?.let { nowEpochMillis + it }
}

/** A small "View"/"Edit" dropdown selector, reused by `ShareDialog` for the permission granted alongside a new share. */
@Composable
private fun PermissionDropdown(selected: String, onSelect: (String) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(if (selected == "EDIT") "Edit" else "View")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("View") }, onClick = { onSelect("VIEW"); expanded = false })
            DropdownMenuItem(text = { Text("Edit") }, onClick = { onSelect("EDIT"); expanded = false })
        }
    }
}

/** A small expiry-preset dropdown selector, reused by `ShareDialog` for both a share grant's and a public link's expiry. */
@Composable
private fun ExpiryDropdown(selected: ExpiryPreset, onSelect: (ExpiryPreset) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(selected.label)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (preset in ExpiryPreset.entries) {
                DropdownMenuItem(text = { Text(preset.label) }, onClick = { onSelect(preset); expanded = false })
            }
        }
    }
}

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

// ExperimentalFoundationApi guards Modifier.dragAndDropTarget (external-file-drop support, below);
// ExperimentalComposeUiApi guards DragAndDropEvent#dragData() - both are the current, non-deprecated
// Compose Multiplatform drag-and-drop API as of Compose 1.7.1, just not yet stable.
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FileBrowserScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadCurrentFolder() }

    // The entry a right-click "Move to..." was requested for, if any - drives the dialog
    // rendered below, outside AuthenticatedShell's own content so it overlays the whole screen
    // (sidebar included) rather than just the file listing. Declared here, at this composable's
    // own top level, rather than inside AuthenticatedShell's content lambda, precisely so it's
    // still in scope down there.
    var moveDialogEntry by remember { mutableStateOf<Entry?>(null) }

    // The entry a right-click "Share" was requested for, if any - drives ShareDialog, rendered
    // below the same "declared at this composable's own top level, outside AuthenticatedShell's
    // content" way moveDialogEntry is, for the same reason (overlays the whole screen).
    var shareDialogEntry by remember { mutableStateOf<Entry?>(null) }

    // The folder a right-click "Set color" was requested for, if any - drives FolderColorPickerDialog,
    // same "declared at this composable's own top level, outside AuthenticatedShell's content" shape.
    var colorPickerEntry by remember { mutableStateOf<Entry.FolderEntry?>(null) }

    // The file a double-click was requested for, if any - see EntryRow's own click handling and
    // FilePreviewDialog. Same "declared at this composable's own top level, rendered outside
    // AuthenticatedShell's content" shape as moveDialogEntry above, for the same reason.
    var previewEntry by remember { mutableStateOf<Entry.FileEntry?>(null) }

    // The file a context-menu "Version history" was requested for, if any - drives VersionHistoryDialog.
    var versionHistoryEntry by remember { mutableStateOf<Entry.FileEntry?>(null) }

    // The entry a context-menu "Activity" was requested for, if any - drives EntryActivityDialog.
    var activityDialogEntry by remember { mutableStateOf<Entry?>(null) }

    // Whether an OS-level drag (from Finder/Explorer) is currently hovering this screen - drives
    // the highlighted drop-zone overlay below. Purely local, transient UI-gesture state, same
    // reasoning FileBrowserScreen's own in-app drag state (draggedEntries/hoveredFolderId) is kept
    // local rather than on AppViewModel.
    var isExternalDragActive by remember { mutableStateOf(false) }

    // The Modifier.dragAndDropTarget callback object - remembered once (its methods close over
    // viewModel/isExternalDragActive, both stable across this composable's lifetime) rather than
    // rebuilt every recomposition. A drop yields a list of `file:` URI strings (readFiles()) -
    // parsed back into Paths and handed to AppViewModel.uploadDroppedPaths, which uploads a plain
    // file as-is and zips a dropped directory first, matching this screen's "folder upload = zip"
    // convention used everywhere else (UploadMenuButton's own "Upload folder" picker).
    val externalDropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { isExternalDragActive = true }
            override fun onEntered(event: DragAndDropEvent) { isExternalDragActive = true }
            override fun onExited(event: DragAndDropEvent) { isExternalDragActive = false }
            override fun onEnded(event: DragAndDropEvent) { isExternalDragActive = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isExternalDragActive = false
                val droppedPaths = (event.dragData() as? DragData.FilesList)
                    ?.readFiles()
                    ?.mapNotNull { uri -> runCatching { Path.of(URI(uri)) }.getOrNull() }
                    .orEmpty()
                if (droppedPaths.isEmpty()) return false
                viewModel.uploadDroppedPaths(droppedPaths)
                return true
            }
        }
    }

    AuthenticatedShell(viewModel) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    // Drag a file/folder in from the OS anywhere onto this screen to upload it
                    // straight into the currently open folder - the drag-into-the-app counterpart
                    // to this screen's existing drag-within-the-app row moving.
                    .dragAndDropTarget(
                        // Deliberately not also checking `event.dragData() is DragData.FilesList`
                        // here: on macOS, AWT's native drag-and-drop only exposes real data
                        // flavors once the drop actually happens - during dragEnter/dragOver (what
                        // this "started" event fires from), Transferable#isDataFlavorSupported
                        // reports nothing, so `dragData()` always resolves to something other than
                        // FilesList and this check silently rejected every external drag before
                        // the drop target ever activated (no overlay, no drop effect - nothing).
                        // The actual FilesList check already happens where the data is guaranteed
                        // to be available: inside externalDropTarget#onDrop below.
                        shouldStartDragAndDrop = { !viewModel.busy },
                        target = externalDropTarget,
                    ),
            ) {
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

                SortMenuButton(viewModel)

                // Opens a small overlay to search every file the caller owns by name/content,
                // always global rather than scoped to the current folder - matching the server
                // route's own always-global scope, so there's nothing to narrow client-side.
                OutlinedButton(onClick = { viewModel.openSearchOverlay() }, enabled = !viewModel.busy) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search")
                }

                // Only shown once something is selected - an empty toolbar slot for an action
                // with nothing to act on just adds visual noise, per this app's own spec. Bundled
                // behind one "Options" dropdown, rather than three always-visible buttons, since
                // three inline buttons plus the toolbar's other buttons could overflow this Row's
                // width once "Delete selected" was added - which pushed "Delete selected" itself
                // off-screen with no scroll affordance to reach it, the exact bug this fixes.
                if (viewModel.selected.isNotEmpty()) {
                    SelectionOptionsMenuButton(viewModel)
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
                derivedStateOf {
                    val folders = sortedFolders(viewModel.folders, viewModel.folderSortOption, viewModel.folderSizes)
                    val files = sortedFiles(viewModel.files, viewModel.fileSortOption)
                    folders.map { Entry.FolderEntry(it) } + files.map { Entry.FileEntry(it) }
                }
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
                        client = viewModel.client,
                        selected = viewModel.selected.contains(entry),
                        enabled = !viewModel.busy,
                        isBeingDragged = draggedEntries.any { it.id == entry.id },
                        isDropTarget = entry.id == hoveredFolderId,
                        onToggleSelect = { viewModel.toggleSelected(entry) },
                        onOpen = { if (entry is Entry.FolderEntry) viewModel.openFolder(entry.folder) },
                        // A double-clicked ZIP archive is extracted into the current folder
                        // instead of opening a preview - see AppViewModel.extractArchive.
                        onPreviewRequest = {
                            if (entry is Entry.FileEntry) {
                                if (isZipArchive(entry.summary.contentType())) viewModel.extractArchive(entry) else previewEntry = entry
                            }
                        },
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
                        onDuplicateRequest = { viewModel.duplicateEntries(listOf(entry)) },
                        onDeleteRequest = { viewModel.deleteEntries(listOf(entry)) },
                        onShareRequest = { shareDialogEntry = entry },
                        onSetColorRequest = { if (entry is Entry.FolderEntry) colorPickerEntry = entry },
                        onVersionHistoryRequest = { if (entry is Entry.FileEntry) versionHistoryEntry = entry },
                        onActivityRequest = { activityDialogEntry = entry },
                    )
                }
                // Explicit "Load more" rather than auto-loading on scroll - a large folder's next
                // page is a real network round trip (see CloudDriverClient.listFilesPage/
                // listFoldersPage's own Javadoc for why this bounds response size, not per-request
                // scan cost), so triggering it only on a deliberate click keeps that cost visible
                // and predictable rather than firing silently as the user scrolls past the fold.
                if (viewModel.hasMoreEntries) {
                    item(key = "__load_more__") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = { viewModel.loadMoreEntries() }, enabled = !viewModel.busy) {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
            }

            if (isExternalDragActive) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 6.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                        ) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Drop to upload here",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }

    moveDialogEntry?.let { entry ->
        MoveToFolderDialog(viewModel = viewModel, entry = entry, onDismiss = { moveDialogEntry = null })
    }

    shareDialogEntry?.let { entry ->
        ShareDialog(viewModel = viewModel, entry = entry, onDismiss = { shareDialogEntry = null })
    }

    colorPickerEntry?.let { entry ->
        FolderColorPickerDialog(
            currentColor = FolderColorOption.forName(entry.folder.color()),
            onSelect = { color -> viewModel.setFolderColor(entry.folder, color); colorPickerEntry = null },
            onDismiss = { colorPickerEntry = null },
        )
    }

    previewEntry?.let { entry ->
        FilePreviewDialog(entry = entry, client = viewModel.client, onDismiss = { previewEntry = null })
    }

    versionHistoryEntry?.let { entry ->
        VersionHistoryDialog(viewModel = viewModel, entry = entry, onDismiss = { versionHistoryEntry = null })
    }

    activityDialogEntry?.let { entry ->
        EntryActivityDialog(viewModel = viewModel, entry = entry, onDismiss = { activityDialogEntry = null })
    }

    if (viewModel.searchOverlayOpen) {
        SearchDialog(viewModel = viewModel, onDismiss = { viewModel.closeSearchOverlay() })
    }
}

/**
 * The toolbar's "Search" overlay - always global (every file the caller owns, not scoped to the
 * current folder, matching the server route's own scope), debounced ~400ms after the last
 * keystroke (see [AppViewModel.updateSearchQuery]) so a network call doesn't fire on every
 * keystroke. Clicking a result navigates straight to its containing folder and closes the overlay.
 */
@Composable
private fun SearchDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search") },
        text = {
            Column(Modifier.fillMaxWidth().height(360.dp)) {
                OutlinedTextField(
                    value = viewModel.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    label = { Text("File name or content") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = { if (viewModel.searchInFlight) CircularProgressIndicator(modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                when {
                    viewModel.searchUnavailable -> Text("Search isn't available on this server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    viewModel.searchQuery.isBlank() -> Text("Type to search every file you own.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    viewModel.searchResults.isEmpty() && !viewModel.searchInFlight -> Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        LazyColumn(Modifier.weight(1f)) {
                            items(viewModel.searchResults, key = { it.storedFileId() }) { result ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.openSearchResult(result) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(result.fileName())
                                        Text(
                                            result.folderId()?.let { "In a folder" } ?: "In Home",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * The row context menu's "Set color" action: a small dialog of every [FolderColorOption] swatch,
 * clicking one immediately applies it (via [onSelect]) and closes the dialog - no separate
 * "confirm" step, since picking a color is a single, easily-undoable action (just pick another
 * color), unlike the destructive/multi-field dialogs elsewhere in this screen.
 */
@Composable
private fun FolderColorPickerDialog(currentColor: FolderColorOption, onSelect: (FolderColorOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set folder color") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in FolderColorOption.entries) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(option.color, CircleShape)
                            .let {
                                if (option == currentColor) {
                                    it.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                } else it
                            }
                            .clickable { onSelect(option) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (option == currentColor) {
                            Icon(Icons.Filled.Check, contentDescription = option.name, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** Which list a [SortMenuButton] second-level menu is currently choosing a [SortOption] for - `null` means the top-level "Sort Files"/"Sort Folders" picker is showing. */
private enum class SortTarget { FILES, FOLDERS }

/**
 * The toolbar's single "Sort" dropdown (consolidated 2026-09-05 from two separate always-visible
 * "Sort folders"/"Sort files" buttons into one, per Lino's own request) - one button opens a
 * two-level menu: the top level offers "Sort Files"/"Sort Folders", and choosing either drills
 * into the same [SortOption] list [de.lino.cloud.platform.desktop.viewmodel.AppViewModel.fileSortOption]/
 * [folderSortOption] already exposed, with a "Back" entry returning to the top level. Compose has
 * no built-in nested-submenu popup, so this is implemented as one [DropdownMenu] whose content
 * switches on [activeTarget] rather than two independently-positioned popups - the menu never
 * closes between levels, only its content changes, matching how a native OS submenu feels. A
 * small spinner shows next to "Sort Folders" while
 * [de.lino.cloud.platform.desktop.viewmodel.AppViewModel.computingFolderSizes] is walking a folder
 * tree for [SortOption.SIZE], the same signal the old two-button layout showed on its folder
 * button only.
 */
@Composable
private fun SortMenuButton(viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var activeTarget by remember { mutableStateOf<SortTarget?>(null) }

    fun closeMenu() {
        expanded = false
        activeTarget = null
    }

    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = !viewModel.busy) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sort")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = ::closeMenu) {
            when (activeTarget) {
                null -> {
                    DropdownMenuItem(
                        text = { Text("Sort Files") },
                        trailingIcon = { Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { activeTarget = SortTarget.FILES },
                    )
                    DropdownMenuItem(
                        text = { Text("Sort Folders") },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (viewModel.computingFolderSizes) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = { activeTarget = SortTarget.FOLDERS },
                    )
                }
                SortTarget.FILES -> {
                    DropdownMenuItem(
                        text = { Text("Back") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { activeTarget = null },
                    )
                    HorizontalDivider()
                    for (option in SortOption.entries) {
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = if (option == viewModel.fileSortOption) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = { viewModel.changeFileSortOption(option); closeMenu() },
                        )
                    }
                }
                SortTarget.FOLDERS -> {
                    DropdownMenuItem(
                        text = { Text("Back") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { activeTarget = null },
                    )
                    HorizontalDivider()
                    for (option in SortOption.entries) {
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = if (option == viewModel.folderSortOption) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = { viewModel.changeFolderSortOption(option); closeMenu() },
                        )
                    }
                }
            }
        }
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

/**
 * The context menu's "Share" action (file/folder sharing) - lets the caller grant another
 * account (by email) read-only access to [entry], see who it's
 * currently shared with, and revoke any of those grants. Deliberately a plain email text field, not
 * a live-searching account picker: the server has no endpoint to search/list other accounts' emails
 * (and building one would be a real account-enumeration risk this codebase avoids elsewhere, e.g.
 * `AuthService#login`'s/`#requestPasswordReset`'s own "don't leak" error handling) - but the typed
 * address itself *is* live-checked for existence (added 2026-09-02, via
 * [CloudDriverClient.checkCloudUserExists], debounced [EMAIL_CHECK_DEBOUNCE_MILLIS] after the last
 * keystroke) and the "Share" button disabled while it's known not to exist, showing "Cloud user
 * account '&lt;email&gt;' does not exist" - checking existence for an already-authenticated caller
 * sharing their own file isn't the same login-enumeration risk an anonymous account-search endpoint
 * would be, the same reasoning `AuthService#requestEmailChange`'s own `EmailAlreadyRegisteredException`
 * already relies on. **Fixed a real bug this same pass:** before this existed, a mistyped/nonexistent
 * grantee address failed server-side with a misleading generic 404 (`DefaultRestFactory#folderFailureOrPropagate`
 * collapsed it into "No StoredFile/Folder with id ...", implying the *file* was missing rather than
 * the *address* being wrong - see `GranteeAccountNotFoundException`'s own Javadoc) with no grant ever
 * persisted, so the intended recipient never saw the file and the sharer had no clear signal why.
 *
 * Fully self-contained (own local loading/error/list state, own [rememberCoroutineScope] for the
 * share/revoke actions), the same way [MoveToFolderDialog]'s own folder browsing calls
 * [AppViewModel.client] directly rather than going through [AppViewModel.run] - this dialog stays
 * open across multiple share/revoke actions (unlike [MoveToFolderDialog], which closes itself after
 * one move), so tying every action to the screen-wide `busy` guard would disable the rest of the
 * app for no reason while this modal is simply open.
 */
@Composable
private fun ShareDialog(viewModel: AppViewModel, entry: Entry, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var currentShares by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var emailInput by remember { mutableStateOf("") }
    var actionInFlight by remember { mutableStateOf(false) }

    // Permission granted alongside a new share ("VIEW"/"EDIT") and its optional expiry - "EDIT"
    // only ever grants the server-side ability to replace the file's own content directly (see
    // CloudDriverClient.shareFile's own Javadoc); this app has no in-app "edit a shared file"
    // action of its own, so EDIT is offered purely as a grant option for a caller with their own
    // tooling, deliberately not paired with any editing UI here.
    var permissionLevel by remember { mutableStateOf("VIEW") }
    var shareExpiryPreset by remember { mutableStateOf(ExpiryPreset.NEVER) }

    // Public-link state - file-only (the server has no folder public-link route), so this whole
    // section is skipped entirely for a folder entry.
    var publicLinks by remember { mutableStateOf<List<de.lino.cloud.platform.rest.api.dto.Dtos.PublicFileLinkSummaryResponse>>(emptyList()) }
    var publicLinksLoading by remember { mutableStateOf(entry is Entry.FileEntry) }
    var publicLinkExpiryPreset by remember { mutableStateOf(ExpiryPreset.NEVER) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    suspend fun reloadPublicLinks() {
        if (entry !is Entry.FileEntry) return
        publicLinksLoading = true
        try {
            publicLinks = viewModel.client.listPublicFileLinks(entry.id)
        } catch (e: Exception) {
            // Best-effort - a failed fetch just leaves the section showing "none yet" rather than
            // failing the whole dialog, since the sharing half above is the primary function here.
        } finally {
            publicLinksLoading = false
        }
    }

    LaunchedEffect(entry.id) { reloadPublicLinks() }

    fun createPublicLink() {
        if (entry !is Entry.FileEntry || actionInFlight) return
        actionInFlight = true
        scope.launch {
            try {
                val expiresAt = publicLinkExpiryPreset.epochMillisFrom(System.currentTimeMillis())
                viewModel.client.createPublicFileLink(entry.id, expiresAt)
                reloadPublicLinks()
            } catch (e: Exception) {
                dialogError = e.message ?: "Failed to create public link"
            } finally {
                actionInFlight = false
            }
        }
    }

    fun revokePublicLink(token: String) {
        if (entry !is Entry.FileEntry || actionInFlight) return
        actionInFlight = true
        scope.launch {
            try {
                viewModel.client.revokePublicFileLink(entry.id, token)
                reloadPublicLinks()
            } catch (e: Exception) {
                dialogError = e.message ?: "Failed to revoke public link"
            } finally {
                actionInFlight = false
            }
        }
    }

    fun publicUrlFor(token: String): String = viewModel.client.apiClient.apiBaseUrl().resolve("/public/files/$token").toString()

    // Live existence check for the typed grantee address, debounced so it doesn't fire a network
    // call on every keystroke - null means "unknown/still checking", true/false once resolved.
    // Keyed on the trimmed email so pure whitespace edits don't restart the debounce.
    var checkedEmail by remember { mutableStateOf("") }
    var emailExists by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(emailInput.trim()) {
        val email = emailInput.trim()
        if (email.isEmpty()) {
            checkedEmail = ""
            emailExists = null
            return@LaunchedEffect
        }
        delay(EMAIL_CHECK_DEBOUNCE_MILLIS)
        try {
            emailExists = viewModel.client.checkCloudUserExists(email)
            checkedEmail = email
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Couldn't determine (network hiccup, rate limit, etc.) - don't block sharing on a
            // failed check, the server's own validation is still the real guard.
            emailExists = null
            checkedEmail = ""
        }
    }

    suspend fun reload() {
        loading = true
        dialogError = null
        try {
            currentShares = when (entry) {
                is Entry.FileEntry -> viewModel.client.listFileShares(entry.id)
                is Entry.FolderEntry -> viewModel.client.listFolderShares(entry.id)
            }
        } catch (e: Exception) {
            dialogError = e.message ?: "Failed to load shares"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(entry.id) { reload() }

    fun share() {
        val email = emailInput.trim()
        if (email.isEmpty() || actionInFlight) return
        actionInFlight = true
        scope.launch {
            try {
                val expiresAt = shareExpiryPreset.epochMillisFrom(System.currentTimeMillis())
                when (entry) {
                    is Entry.FileEntry -> viewModel.client.shareFile(entry.id, email, permissionLevel, expiresAt)
                    is Entry.FolderEntry -> viewModel.client.shareFolder(entry.id, email, permissionLevel, expiresAt)
                }
                emailInput = ""
                checkedEmail = ""
                emailExists = null
                reload()
            } catch (e: Exception) {
                dialogError = e.message ?: "Failed to share"
            } finally {
                actionInFlight = false
            }
        }
    }

    fun revoke(email: String) {
        if (actionInFlight) return
        actionInFlight = true
        scope.launch {
            try {
                when (entry) {
                    is Entry.FileEntry -> viewModel.client.revokeFileShare(entry.id, email)
                    is Entry.FolderEntry -> viewModel.client.revokeFolderShare(entry.id, email)
                }
                reload()
            } catch (e: Exception) {
                dialogError = e.message ?: "Failed to revoke share"
            } finally {
                actionInFlight = false
            }
        }
    }

    val trimmedEmail = emailInput.trim()
    // Only treat the address as confirmed-missing once the debounced check has actually resolved
    // for this exact (trimmed) value - checkedEmail lags emailInput while a check is still in
    // flight/debouncing, so a stale `emailExists == false` from a previous, different address is
    // never shown against the current one.
    val emailKnownMissing = trimmedEmail.isNotEmpty() && checkedEmail == trimmedEmail && emailExists == false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share \"${entry.name}\"") },
        text = {
            Column(Modifier.fillMaxWidth().height(520.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Account email") },
                        singleLine = true,
                        isError = emailKnownMissing,
                        enabled = !actionInFlight,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = ::share, enabled = !actionInFlight && trimmedEmail.isNotEmpty() && !emailKnownMissing) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = "Share")
                    }
                }
                if (emailKnownMissing) {
                    Text(
                        "Cloud user account '$trimmedEmail' does not exist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionDropdown(selected = permissionLevel, onSelect = { permissionLevel = it }, enabled = !actionInFlight)
                    ExpiryDropdown(selected = shareExpiryPreset, onSelect = { shareExpiryPreset = it }, enabled = !actionInFlight)
                }

                Spacer(Modifier.height(16.dp))
                Text("Currently shared with", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    dialogError != null -> Text(dialogError!!, color = MaterialTheme.colorScheme.error)
                    currentShares.isEmpty() -> Text("Not shared with anyone yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Column(Modifier.heightIn(max = 140.dp)) {
                            LazyColumn {
                                items(currentShares, key = { it }) { email ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(email, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { revoke(email) }, enabled = !actionInFlight) {
                                            Icon(Icons.Filled.PersonRemove, contentDescription = "Revoke", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Public links are a file-only capability - the server has no equivalent route
                // for a folder, so this whole section simply doesn't render for one.
                if (entry is Entry.FileEntry) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Public link", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        ExpiryDropdown(selected = publicLinkExpiryPreset, onSelect = { publicLinkExpiryPreset = it }, enabled = !actionInFlight)
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = ::createPublicLink, enabled = !actionInFlight) {
                            Text("Create link")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        publicLinksLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        publicLinks.isEmpty() -> Text("No public links yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            Column(Modifier.heightIn(max = 140.dp)) {
                                LazyColumn {
                                    items(publicLinks, key = { it.token() }) { link ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(publicUrlFor(link.token()), style = MaterialTheme.typography.bodySmall)
                                                Text(
                                                    if (link.expiresAtEpochMillis() == null) "Never expires" else "Expires ${formatEpochMilli(link.expiresAtEpochMillis()!!)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(publicUrlFor(link.token()))) }) {
                                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy link")
                                            }
                                            IconButton(onClick = { revokePublicLink(link.token()) }, enabled = !actionInFlight) {
                                                Icon(Icons.Filled.PersonRemove, contentDescription = "Revoke link", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/**
 * The context menu's "Version history" action - lists every previously-captured version of
 * [entry]'s content (timestamp, size), each with "Download this version"/"Restore". Stays open
 * across multiple actions, the same self-contained shape [ShareDialog] uses, rather than closing
 * itself after one action. Treats a `503` (the versioning extension not running on this
 * deployment) as "no version history available" rather than an error, matching this app's
 * existing convention for every other optional-extension call.
 */
@Composable
private fun VersionHistoryDialog(viewModel: AppViewModel, entry: Entry.FileEntry, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<de.lino.cloud.platform.rest.api.dto.Dtos.FileVersionSummaryResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var actionInFlight by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        dialogError = null
        try {
            versions = viewModel.client.listFileVersions(entry.id)
        } catch (e: de.lino.cloud.platform.rest.api.ApiClient.ApiException) {
            if (e.statusCode() == 503) unavailable = true else dialogError = e.message ?: "Failed to load version history"
        } catch (e: Exception) {
            dialogError = e.message ?: "Failed to load version history"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(entry.id) { reload() }

    fun downloadVersion(versionNumber: Int) {
        val destination = chooseDirectory("Select download destination", DEFAULT_DOWNLOAD_DIRECTORY) ?: return
        if (actionInFlight) return
        actionInFlight = true
        scope.launch {
            try {
                viewModel.client.downloadFileVersion(entry.id, versionNumber, destination.resolve("v${versionNumber}_${entry.name}"))
            } catch (e: Exception) {
                dialogError = e.message ?: "Failed to download version"
            } finally {
                actionInFlight = false
            }
        }
    }

    fun restoreVersion(versionNumber: Int) {
        if (actionInFlight) return
        actionInFlight = true
        scope.launch {
            try {
                viewModel.client.restoreFileVersion(entry.id, versionNumber)
                onDismiss()
                viewModel.loadCurrentFolder()
            } catch (e: Exception) {
                dialogError = e.message ?: "Failed to restore version"
            } finally {
                actionInFlight = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Version history: \"${entry.name}\"") },
        text = {
            Column(Modifier.fillMaxWidth().height(320.dp)) {
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    unavailable -> Text("Version history isn't available on this server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    dialogError != null -> Text(dialogError!!, color = MaterialTheme.colorScheme.error)
                    versions.isEmpty() -> Text("No earlier versions yet - this file has never been overwritten.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        LazyColumn(Modifier.weight(1f)) {
                            items(versions, key = { it.versionNumber() }) { version ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Version ${version.versionNumber()}", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${formatEpochMilli(version.capturedAtEpochMillis())} - ${formatBytes(version.sizeBytes())}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { downloadVersion(version.versionNumber()) }, enabled = !actionInFlight) {
                                        Icon(Icons.Filled.Download, contentDescription = "Download this version")
                                    }
                                    IconButton(onClick = { restoreVersion(version.versionNumber()) }, enabled = !actionInFlight) {
                                        Icon(Icons.Filled.Restore, contentDescription = "Restore this version")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/**
 * The context menu's "Activity" action - a scoped view of the audit trail for one file/folder,
 * newest first, with "Load more" pagination mirroring the folder view's own pattern. Reuses the
 * same list-rendering shape [ActivityFeedScreen] (see `Screen.Activity`) uses for the global feed,
 * just backed by [CloudDriverClient.listFileActivity]/[listFolderActivity] instead of [listActivity].
 */
@Composable
private fun EntryActivityDialog(viewModel: AppViewModel, entry: Entry, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<de.lino.cloud.platform.rest.api.dto.Dtos.ActivityEntryResponse>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    suspend fun page(nextCursor: String?): de.lino.cloud.platform.rest.api.dto.Dtos.Page<de.lino.cloud.platform.rest.api.dto.Dtos.ActivityEntryResponse> =
        when (entry) {
            is Entry.FileEntry -> viewModel.client.listFileActivity(entry.id, nextCursor, 50)
            is Entry.FolderEntry -> viewModel.client.listFolderActivity(entry.id, nextCursor, 50)
        }

    LaunchedEffect(entry.id) {
        loading = true
        dialogError = null
        try {
            val firstPage = page(null)
            entries = firstPage.items()
            cursor = firstPage.nextCursor()
        } catch (e: Exception) {
            dialogError = e.message ?: "Failed to load activity"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activity: \"${entry.name}\"") },
        text = {
            Column(Modifier.fillMaxWidth().height(320.dp)) {
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    dialogError != null -> Text(dialogError!!, color = MaterialTheme.colorScheme.error)
                    entries.isEmpty() -> Text("No recorded activity yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        LazyColumn(Modifier.weight(1f)) {
                            items(entries, key = { "${it.timestampEpochMillis()}-${it.action()}" }) { activity ->
                                ActivityEntryRow(activity)
                            }
                            if (cursor != null) {
                                item(key = "__load_more_activity__") {
                                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                        if (loadingMore) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                        } else {
                                            TextButton(onClick = {
                                                val c = cursor ?: return@TextButton
                                                loadingMore = true
                                                scope.launch {
                                                    try {
                                                        val nextPage = page(c)
                                                        entries = entries + nextPage.items()
                                                        cursor = nextPage.nextCursor()
                                                    } catch (e: Exception) {
                                                        dialogError = e.message ?: "Failed to load more activity"
                                                    } finally {
                                                        loadingMore = false
                                                    }
                                                }
                                            }) { Text("Load more") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
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
 * "Options" - the toolbar's bulk actions on the current selection (Download/Duplicate/Delete
 * selected), bundled behind one dropdown button the same way [UploadMenuButton] bundles its own
 * two upload actions, rather than three always-visible buttons. Fixes a real layout bug: with
 * three separate buttons inline, this toolbar's total width could exceed the available `Row`
 * width once a selection was made, and "Delete selected" - the last of the three - was the one
 * that ended up pushed off-screen, with no scroll affordance to reach it. One fixed-width trigger
 * button avoids that regardless of how many bulk actions this menu ever grows to.
 */
@Composable
private fun SelectionOptionsMenuButton(viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = !viewModel.busy) {
            Text("Options")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Download selected") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                onClick = {
                    expanded = false
                    chooseDirectory("Select download destination", DEFAULT_DOWNLOAD_DIRECTORY)?.let { viewModel.downloadSelected(it) }
                },
            )
            DropdownMenuItem(
                text = { Text("Duplicate selected") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    expanded = false
                    viewModel.duplicateSelected()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete selected") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    viewModel.deleteSelected()
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
 * Right-clicking anywhere on the row opens a context menu (Download/Duplicate/Move to.../Delete)
 * at the cursor - detected via a second, independent `pointerInput` block reading raw pointer events
 * rather than [androidx.compose.foundation.ContextMenuArea] (which replaces the platform's own
 * text-selection context menu, not a good fit for a whole row) - it only *observes* the press
 * (never calls `change.consume()`), so left-click/drag handling above is untouched.
 *
 * A single click still only opens a folder (via [onOpen], unchanged - a file's single click is a
 * no-op, same as before this row could preview anything). A **double**-click (two clicks within
 * [DOUBLE_CLICK_THRESHOLD_MILLIS]) on a file row calls [onPreviewRequest] instead - detected with
 * a plain click-timestamp check inside the same `.clickable(...)` this row already had, rather
 * than a second `detectTapGestures` `pointerInput` block, to avoid two independent tap-gesture
 * recognizers racing over the same pointer stream (this row already layers a long-press-drag
 * detector and a raw-event right-click detector alongside `.clickable` - both of those coexist
 * safely with it specifically because neither consumes a plain, quick tap). Despite the callback's
 * name, [onPreviewRequest]'s caller (`FileBrowserScreen`) branches on the file's content type: a
 * ZIP archive is extracted into the current folder instead of opening `FilePreviewDialog` - see
 * `AppViewModel.extractArchive`.
 *
 * The row's own icon is [iconFor]'s generic per-content-type glyph, except for an image file under
 * [de.lino.cloud.platform.desktop.utils.isThumbnailable]'s size ceiling - there,
 * [rememberThumbnail] resolves (downloading+decoding in the background, on first use) a real
 * thumbnail of the image itself instead.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EntryRow(
    entry: Entry,
    client: CloudDriverClient,
    selected: Boolean,
    enabled: Boolean,
    isBeingDragged: Boolean,
    isDropTarget: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onPreviewRequest: () -> Unit,
    onRegisterBounds: (Rect) -> Unit,
    onUnregisterBounds: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDownloadRequest: () -> Unit,
    onMoveRequest: () -> Unit,
    onDuplicateRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    onShareRequest: () -> Unit,
    onSetColorRequest: () -> Unit,
    onVersionHistoryRequest: () -> Unit,
    onActivityRequest: () -> Unit,
) {
    var rowBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var lastClickTimeMillis by remember { mutableStateOf(0L) }

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
                .clickable(enabled = enabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTimeMillis <= DOUBLE_CLICK_THRESHOLD_MILLIS) {
                        lastClickTimeMillis = 0L
                        // A file still being scanned or flagged by content-scanning can't be
                        // previewed - its content isn't servable yet (see CloudUserService's
                        // scan-blocked check server-side) - so a double-click on one is a no-op
                        // here rather than opening a dialog that would just fail to load.
                        if (entry !is Entry.FileEntry || entry.summary.scanStatus() == "CLEAN") onPreviewRequest()
                    } else {
                        lastClickTimeMillis = now
                        onOpen()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, enabled = enabled, modifier = Modifier.width(40.dp))
            val scanStatus = (entry as? Entry.FileEntry)?.summary?.scanStatus()
            Box(contentAlignment = Alignment.BottomEnd) {
                val thumbnail = rememberThumbnail(entry, client)
                if (thumbnail != null) {
                    Image(
                        thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    Icon(
                        iconFor(entry),
                        contentDescription = null,
                        // A distinct color per folder/file category (see EntryIcons.kt#colorFor) -
                        // the real macOS iCloud app renders every service as its own colorful icon
                        // rather than one repeated monochrome tint.
                        tint = colorFor(entry),
                        modifier = Modifier.size(20.dp),
                    )
                }
                // A small badge overlaid on the icon when a content scan hasn't cleared this file
                // yet - a spinner while still pending, a warning triangle once flagged. Nothing is
                // shown for "CLEAN" (the overwhelming majority of files, and every file on a
                // deployment with no content-scanning extension running at all).
                when (scanStatus) {
                    "PENDING" -> CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                    "FLAGGED" -> Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Flagged by content scanning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp),
                    )
                    else -> {}
                }
            }
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
            // Disabled while a content scan hasn't cleared this file yet - mirrors the server's
            // own refusal to serve non-CLEAN content (409 while pending, 403 once flagged),
            // surfaced proactively here instead of only after a failed click.
            val downloadBlocked = scanStatus != null && scanStatus != "CLEAN"
            val downloadBlockedReason = when (scanStatus) {
                "PENDING" -> "Download (still being scanned)"
                "FLAGGED" -> "Download (flagged by content scanning)"
                else -> "Download"
            }
            DropdownMenuItem(
                text = { Text(downloadBlockedReason) },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                enabled = enabled && !downloadBlocked,
                onClick = { contextMenuExpanded = false; onDownloadRequest() },
            )
            DropdownMenuItem(
                text = { Text("Duplicate") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                enabled = enabled,
                onClick = { contextMenuExpanded = false; onDuplicateRequest() },
            )
            DropdownMenuItem(
                text = { Text("Move to...") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                enabled = enabled,
                onClick = { contextMenuExpanded = false; onMoveRequest() },
            )
            if (entry is Entry.FolderEntry) {
                DropdownMenuItem(
                    text = { Text("Set color") },
                    leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    enabled = enabled,
                    onClick = { contextMenuExpanded = false; onSetColorRequest() },
                )
            }
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                enabled = enabled,
                onClick = { contextMenuExpanded = false; onShareRequest() },
            )
            if (entry is Entry.FileEntry) {
                DropdownMenuItem(
                    text = { Text("Version history") },
                    leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                    enabled = enabled,
                    onClick = { contextMenuExpanded = false; onVersionHistoryRequest() },
                )
            }
            DropdownMenuItem(
                text = { Text("Activity") },
                leadingIcon = { Icon(Icons.Filled.Timeline, contentDescription = null) },
                enabled = enabled,
                onClick = { contextMenuExpanded = false; onActivityRequest() },
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
