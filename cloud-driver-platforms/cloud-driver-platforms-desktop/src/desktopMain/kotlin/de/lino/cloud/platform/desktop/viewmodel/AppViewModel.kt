package de.lino.cloud.platform.desktop.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.desktop.model.AccountStats
import de.lino.cloud.platform.desktop.model.Entry
import de.lino.cloud.platform.desktop.model.Screen
import de.lino.cloud.platform.desktop.model.computeAccountStats
import de.lino.cloud.platform.desktop.theme.ThemeMode
import de.lino.cloud.platform.desktop.utils.AppSettingsStore
import de.lino.cloud.platform.desktop.utils.decodeJwtSubject
import de.lino.cloud.platform.desktop.utils.downloadFileStreaming
import de.lino.cloud.platform.desktop.utils.extractZip
import de.lino.cloud.platform.desktop.utils.mapConcurrently
import de.lino.cloud.platform.desktop.utils.uninstallApp
import de.lino.cloud.platform.desktop.utils.zipDirectory
import de.lino.cloud.platform.rest.api.ApiClient
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which kind of batch transfer [AppViewModel.transferProgress] is currently reporting on.
 * [EXTRACT] covers both halves of [AppViewModel.extractArchive] (downloading the archive, then
 * re-uploading its extracted contents) under one label - from the user's perspective "unarchiving"
 * is a single action, even though it's two sequential [AppViewModel.runTransfer] batches under
 * the hood (see that function's own Javadoc for why a genuinely single batch isn't possible here).
 */
enum class TransferKind { UPLOAD, DOWNLOAD, EXTRACT }

/**
 * A snapshot of an in-flight upload/download batch - [AppViewModel.transferProgress] is `null`
 * whenever no transfer is running. [transferredBytes]/[totalBytes] are summed across every item in
 * the batch (not just the item currently in flight), since [AppViewModel]'s transfers run several
 * files concurrently at once (see [de.lino.cloud.platform.desktop.utils.mapConcurrently]) rather
 * than one at a time - a per-item-only progress bar would jump backwards every time one file
 * finished and the next one started.
 */
