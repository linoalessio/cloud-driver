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
import de.lino.cloud.platform.desktop.utils.downloadTo
import de.lino.cloud.platform.desktop.utils.mapConcurrently
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

    var dashboardStats: AccountStats? by mutableStateOf(null)
        private set

    private fun onAuthenticated(email: String, jwt: String) {
        this.currentUserEmail = email
        this.currentUserId = decodeJwtSubject(jwt)
    }

    // --- file browser state ---------------------------------------------

    /** The path from the root to [currentFolderId], root-first; empty means we're at the root. */
    val breadcrumbs = mutableStateListOf<FolderResponse>()

    var currentFolderId: String? by mutableStateOf(null)
        private set

    val folders = mutableStateListOf<FolderResponse>()
    val files = mutableStateListOf<de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse>()
    val selected = mutableStateListOf<Entry>()

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

    fun logout() {
        this.client.logout()
        this.currentUserEmail = null
        this.currentUserId = null
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
     * Uploads every chosen local file concurrently (capped - see [mapConcurrently]) rather than
     * one at a time; `ApiClient`'s own batch upload can't be reused here since it has no
     * `folderId` parameter (always targets the root), unlike [CloudDriverClient.uploadFile].
     */
    fun uploadFiles(paths: List<Path>) = run {
        paths.mapConcurrently { path -> this.client.uploadFile(path, this.currentFolderId) }
        this.refreshCurrentFolder()
    }

    /** Zips [directory] client-side and uploads the archive as a single file, per this app's "folder upload = zip" spec. */
    fun uploadFolderAsZip(directory: Path) = run {
        val zipPath = zipDirectory(directory)
        try {
            this.client.uploadFile("${directory.fileName}.zip", zipPath, this.currentFolderId)
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(zipPath) }
        }
        this.refreshCurrentFolder()
    }

    /**
     * Deletes every currently-selected entry, concurrently (capped - see [mapConcurrently]). A
     * selected folder is cascade-deleted client-side (every contained file and nested folder
     * first, then the folder itself) since the server's `deleteFolder` 409s on a non-empty
     * folder by design - see [deleteFolderRecursively].
     */
    fun deleteSelected() = run {
        this.selected.toList().mapConcurrently { entry ->
            when (entry) {
                is Entry.FileEntry -> this.client.deleteFile(entry.id)
                is Entry.FolderEntry -> this.deleteFolderRecursively(entry.id)
            }
        }
        this.selected.clear()
        this.refreshCurrentFolder()
    }

    private suspend fun deleteFolderRecursively(folderId: String) {
        this.client.listFiles(folderId).mapConcurrently { file -> this.client.deleteFile(file.fileId()) }
        this.client.listFolders(folderId).mapConcurrently { subFolder -> this.deleteFolderRecursively(subFolder.folderId()) }
        this.client.deleteFolder(folderId)
    }

    /**
     * Downloads every currently-selected entry into [destinationDirectory], concurrently (capped
     * - see [mapConcurrently]). A selected folder is recreated under its own name inside
     * [destinationDirectory], recursively, mirroring its server-side structure - see
     * [downloadFolderRecursively].
     */
    fun downloadSelected(destinationDirectory: Path) = run {
        this.selected.toList().mapConcurrently { entry ->
            when (entry) {
                is Entry.FileEntry -> this.client.downloadFile(entry.id).downloadTo(destinationDirectory)
                is Entry.FolderEntry -> this.downloadFolderRecursively(entry.id, destinationDirectory.resolve(entry.name))
            }
        }
    }

    private suspend fun downloadFolderRecursively(folderId: String, destination: Path) {
        withContext(Dispatchers.IO) { Files.createDirectories(destination) }
        this.client.listFiles(folderId).mapConcurrently { file -> this.client.downloadFile(file.fileId()).downloadTo(destination) }
        this.client.listFolders(folderId).mapConcurrently { subFolder ->
            this.downloadFolderRecursively(subFolder.folderId(), destination.resolve(subFolder.name()))
        }
    }

    /**
     * Moves every entry in [entriesToMove] into [targetFolderId], concurrently (capped - see
     * [mapConcurrently]) - the action a drag-and-drop drop in [FileBrowserScreen] resolves to.
     * [targetFolderId] is always a real folder id here (never root/`null`) since the only drop
     * targets [FileBrowserScreen] currently offers are folder rows within the listing being
     * dragged from.
     */
    fun moveEntriesToFolder(entriesToMove: List<Entry>, targetFolderId: String) = run {
        entriesToMove.mapConcurrently { entry -> this.moveEntry(entry, targetFolderId) }
        this.selected.clear()
        this.refreshCurrentFolder()
    }

    /** Moves a single [entry] into [targetFolderId] - a file via [CloudDriverClient.moveFile], a folder via a name-preserving [CloudDriverClient.updateFolder] (only its parent changes). */
    private suspend fun moveEntry(entry: Entry, targetFolderId: String) {
        when (entry) {
            is Entry.FileEntry -> this.client.moveFile(entry.id, targetFolderId)
            is Entry.FolderEntry -> this.client.updateFolder(entry.id, entry.name, targetFolderId)
        }
    }

}