data class TransferProgress(
    val kind: TransferKind,
    val totalFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
) {
    /** `1f` if [totalBytes] is `0` (nothing to divide by, e.g. every selected file happened to be empty) - a full bar reads better than a division-by-zero `NaN` for a batch with nothing left to transfer. */
    val fraction: Float get() = if (this.totalBytes <= 0L) 1f else (this.transferredBytes.toFloat() / this.totalBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * All mutable state and actions for the app, in one place - navigation ([screen]), the current
 * session ([client]), and the file browser's current listing/selection. Not an AndroidX
 * `ViewModel` (unavailable outside Android/Jetpack) - a plain class holding Compose
 * `mutableStateOf`/`mutableStateListOf` properties, constructed once via `remember { }` in
 * [de.lino.cloud.platform.desktop.main] and read directly by every screen composable, the
 * standard pattern for a Compose Desktop app with no navigation library.
 *
 * Every action ([login], [uploadFiles], [deleteSelected], ...) is fire-and-forget from the
 * caller's perspective: it launches a coroutine on [scope] and returns immediately, updating
 * [busy]/[errorMessage] as it goes - a screen composable never awaits an action's result itself.
 */
class AppViewModel(private val scope: CoroutineScope, initialServerUrl: String, initialThemeMode: ThemeMode) {

    /** The active session's HTTP client, against the hardcoded server address(es) passed at construction (see `Main.kt`'s `DEFAULT_SERVER_URL`). */
    val client: CloudDriverClient = CloudDriverClient(initialServerUrl, initialServerUrl)

    /** The active light/dark theme - loaded once at startup via `AppSettingsStore.loadThemeMode()`, toggled via [toggleTheme]. */
    var themeMode: ThemeMode by mutableStateOf(initialThemeMode)
        private set

    /** Flips [themeMode] and persists the new choice via [AppSettingsStore] so it survives a restart. */
    fun toggleTheme() {
        val newMode = if (this.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        this.themeMode = newMode
        this.scope.launch { AppSettingsStore.saveThemeMode(newMode) }
    }

    var screen: Screen by mutableStateOf(Screen.Login)
        private set

    /** `true` while an action launched by this view model is in flight - screens disable their action buttons while this is `true`. */
    var busy: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    // --- account state ---------------------------------------------------

    /** The signed-in account's email address - captured from whichever auth action last succeeded (there is no `GET /me`-style endpoint to fetch it back from). */
    var currentUserEmail: String? by mutableStateOf(null)
        private set

    /** The signed-in account's id - decoded from the JWT's own `sub` claim (see [decodeJwtSubject]), not a separate API call. */
    var currentUserId: String? by mutableStateOf(null)
        private set

    /**
     * The signed-in account's creation time (epoch millis) - fetched via [CloudDriverClient.getCloudUser]
     * right after authenticating, since a `CloudUser`'s `timeStamp` is set once, at account-confirmation
     * time (see `CloudUserResponse`'s own Javadoc). `null` until that fetch completes, and left `null`
     * (rather than failing the whole sign-in) if it errors - this is Dashboard-only display information,
     * not something the rest of the app depends on.
     */
    var currentUserCreatedAtEpochMillis: Long? by mutableStateOf(null)
        private set

    /** The signed-in account's current upload usage/quota (bytes) - fetched alongside [currentUserCreatedAtEpochMillis], same `null`-on-failure/Dashboard-only reasoning. */
    var currentUserUploadedBytes: Long? by mutableStateOf(null)
        private set
    var currentUserMaxBytesToUpload: Long? by mutableStateOf(null)
        private set

    var dashboardStats: AccountStats? by mutableStateOf(null)
        private set

    private suspend fun onAuthenticated(email: String, jwt: String) {
        this.currentUserEmail = email
        this.currentUserId = decodeJwtSubject(jwt)
        this.refreshAccountInfo()
    }

    /**
     * Re-fetches [currentUserCreatedAtEpochMillis]/[currentUserUploadedBytes]/[currentUserMaxBytesToUpload]
     * from the server via [CloudDriverClient.getCloudUser] - called once from [onAuthenticated], and
     * again from [loadDashboardStats] every time the Dashboard is shown, since an operator can change
     * an account's upload quota out-of-band (e.g. the terminal's `cu update <email> <bytes>` command)
     * while this client is already signed in; without a re-fetch here the Dashboard's "Storage" row
     * would keep showing whatever quota was in effect at login time until the next full sign-in.
     */
    private suspend fun refreshAccountInfo() {
        val userId = this.currentUserId ?: return
        try {
            val cloudUser = this.client.getCloudUser(userId)
            this.currentUserCreatedAtEpochMillis = cloudUser?.timeStamp()
            this.currentUserUploadedBytes = cloudUser?.currentUploadedBytes()
            this.currentUserMaxBytesToUpload = cloudUser?.maxBytesToUpload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            this.currentUserCreatedAtEpochMillis = null
            this.currentUserUploadedBytes = null
            this.currentUserMaxBytesToUpload = null
        }
    }

    // --- file browser state ---------------------------------------------

    /** The path from the root to [currentFolderId], root-first; empty means we're at the root. */
    val breadcrumbs = mutableStateListOf<FolderResponse>()

    var currentFolderId: String? by mutableStateOf(null)
        private set

    val folders = mutableStateListOf<FolderResponse>()
    val files = mutableStateListOf<de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse>()
    val selected = mutableStateListOf<Entry>()

    /** The currently in-flight upload/download batch, if any - `null` otherwise. Rendered as a bottom progress bar (see `App.kt`/`Sidebar.kt`). Set/cleared exclusively by [runTransfer]. */
    var transferProgress: TransferProgress? by mutableStateOf(null)
        private set

    /**
     * Drives [uploadFiles]/[uploadFolderAsZip]/[downloadEntries] uniformly: runs [transfer] over
     * every item in [items] concurrently (capped - see [mapConcurrently]), publishing an
     * aggregated [transferProgress] (summed across every item, not just the one currently in
     * flight - see [TransferProgress]'s own Javadoc for why) as each item's own progress callback
     * fires, and clears [transferProgress] back to `null` once the whole batch finishes (success or
     * failure alike - a `finally` block, so a failed transfer doesn't leave a stale progress bar
     * on screen forever). A no-op (no bar shown at all) if [items] is empty.
     *
     * [sizeOf] must be cheap/already-known (a local [Files.size] read, or a size already carried on
     * a listing entry) - this does not fetch sizes itself. The per-item byte callback each call to
     * [transfer] receives may fire from a background HTTP I/O thread, not this coroutine's own
     * dispatcher (see [de.lino.cloud.platform.rest.api.ApiClient]'s own Javadoc on this) - safe
     * here since every write below only replaces [transferProgress] wholesale (no in-place
     * mutation), and Compose's snapshot state system tolerates writes from any thread.
     */
    private suspend fun <T> runTransfer(
        kind: TransferKind,
        items: List<T>,
        sizeOf: (T) -> Long,
        transfer: suspend (T, onBytesTransferred: (Long) -> Unit) -> Unit,
    ) {
        if (items.isEmpty()) return
        val totalBytes = items.sumOf(sizeOf)
        val transferredByItem = ConcurrentHashMap<Int, Long>()
        val completedFiles = AtomicInteger(0)

        fun publish() {
            this.transferProgress = TransferProgress(
                kind = kind,
                totalFiles = items.size,
                completedFiles = completedFiles.get(),
                totalBytes = totalBytes,
                transferredBytes = transferredByItem.values.sum(),
            )
        }

        publish()
        try {
            items.withIndex().toList().mapConcurrently { (index, item) ->
                transfer(item) { bytesTransferred ->
                    transferredByItem[index] = bytesTransferred
                    publish()
                }
                completedFiles.incrementAndGet()
                publish()
            }
        } finally {
            this.transferProgress = null
        }
    }

    /**
     * Runs [action] on [scope], toggling [busy] and surfacing any failure as [errorMessage].
     * A no-op if an action launched by a previous [run] call is still in flight ([busy] already
     * `true`) - a synchronous check-then-set on the same (UI) thread this is always called from,
     * so it's race-free without needing an atomic/lock, and guards against a rapid double-click
     * firing the same action twice before recomposition has a chance to disable the button.
     */
    private fun run(action: suspend () -> Unit) {
        if (this.busy) return
        this.busy = true
        this.errorMessage = null
        this.scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                // Real cancellation (e.g. the window closing mid-action) - not a request
                // failure, so it must propagate rather than be swallowed as an error message;
                // swallowing it here would break this coroutine's cooperative cancellation.
                throw e
            } catch (e: ApiClient.ApiException) {
                this@AppViewModel.errorMessage = e.message ?: "Request failed"
            } catch (e: Exception) {
                this@AppViewModel.errorMessage = e.message ?: e.toString()
            } finally {
                this@AppViewModel.busy = false
            }
        }
    }

    // --- auth --------------------------------------------------------

    fun login(email: String, password: String) = run {
        val jwt = this.client.login(email, password)
        this.onAuthenticated(email, jwt)
        this.screen = Screen.Browser
    }

    fun startRegister() {
        this.screen = Screen.Register
        this.errorMessage = null
    }

    fun register(email: String, password: String) = run {
        this.client.register(email, password)
        this.screen = Screen.RegisterConfirm(email)
    }

    fun confirmRegister(email: String, code: String) = run {
        val jwt = this.client.confirmRegistration(email, code)
        this.onAuthenticated(email, jwt)
        this.screen = Screen.Browser
    }

    fun startResetPassword() {
        this.screen = Screen.ResetPasswordRequest
        this.errorMessage = null
    }

    fun requestPasswordReset(email: String) = run {
        this.client.requestPasswordReset(email)
        this.screen = Screen.ResetPasswordConfirm(email)
    }

    fun confirmPasswordReset(email: String, code: String, newPassword: String) = run {
        val jwt = this.client.confirmPasswordReset(email, code, newPassword)
        this.onAuthenticated(email, jwt)
        this.screen = Screen.Browser
    }

    fun backToLogin() {
        this.screen = Screen.Login
        this.errorMessage = null
    }

    /** Closes the HTTP client and terminates the whole process - the sidebar's "Quit", distinct from [logout] (which only ends the session and returns to the login screen). */
    fun quit() {
        this.client.close()
        kotlin.system.exitProcess(0)
    }

    /**
     * Deletes the installed app and this app's local settings (see [uninstallApp] for the exact,
     * per-OS locations it mirrors from `build-app.sh`), then terminates the process - the
     * Dashboard's "Uninstall" action. The caller (`DashboardScreen`) is responsible for confirming
     * with the user first via a dialog; this function performs the deletion unconditionally, the
     * same way [deleteSelected]/[deleteFolderRecursively] assume their own caller already
     * confirmed. Dispatched on [Dispatchers.IO] rather than run directly on the calling (UI)
     * thread, matching this class's own "blocking local I/O off the UI dispatcher" convention -
     * even though the process exits immediately after, so the UI never actually gets a chance to
     * visibly block either way.
     */
    fun uninstall() {
        this.scope.launch(Dispatchers.IO) {
            this@AppViewModel.client.close()
            uninstallApp()
            kotlin.system.exitProcess(0)
        }
    }

    fun logout() {
        this.client.logout()
        this.currentUserEmail = null
        this.currentUserId = null
        this.currentUserCreatedAtEpochMillis = null
        this.currentUserUploadedBytes = null
        this.currentUserMaxBytesToUpload = null
        this.dashboardStats = null
        this.breadcrumbs.clear()
        this.currentFolderId = null
        this.folders.clear()
        this.files.clear()
        this.selected.clear()
        this.screen = Screen.Login
    }

    // --- dashboard -------------------------------------------------------

    fun showDashboard() {
        this.screen = Screen.Dashboard
        this.errorMessage = null
    }

    fun loadDashboardStats() = run {
        this.refreshAccountInfo()
        this.dashboardStats = this.client.computeAccountStats()
    }

    // --- file browser --------------------------------------------------

    /** Public, guarded entry point - use from a screen (button/`LaunchedEffect`). Goes through [run], so it's a no-op while another action is already in flight. */
    fun loadCurrentFolder() = run { this.refreshCurrentFolder() }

    /**
     * The actual reload, callable from *inside* another [run]-wrapped action (e.g. after
     * [uploadFiles]/[deleteSelected] finish mutating something) without tripping [run]'s own
     * `busy` guard - calling the public [loadCurrentFolder] from inside another action would
     * silently no-op, since [busy] is already `true` for the whole duration of the outer action,
     * leaving the list stale until the *next* unrelated reload. This was a real bug: every
     * upload/delete/create action already called [loadCurrentFolder] at its end, but since `busy`
     * was still `true` at that point, the reload never actually ran.
     */
    private suspend fun refreshCurrentFolder() {
        val folderId = this.currentFolderId
        this.selected.clear()
        val newFolders = this.client.listFolders(folderId)
        val newFiles = this.client.listFiles(folderId)
        this.folders.clear()
        this.folders.addAll(newFolders)
        this.files.clear()
        this.files.addAll(newFiles)
    }

    /**
     * Navigates into [folder], pushing it onto [breadcrumbs]. Guarded against re-entry: a no-op
     * while [busy] (a load is already in flight) or if [folder] is already [currentFolderId] -
     * fixes a real bug where a fast double/triple-click on the same folder row pushed a duplicate
     * breadcrumb entry for every extra click landing before the listing swapped out from under
     * it (the row stayed clickable for the whole round trip, unlike every toolbar button, which
     * already disables itself via `enabled = !viewModel.busy`), showing the same folder opened
     * more than once in the sidebar's breadcrumb trail.
     */
    fun openFolder(folder: FolderResponse) {
        if (this.busy || folder.folderId() == this.currentFolderId) return
        this.breadcrumbs.add(folder)
        this.currentFolderId = folder.folderId()
        this.loadCurrentFolder()
    }

    /** Navigates to the breadcrumb at [index], or the root if [index] is negative. Also switches back to the file browser if called while [Screen.Dashboard] is showing (the sidebar's "Home"/breadcrumb entries are reachable from there too). */
    fun navigateToBreadcrumb(index: Int) {
        this.screen = Screen.Browser
        this.errorMessage = null
        if (index < 0) {
            this.breadcrumbs.clear()
            this.currentFolderId = null
        } else {
            while (this.breadcrumbs.size > index + 1) this.breadcrumbs.removeAt(this.breadcrumbs.size - 1)
            this.currentFolderId = this.breadcrumbs[index].folderId()
        }
        this.loadCurrentFolder()
    }

    fun toggleSelected(entry: Entry) {
        if (!this.selected.remove(entry)) this.selected.add(entry)
    }

    fun createFolder(name: String) = run {
        this.client.createFolder(name, this.currentFolderId)
        this.refreshCurrentFolder()
    }

    /**
     * Uploads every chosen local file concurrently (capped - see [mapConcurrently]), reporting
     * aggregate progress via [transferProgress] - see [runTransfer]. `ApiClient`'s own batch
     * upload can't be reused here since it has no `folderId` parameter (always targets the root),
     * unlike [CloudDriverClient.uploadFile].
     */
    fun uploadFiles(paths: List<Path>) = run {
        val sizes = withContext(Dispatchers.IO) { paths.associateWith { Files.size(it) } }
        this.runTransfer(TransferKind.UPLOAD, paths, { sizes.getValue(it) }) { path, onBytesTransferred ->
            this.client.uploadFile(path, this.currentFolderId, onBytesTransferred)
        }
        this.refreshCurrentFolder()
    }

    /** Zips [directory] client-side and uploads the archive as a single file, per this app's "folder upload = zip" spec - a one-item [runTransfer] batch, so it still reports byte-level progress via [transferProgress]. */
    fun uploadFolderAsZip(directory: Path) = run {
        val zipPath = zipDirectory(directory)
        try {
            val zipName = "${directory.fileName}.zip"
            val zipSize = withContext(Dispatchers.IO) { Files.size(zipPath) }
            this.runTransfer(TransferKind.UPLOAD, listOf(zipPath), { zipSize }) { path, onBytesTransferred ->
                this.client.uploadFile(zipName, path, this.currentFolderId, onBytesTransferred)
            }
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(zipPath) }
        }
        this.refreshCurrentFolder()
    }

    /** One item of a mixed file/folder drop-upload batch - see [uploadDroppedPaths]. */
    private data class DroppedUploadItem(val uploadName: String, val sourcePath: Path, val sizeBytes: Long)

    /**
     * Uploads whatever was just dropped onto the file browser from the OS (Finder/Explorer) into
     * [currentFolderId] - the drag-*into*-the-app counterpart to [moveEntriesToFolder]'s
     * drag-*within*-the-app move (`FileBrowserScreen`'s `Modifier.dragAndDropTarget` calls this on
     * a drop). Each plain file in [paths] uploads as-is; each directory is zipped client-side first
     * via [zipDirectory] - the same "folder upload = zip" convention [uploadFolderAsZip] already
     * uses, since the server has no folder-tree upload endpoint - and uploaded as `<name>.zip`.
     * Every item, files and zipped folders alike, runs through one shared [runTransfer] batch, the
     * same aggregate-progress treatment [uploadFiles] gives a multi-file picker selection - so this
     * reports through the exact same [transferProgress] bar (rendered by `AuthenticatedShell`
     * regardless of what triggered it) as every other upload in this app, with no extra wiring.
     */
    fun uploadDroppedPaths(paths: List<Path>) = run {
        if (paths.isEmpty()) return@run
        val (directories, files) = withContext(Dispatchers.IO) { paths.partition { Files.isDirectory(it) } }
        val zippedDirectories = directories.map { directory -> directory to zipDirectory(directory) }
        try {
            val items = withContext(Dispatchers.IO) {
                files.map { DroppedUploadItem(it.fileName.toString(), it, Files.size(it)) } +
                    zippedDirectories.map { (directory, zipPath) -> DroppedUploadItem("${directory.fileName}.zip", zipPath, Files.size(zipPath)) }
            }
            this.runTransfer(TransferKind.UPLOAD, items, DroppedUploadItem::sizeBytes) { item, onBytesTransferred ->
                this.client.uploadFile(item.uploadName, item.sourcePath, this.currentFolderId, onBytesTransferred)
            }
        } finally {
            withContext(Dispatchers.IO) { zippedDirectories.forEach { (_, zipPath) -> Files.deleteIfExists(zipPath) } }
        }
        this.refreshCurrentFolder()
    }

    /**
     * The desktop app's "double-click a ZIP archive to unarchive it" behavior (see
     * `FileBrowserScreen`'s `EntryRow` double-click wiring, gated on
     * [de.lino.cloud.platform.desktop.utils.isZipArchive]), reporting real byte-level progress via
     * [transferProgress] the whole way through - as two sequential [runTransfer] batches, both
     * under [TransferKind.EXTRACT]:
     *
     * 1. Downloads [entry] to a throwaway temp file - a one-item batch, the same "one logical item,
     *    still real byte progress" shape [uploadFolderAsZip] already uses for its own single-file
     *    upload.
     * 2. Extracts it into a fresh temp directory via [extractZip] (fast, local, no network - no
     *    progress reporting needed, same as [zipDirectory]'s own write-side equivalent), then
     *    recreates its entire contents - every folder and file, nested structure included -
     *    directly inside the current folder: [planAndCreateDirectoryTree] creates every needed
     *    remote subfolder up front and returns one flat list of files-to-upload with sizes already
     *    known, which a second batch then uploads with one aggregated percentage across the whole
     *    tree - the same "plan first, so `runTransfer` never has to guess a total up front" shape
     *    [planDownload]/[downloadEntries] already use.
     *
     * A genuinely single [runTransfer] batch spanning both phases isn't possible here: the
     * upload phase's item list (and therefore its total byte count) only exists *after* phase 1's
     * download has completed and been extracted - unlike every other batch in this app, where
     * every item's size is already known before the batch starts.
     *
     * The archive file itself is left in place - extraction only adds new entries alongside it,
     * per this app's "never silently delete something the user didn't ask to delete" convention.
     */
    fun extractArchive(entry: Entry.FileEntry) = run {
        val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("cloud-driver-extract") }
        val archiveTempFile = tempDir.resolve(entry.name)
        val extractedDir = tempDir.resolve("extracted")
        try {
            this.runTransfer(TransferKind.EXTRACT, listOf(entry), Entry.FileEntry::sizeBytes) { _, onBytesTransferred ->
                this.client.downloadFileToPath(entry.id, archiveTempFile, onBytesTransferred)
            }
            extractZip(archiveTempFile, extractedDir)
            val plannedUploads = this.planAndCreateDirectoryTree(extractedDir, this.currentFolderId)
            this.runTransfer(TransferKind.EXTRACT, plannedUploads, PlannedUpload::sizeBytes) { item, onBytesTransferred ->
                this.client.uploadFile(item.localPath.fileName.toString(), item.localPath, item.remoteFolderId, onBytesTransferred)
            }
        } finally {
            withContext(Dispatchers.IO) { tempDir.toFile().deleteRecursively() }
        }
        this.refreshCurrentFolder()
    }

    /** One file's worth of a planned archive-extraction upload batch - see [planAndCreateDirectoryTree]. */
    private data class PlannedUpload(val localPath: Path, val remoteFolderId: String?, val sizeBytes: Long)

    /**
     * Recursively creates every subfolder directly/transitively inside [localDirectory] under
     * [remoteParentFolderId] (`null` = root), and flattens every plain file into one
     * [PlannedUpload] list with its size already known (a local [Files.size] read) - the "plan"
     * half of [extractArchive]'s two-phase extraction, split out from the actual upload so
     * [runTransfer] can report one accurate, aggregated percentage across the whole tree instead
     * of restarting per subfolder. Mirrors [duplicateFolderInto]'s own "create the folder remotely
     * first, then recurse into it" shape, just sourced from the local filesystem instead of
     * another remote folder. Subdirectories are processed concurrently with their siblings (capped
     * - see [mapConcurrently]), each after its own remote folder is created (a child file/folder
     * needs a real parent id to upload/create under).
     */
    private suspend fun planAndCreateDirectoryTree(localDirectory: Path, remoteParentFolderId: String?): List<PlannedUpload> {
        val children = withContext(Dispatchers.IO) { Files.list(localDirectory).use { it.toList() } }
        val (directories, files) = children.partition { Files.isDirectory(it) }
        val filePlans = withContext(Dispatchers.IO) {
            files.map { file -> PlannedUpload(file, remoteParentFolderId, Files.size(file)) }
        }
        val nestedPlans = directories.mapConcurrently { directory ->
            val remoteFolder = this.client.createFolder(directory.fileName.toString(), remoteParentFolderId)
            this.planAndCreateDirectoryTree(directory, remoteFolder.folderId())
        }
        return filePlans + nestedPlans.flatten()
    }

    /** Deletes every currently-selected entry - see [deleteEntries]. */
    fun deleteSelected() = this.deleteEntries(this.selected.toList())

    /**
     * Deletes every entry in [entries], concurrently (capped - see [mapConcurrently]) - backs both
     * [deleteSelected] (the toolbar's "Delete selected") and a single entry's context-menu
     * "Delete". A folder among [entries] is cascade-deleted client-side (every contained file and
     * nested folder first, then the folder itself) since the server's `deleteFolder` 409s on a
     * non-empty folder by design - see [deleteFolderRecursively].
     */
    fun deleteEntries(entries: List<Entry>) = run {
        entries.mapConcurrently { entry ->
            when (entry) {
                is Entry.FileEntry -> this.client.deleteFile(entry.id)
                is Entry.FolderEntry -> this.deleteFolderRecursively(entry.id)
            }
        }
        this.selected.removeAll(entries)
        this.refreshCurrentFolder()
    }

    private suspend fun deleteFolderRecursively(folderId: String) {
        this.client.listFiles(folderId).mapConcurrently { file -> this.client.deleteFile(file.fileId()) }
        this.client.listFolders(folderId).mapConcurrently { subFolder -> this.deleteFolderRecursively(subFolder.folderId()) }
        this.client.deleteFolder(folderId)
    }

    /** Downloads every currently-selected entry into [destinationDirectory] - see [downloadEntries]. */
    fun downloadSelected(destinationDirectory: Path) = this.downloadEntries(this.selected.toList(), destinationDirectory)

    /**
     * Downloads every entry in [entries] into [destinationDirectory], reporting aggregate progress
     * via [transferProgress] - backs both [downloadSelected] (the toolbar's "Download selected")
     * and a single entry's context-menu "Download". A folder among [entries] is recreated under
     * its own name inside [destinationDirectory], recursively, mirroring its server-side structure.
     *
     * Unlike a plain recursive walk, this plans the whole batch first ([planDownload] - a
     * listings-only walk, no content) so every file's size is known *before* any transfer starts,
     * letting [runTransfer] report one accurate, aggregated percentage across the entire batch
     * (files and nested-folder contents alike) rather than restarting/jumping per folder.
     */
    fun downloadEntries(entries: List<Entry>, destinationDirectory: Path) = run {
        val items = this.planDownload(entries, destinationDirectory)
        this.runTransfer(TransferKind.DOWNLOAD, items, DownloadItem::sizeBytes) { item, onBytesTransferred ->
            this.client.downloadFileStreaming(item.fileId, item.fileName, item.destinationDirectory, onBytesTransferred)
        }
    }

    /** One file's worth of a planned download batch - see [planDownload]. */
    private data class DownloadItem(val fileId: String, val fileName: String, val sizeBytes: Long, val destinationDirectory: Path)

    /** Flattens [entries] (files and/or folders) into one list of [DownloadItem]s, recursing into every folder via listings only (no content) so each item's size is known up front - see [downloadEntries]. */
    private suspend fun planDownload(entries: List<Entry>, destinationDirectory: Path): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        for (entry in entries) {
            when (entry) {
                is Entry.FileEntry -> items += DownloadItem(entry.id, entry.name, entry.sizeBytes, destinationDirectory)
                is Entry.FolderEntry -> items += this.planFolderDownload(entry.id, destinationDirectory.resolve(entry.name))
            }
        }
        return items
    }

    private suspend fun planFolderDownload(folderId: String, destination: Path): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        this.client.listFiles(folderId).forEach { file -> items += DownloadItem(file.fileId(), file.fileName(), file.sizeBytes(), destination) }
        this.client.listFolders(folderId).forEach { subFolder -> items += this.planFolderDownload(subFolder.folderId(), destination.resolve(subFolder.name())) }
        return items
    }

    /** Duplicates every currently-selected entry - see [duplicateEntries]. */
    fun duplicateSelected() = this.duplicateEntries(this.selected.toList())

    /**
     * Duplicates every entry in [entries] within the current folder, concurrently (capped - see
     * [mapConcurrently]) - backs both the toolbar's "Duplicate selected" and a single entry's
     * context-menu "Duplicate". Each copy's name is picked up front, sequentially (not inside the
     * concurrent batch itself, to avoid two duplicates racing to the same name), via
     * [uniqueCopyName] against this folder's already-loaded [folders]/[files] - Finder's own
     * "name copy", "name copy 2", ... convention. A file is duplicated by [duplicateFileInto]
     * (download to a local temp file, re-upload under the new name); a folder is duplicated by
     * [duplicateFolderInto] (a fresh folder, then every contained file/subfolder copied into it
     * recursively - nested names never collide, since the destination folder starts out empty).
     */
    fun duplicateEntries(entries: List<Entry>) = run {
        val existingNames = (this.folders.map { it.name() } + this.files.map { it.fileName() }).toMutableSet()
        val plannedCopies = entries.map { entry ->
            val copyName = uniqueCopyName(entry.name, existingNames)
            existingNames += copyName
            entry to copyName
        }
        plannedCopies.mapConcurrently { (entry, copyName) ->
            when (entry) {
                is Entry.FileEntry -> this.duplicateFileInto(entry.id, copyName, this.currentFolderId)
                is Entry.FolderEntry -> this.duplicateFolderInto(entry.id, copyName, this.currentFolderId)
            }
        }
        this.refreshCurrentFolder()
    }

    /** Downloads [fileId] to a fresh, throwaway local temp directory and re-uploads it as [newName] into [targetFolderId], then cleans the temp file/directory up regardless of outcome. */
    private suspend fun duplicateFileInto(fileId: String, newName: String, targetFolderId: String?) {
        val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("cloud-driver-duplicate") }
        val tempFile = tempDir.resolve(newName)
        try {
            this.client.downloadFileToPath(fileId, tempFile)
            this.client.uploadFile(newName, tempFile, targetFolderId)
        } finally {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(tempFile)
                Files.deleteIfExists(tempDir)
            }
        }
    }

    /** Creates a new folder named [newName] under [targetParentFolderId], then recursively copies every file/subfolder of [folderId] into it. */
    private suspend fun duplicateFolderInto(folderId: String, newName: String, targetParentFolderId: String?) {
        val newFolder = this.client.createFolder(newName, targetParentFolderId)
        this.client.listFiles(folderId).mapConcurrently { file -> this.duplicateFileInto(file.fileId(), file.fileName(), newFolder.folderId()) }
        this.client.listFolders(folderId).mapConcurrently { subFolder -> this.duplicateFolderInto(subFolder.folderId(), subFolder.name(), newFolder.folderId()) }
    }

    /**
     * Moves every entry in [entriesToMove] into [targetFolderId], concurrently (capped - see
     * [mapConcurrently]) - the action a drag-and-drop drop in [FileBrowserScreen] resolves to.
     * [targetFolderId] is always a real folder id here (never root/`null`) since the only drop
     * targets [FileBrowserScreen] currently offers are folder rows within the listing being
     * dragged from. See [moveEntryToFolder] for a single-entry move that *can* target the root
     * (the context menu's "Move to...").
     */
    fun moveEntriesToFolder(entriesToMove: List<Entry>, targetFolderId: String) = run {
        entriesToMove.mapConcurrently { entry -> this.moveEntry(entry, targetFolderId) }
        this.selected.clear()
        this.refreshCurrentFolder()
    }

    /** Moves a single [entry] into [targetFolderId] (`null` = the root) - backs the context menu's "Move to..." dialog, which (unlike drag-and-drop) can target the root directly. */
    fun moveEntryToFolder(entry: Entry, targetFolderId: String?) = run {
        this.moveEntry(entry, targetFolderId)
        this.selected.remove(entry)
        this.refreshCurrentFolder()
    }

    /** Moves a single [entry] into [targetFolderId] - a file via [CloudDriverClient.moveFile], a folder via a name-preserving [CloudDriverClient.updateFolder] (only its parent changes). */
    private suspend fun moveEntry(entry: Entry, targetFolderId: String?) {
        when (entry) {
            is Entry.FileEntry -> this.client.moveFile(entry.id, targetFolderId)
            is Entry.FolderEntry -> this.client.updateFolder(entry.id, entry.name, targetFolderId)
        }
    }

    // --- account: change email --------------------------------------------

    /**
     * The new address a [requestEmailChange] call is currently mid-flight for - `null` outside of
     * that flow. Drives the Dashboard's settings-menu "Change Email" dialog from step one (enter a
     * new address) to step two (enter the code just e-mailed there); [confirmEmailChange] resolves
     * it back to `null` on success, [cancelEmailChangeRequest] does the same if the user backs out.
     */
    var pendingEmailChangeAddress: String? by mutableStateOf(null)
        private set

    /** Step one of changing the signed-in account's e-mail address - e-mails a verification code to [newEmailAddress]; the account's address itself is not changed yet. */
    fun requestEmailChange(newEmailAddress: String) = run {
        this.client.requestEmailChange(newEmailAddress)
        this.pendingEmailChangeAddress = newEmailAddress
    }

    /** Abandons an in-progress [requestEmailChange] flow (e.g. the user closed the code-entry dialog) without changing anything server-side - the pending code simply expires unused. */
    fun cancelEmailChangeRequest() {
        this.pendingEmailChangeAddress = null
    }

    /** Step two - submits [code], which (on success) actually changes the account's e-mail address to [pendingEmailChangeAddress] server-side; updates [currentUserEmail] to match locally, since there is no `GET /me` to re-fetch it from. */
    fun confirmEmailChange(code: String) = run {
        this.client.confirmEmailChange(code)
        this.currentUserEmail = this.pendingEmailChangeAddress
        this.pendingEmailChangeAddress = null
    }

}

/**
 * Picks a name for a duplicate of [originalName] that doesn't collide with anything in
 * [existingNames] - Finder's own "name copy", "name copy 2", ... convention, preserving
 * [originalName]'s file extension (if any, and not for a folder name, which never has one in this
 * app's own sense) so a duplicated file's content type stays recognizable by name.
 */
private fun uniqueCopyName(originalName: String, existingNames: Set<String>): String {
    val dotIndex = originalName.lastIndexOf('.')
    val hasExtension = dotIndex > 0 && dotIndex < originalName.length - 1
    val baseName = if (hasExtension) originalName.substring(0, dotIndex) else originalName
    val extension = if (hasExtension) originalName.substring(dotIndex) else ""

    var candidate = "$baseName copy$extension"
    var suffix = 2
    while (candidate in existingNames) {
        candidate = "$baseName copy $suffix$extension"
        suffix++
    }
    return candidate
}
