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
import de.lino.cloud.platform.desktop.utils.decodeJwtSubject
import de.lino.cloud.platform.desktop.utils.downloadFileStreaming
import de.lino.cloud.platform.desktop.utils.extractZip
import de.lino.cloud.platform.desktop.utils.mapConcurrently
import de.lino.cloud.platform.desktop.utils.sanitizedForLocalPath
import de.lino.cloud.platform.desktop.utils.uninstallApp
import de.lino.cloud.platform.desktop.utils.zipDirectory
import de.lino.cloud.platform.rest.api.ApiClient
import de.lino.cloud.platform.rest.api.dto.Dtos.AuditLogEntryResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.AuthUserResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.MetricsSnapshotResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse
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

/** Page size [AppViewModel.refreshCurrentFolder]/[AppViewModel.loadMoreEntries] request per [CloudDriverClient.listFilesPage]/[listFoldersPage] call. */
private const val FOLDER_VIEW_PAGE_SIZE = 200

class AppViewModel(private val scope: CoroutineScope, initialServerUrl: String) {

    /** The active session's HTTP client, against the hardcoded server address(es) passed at construction (see `Main.kt`'s `DEFAULT_SERVER_URL`). */
    val client: CloudDriverClient = CloudDriverClient(initialServerUrl, initialServerUrl)

    /**
     * The active light/dark theme - synced to the signed-in account (`CloudUser.themeMode`
     * server-side, via `PUT /cloudUsers/theme`) instead of a local per-device file, so a choice
     * made on one device follows the account to every other device it's signed into. Defaults to
     * [ThemeMode.LIGHT] until the account's own stored preference is fetched (see
     * [refreshAccountInfo], called right after authenticating) - there is no signed-in account to
     * ask before that point, e.g. while the login screen itself is showing. Toggled via
     * [toggleTheme].
     */
    var themeMode: ThemeMode by mutableStateOf(ThemeMode.DARK)
        private set

    /** Flips [themeMode] and syncs the new choice to the account via [CloudDriverClient.updateThemePreference], so it follows the account to every other signed-in device. */
    fun toggleTheme() {
        val newMode = if (this.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        this.themeMode = newMode
        this.scope.launch {
            try {
                this@AppViewModel.client.updateThemePreference(newMode.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort sync - the local toggle above already applied, and a failed sync
                // here just means the choice doesn't (yet) follow to another device; not worth
                // surfacing as a user-facing error for a UI preference.
            }
        }
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

    /**
     * Whether the signed-in account is flagged admin - fetched via [CloudDriverClient.getMe]
     * alongside [refreshAccountInfo], since there is no other way for this client to learn it
     * (admin-flag writes are deliberately terminal-only, never a REST route, and the account's own
     * flag isn't part of [CloudUserResponse]). Drives whether the sidebar's "Admin" entry is shown
     * at all - `false` on any fetch failure, same "don't assume" caution as every other
     * `null`-on-failure Dashboard-only field in this class, just defaulting to the non-privileged
     * value instead of `null` since a [Boolean] has no natural "unknown" state to fall back to.
     */
    var currentUserIsAdmin: Boolean by mutableStateOf(false)
        private set

    var dashboardStats: AccountStats? by mutableStateOf(null)
        private set

    /**
     * `true` once [client] reported [CloudDriverClient.usedKeychainFallback] after the first
     * successful authentication (fresh login, registration/password-reset confirm, or a restored
     * session at startup) - surfaced as a dismissible banner (`Sidebar.kt`'s `AuthenticatedShell`)
     * rather than silently persisting the session token less securely than a real OS keychain
     * would, with no signal to the user. Intentionally re-armed on every fresh sign-in within a
     * single run of the app (not persisted as "already seen forever") - `usedKeychainFallback`
     * itself never changes for a given process, so this only ever flips `false` via explicit
     * [dismissKeychainFallbackNotice].
     */
    var showKeychainFallbackNotice: Boolean by mutableStateOf(false)
        private set

    fun dismissKeychainFallbackNotice() {
        this.showKeychainFallbackNotice = false
    }

    private suspend fun onAuthenticated(email: String, jwt: String) {
        this.currentUserEmail = email
        this.currentUserId = decodeJwtSubject(jwt)
        if (this.client.usedKeychainFallback) this.showKeychainFallbackNotice = true
        this.refreshAccountInfo()
        this.startLiveUpdates()
    }

    /**
     * Called once at startup (see `Main.kt`) after [CloudDriverClient.tryRestoreSession] finds a
     * still-valid persisted session. Unlike [onAuthenticated], there is no e-mail address on hand
     * here - restoring a session is not itself a server call that returns one, and this app has no
     * `GET /me`-style endpoint to fetch it back from - so [currentUserEmail] stays `null` (the
     * Dashboard's "Email" row already renders `"-"` for that case) until the next explicit
     * login/register/password-reset or e-mail change.
     */
    private suspend fun onSessionRestored(jwt: String) {
        this.currentUserId = decodeJwtSubject(jwt)
        if (this.client.usedKeychainFallback) this.showKeychainFallbackNotice = true
        this.refreshAccountInfo()
        this.startLiveUpdates()
    }

    /**
     * Item 10 (live push via WebSocket, see `architecture/SERVICES.md`) - opens the connection via
     * [CloudDriverClient.startLiveUpdates] and reacts to a pushed notification by refreshing
     * whichever of the file browser/[Dashboard] is currently showing, instead of requiring the
     * user to hit "Refresh" to see a change made from elsewhere (another device, or a file shared
     * with this account - see item 9). The push callback fires on an internal HTTP-client thread
     * (see [de.lino.cloud.platform.rest.api.push.LiveUpdateClient.Listener]'s own Javadoc), so it
     * is immediately re-dispatched onto [scope] before touching any Compose state or calling
     * [loadCurrentFolder]/[loadDashboardStats] - both already re-entrant-safe (each goes through
     * [run]'s own `busy` guard), so an update arriving while another action is in flight is simply
     * dropped rather than queued; the next push (or the user's own "Refresh") catches up.
     */
    private fun startLiveUpdates() {
        this.client.startLiveUpdates {
            this.scope.launch {
                when (this@AppViewModel.screen) {
                    Screen.Browser -> this@AppViewModel.loadCurrentFolder()
                    Screen.Dashboard -> this@AppViewModel.loadDashboardStats()
                    else -> {}
                }
            }
        }
    }

    /**
     * Call once at app startup, before the first screen renders (see `Main.kt`) - if a valid
     * session was persisted from a previous run, skips the login screen entirely and goes straight
     * to [Screen.Browser], mirroring what [login]/[confirmRegister]/[confirmPasswordReset] already
     * do on success. A no-op (silently falls through to the initial [Screen.Login]) if there was no
     * persisted session or it's no longer valid - the same "either way, show the login screen"
     * contract [de.lino.cloud.platform.rest.api.SessionManager.tryRestoreSession] itself documents.
     */
    fun tryRestoreSession() = run {
        val jwt = this.client.tryRestoreSession() ?: return@run
        this.onSessionRestored(jwt)
        this.screen = Screen.Browser
    }

    /**
     * Re-fetches [currentUserCreatedAtEpochMillis]/[currentUserUploadedBytes]/[currentUserMaxBytesToUpload]/
     * [themeMode] from the server via [CloudDriverClient.getCloudUser] - called once from
     * [onAuthenticated]/[onSessionRestored], and again from [loadDashboardStats] every time the
     * Dashboard is shown, since an operator can change an account's upload quota out-of-band (e.g.
     * the terminal's `cu update <email> <bytes>` command) while this client is already signed in;
     * without a re-fetch here the Dashboard's "Storage" row would keep showing whatever quota was
     * in effect at login time until the next full sign-in. [themeMode] is only overwritten when the
     * account actually has a stored preference (`themeMode() != null`) - a fresh account, or one
     * that predates this field, has no server-side opinion yet, so this device's already-showing
     * theme (whatever [ThemeMode.LIGHT] default or earlier value it had) is left alone rather than
     * being reset to a hardcoded default on every login.
     */
    private suspend fun refreshAccountInfo() {
        val userId = this.currentUserId ?: return
        try {
            val cloudUser = this.client.getCloudUser(userId)
            this.currentUserCreatedAtEpochMillis = cloudUser?.timeStamp()
            this.currentUserUploadedBytes = cloudUser?.currentUploadedBytes()
            this.currentUserMaxBytesToUpload = cloudUser?.maxBytesToUpload()
            cloudUser?.themeMode()?.let { syncedThemeMode ->
                runCatching { ThemeMode.valueOf(syncedThemeMode) }.getOrNull()?.let { this.themeMode = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            this.currentUserCreatedAtEpochMillis = null
            this.currentUserUploadedBytes = null
            this.currentUserMaxBytesToUpload = null
        }
        try {
            this.currentUserIsAdmin = this.client.getMe().isAdmin()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            this.currentUserIsAdmin = false
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

    // --- trash state ------------------------------------------------------

    /** Every folder currently in the caller's trash, each paired with when it becomes eligible for permanent removal - see [Screen.Trash]/`TrashScreen`. Loaded/refreshed by [loadTrash]/[restoreFile]/[restoreFolder]/[emptyTrash]. */
    val trashFolders = mutableStateListOf<de.lino.cloud.platform.rest.api.dto.Dtos.TrashedFolderSummaryResponse>()

    /** Every file currently in the caller's trash, each paired with when it becomes eligible for permanent removal - counterpart to [trashFolders]. */
    val trashFiles = mutableStateListOf<de.lino.cloud.platform.rest.api.dto.Dtos.TrashedFileSummaryResponse>()

    // --- shared with me state ----------------------------------------------

    /** Every folder directly shared with the signed-in account, each paired with the sharing account's email - see [Screen.SharedWithMe]. Loaded/refreshed by [loadSharedWithMe]. */
    val sharedWithMeFolders = mutableStateListOf<de.lino.cloud.platform.rest.api.dto.Dtos.SharedFolderSummaryResponse>()

    /** Every file directly shared with the signed-in account, each paired with the sharing account's email - counterpart to [sharedWithMeFolders]. */
    val sharedWithMeFiles = mutableStateListOf<de.lino.cloud.platform.rest.api.dto.Dtos.SharedFileSummaryResponse>()

    // --- shared folder browsing (item 9, added 2026-09-02) ------------------

    /** The path from the originally-opened shared folder down to [sharedBrowseCurrentFolderId], root-first - mirrors [breadcrumbs]' own shape for the caller's own folders, but there is no "shared root" below index 0 (the shared folder itself is the topmost reachable node). */
    val sharedBrowseBreadcrumbs = mutableStateListOf<FolderResponse>()

    /** The folder currently being browsed via a share - `null` before [openSharedFolder] is first called. */
    var sharedBrowseCurrentFolderId: String? by mutableStateOf(null)
        private set

    /** The email of the account that shared the *root* folder of the current browse session - carried through every subfolder navigated into, for display ("Shared by ..."). */
    var sharedBrowseOwnerEmail: String? by mutableStateOf(null)
        private set

    val sharedBrowseFiles = mutableStateListOf<de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse>()
    val sharedBrowseFolders = mutableStateListOf<FolderResponse>()

    // --- admin panel state ---------------------------------------------------

    /** Every registered account (admin-only) - see [Screen.Admin]. Loaded/refreshed by [loadAdmin]. */
    val adminAuthUsers = mutableStateListOf<AuthUserResponse>()

    /** The persisted audit trail (admin-only) - counterpart to [adminAuthUsers]. */
    val adminAuditLog = mutableStateListOf<AuditLogEntryResponse>()

    /** Whether [loadAdmin]/[refreshAdmin] last loaded every audit-log entry ([true]) or just the most recent 20 ([false], the default). */
    var adminAuditLogShowAll: Boolean by mutableStateOf(false)
        private set

    /**
     * Item 13's counters/gauges (admin-only), or `null` if `cloud-driver-extensions-metrics` isn't
     * running on this deployment (or the last [refreshAdmin] call's own fetch simply failed) - see
     * [refreshAdmin]'s own try/catch. `AdminScreen` renders an "unavailable" notice for `null`
     * rather than treating it as a loading error that should fail the whole panel.
     */
    var adminMetrics: MetricsSnapshotResponse? by mutableStateOf(null)
        private set

    /**
     * [folders]/[files] are loaded a page ([FOLDER_VIEW_PAGE_SIZE] entries) at a time via
     * [CloudDriverClient.listFoldersPage]/[listFilesPage] - these hold each list's own [Page]
     * cursor, `null` once that list has no further page. [hasMoreEntries] drives whether
     * `FileBrowserScreen`'s "Load more" button is shown; a deliberate explicit action rather than
     * an auto-load-on-scroll, so fetching the next page (a real network round trip) only ever
     * happens on a user's own click, not silently as they scroll. Every *other* caller in this
     * class that needs a folder's *complete* contents (delete/duplicate/download planning,
     * `deleteFolderRecursively` etc.) still calls the unpaginated `listFiles`/`listFolders` -
     * this pagination only applies to what's actually rendered in the current folder view.
     */
    private var foldersNextCursor: String? by mutableStateOf(null)
    private var filesNextCursor: String? by mutableStateOf(null)
    val hasMoreEntries: Boolean get() = this.foldersNextCursor != null || this.filesNextCursor != null

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
     * same way [deleteSelected]/[deleteEntries] assume their own caller already confirmed the
     * action first. Dispatched on [Dispatchers.IO] rather than run directly on the calling (UI)
     * thread, matching this class's own "blocking local I/O off the UI dispatcher" convention -
     * even though the process exits immediately after, so the UI never actually gets a chance to
     * visibly block either way.
     */
    fun uninstall() {
        this.scope.launch(Dispatchers.IO) {
            this@AppViewModel.client.clearPersistedSession()
            this@AppViewModel.client.close()
            uninstallApp()
            kotlin.system.exitProcess(0)
        }
    }

    /**
     * Ends the session both in memory (synchronously, via [CloudDriverClient.logout] - so the
     * screen switch back to [Screen.Login] below can't race a still-authenticated [client]) and in
     * persisted storage (asynchronously, via [CloudDriverClient.clearPersistedSession] - a real OS
     * keychain call/file write with no reason to block this screen transition on).
     */
    fun logout() {
        this.client.stopLiveUpdates()
        this.client.logout()
        this.scope.launch { this@AppViewModel.client.clearPersistedSession() }
        this.currentUserEmail = null
        this.currentUserId = null
        this.currentUserCreatedAtEpochMillis = null
        this.currentUserUploadedBytes = null
        this.currentUserMaxBytesToUpload = null
        this.currentUserIsAdmin = false
        this.showKeychainFallbackNotice = false
        this.dashboardStats = null
        this.breadcrumbs.clear()
        this.currentFolderId = null
        this.folders.clear()
        this.files.clear()
        this.foldersNextCursor = null
        this.filesNextCursor = null
        this.selected.clear()
        this.trashFolders.clear()
        this.trashFiles.clear()
        this.sharedWithMeFolders.clear()
        this.sharedWithMeFiles.clear()
        this.sharedBrowseBreadcrumbs.clear()
        this.sharedBrowseCurrentFolderId = null
        this.sharedBrowseOwnerEmail = null
        this.sharedBrowseFiles.clear()
        this.sharedBrowseFolders.clear()
        this.adminAuthUsers.clear()
        this.adminAuditLog.clear()
        this.adminAuditLogShowAll = false
        this.adminMetrics = null
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

    // --- trash -------------------------------------------------------

    fun showTrash() {
        this.screen = Screen.Trash
        this.errorMessage = null
    }

    /** Public, guarded entry point - use from a screen (button/`LaunchedEffect`), mirroring [loadCurrentFolder]'s own shape. */
    fun loadTrash() = run { this.refreshTrash() }

    /** The actual reload, callable from inside another [run]-wrapped action (e.g. after [restoreFile]/[restoreFolder]) without tripping [run]'s own `busy` guard - same reasoning as [refreshCurrentFolder]. */
    private suspend fun refreshTrash() {
        this.trashFolders.clear()
        this.trashFolders.addAll(this.client.listDeletedFolders())
        this.trashFiles.clear()
        this.trashFiles.addAll(this.client.listDeletedFiles())
    }

    /** Restores a trashed file back to its previous folder, then refreshes [trashFiles]/[trashFolders]. */
    fun restoreFile(fileId: String) = run {
        this.client.restoreFile(fileId)
        this.refreshTrash()
    }

    /** Restores a trashed folder back to its previous parent, then refreshes [trashFiles]/[trashFolders]. */
    fun restoreFolder(folderId: String) = run {
        this.client.restoreFolder(folderId)
        this.refreshTrash()
    }

    /**
     * Permanently removes every file/folder currently in the trash - the "Empty trash bin" action.
     * Bypasses the server's configured retention window entirely and is irreversible; the caller
     * (`TrashScreen`) is responsible for confirming with the user first via a dialog, the same
     * "this function performs the action unconditionally" convention [uninstall]/[deleteEntries]
     * already use for their own destructive actions.
     */
    fun emptyTrash() = run {
        this.client.emptyTrash()
        this.refreshTrash()
    }

    // --- shared with me (item 9) ------------------------------------------

    fun showSharedWithMe() {
        this.screen = Screen.SharedWithMe
        this.errorMessage = null
    }

    /** Public, guarded entry point - use from a screen (button/`LaunchedEffect`), mirroring [loadTrash]'s own shape. */
    fun loadSharedWithMe() = run { this.refreshSharedWithMe() }

    /** The actual reload, callable from inside another [run]-wrapped action without tripping [run]'s own `busy` guard - same reasoning as [refreshTrash]. */
    private suspend fun refreshSharedWithMe() {
        this.sharedWithMeFolders.clear()
        this.sharedWithMeFolders.addAll(this.client.listSharedFoldersWithMe())
        this.sharedWithMeFiles.clear()
        this.sharedWithMeFiles.addAll(this.client.listSharedWithMe())
    }

    // --- shared folder browsing (item 9, added 2026-09-02) ------------------

    /** Opens [folder] (a top-level entry from [sharedWithMeFolders]) for browsing - the context menu/row click backing "click a shared folder to browse into it". Resets [sharedBrowseBreadcrumbs] to just this one folder. */
    fun openSharedFolder(folder: FolderResponse, ownerEmail: String) = run {
        this.sharedBrowseBreadcrumbs.clear()
        this.sharedBrowseBreadcrumbs.add(folder)
        this.sharedBrowseCurrentFolderId = folder.folderId()
        this.sharedBrowseOwnerEmail = ownerEmail
        this.screen = Screen.SharedFolderBrowser
        this.refreshSharedBrowseFolder()
    }

    /** Navigates one level deeper, into [folder] (a subfolder of the currently-browsed shared folder). */
    fun openSharedSubfolder(folder: FolderResponse) = run {
        this.sharedBrowseBreadcrumbs.add(folder)
        this.sharedBrowseCurrentFolderId = folder.folderId()
        this.refreshSharedBrowseFolder()
    }

    /** Jumps back to breadcrumb [index] (0 = the originally-opened shared folder) - mirrors [navigateToBreadcrumb]'s own truncate-then-reload shape, but there is no "-1"/root case here, since the shared folder itself is the topmost reachable node (leaving it entirely means returning to [Screen.SharedWithMe] via the sidebar). */
    fun navigateSharedBreadcrumb(index: Int) = run {
        while (this.sharedBrowseBreadcrumbs.size > index + 1) {
            this.sharedBrowseBreadcrumbs.removeAt(this.sharedBrowseBreadcrumbs.lastIndex)
        }
        this.sharedBrowseCurrentFolderId = this.sharedBrowseBreadcrumbs.last().folderId()
        this.refreshSharedBrowseFolder()
    }

    /** Public, guarded entry point for a manual refresh of the currently-browsed shared folder (e.g. a "Refresh" button), mirroring [loadCurrentFolder]'s own shape. */
    fun reloadSharedBrowseFolder() = run { this.refreshSharedBrowseFolder() }

    /** The actual reload, callable from inside another [run]-wrapped action (e.g. [openSharedFolder]/[openSharedSubfolder]/[navigateSharedBreadcrumb]) without tripping [run]'s own `busy` guard - same reasoning as [refreshCurrentFolder]. A no-op if [sharedBrowseCurrentFolderId] is somehow unset. */
    private suspend fun refreshSharedBrowseFolder() {
        val folderId = this.sharedBrowseCurrentFolderId ?: return
        val contents = this.client.listSharedFolderContents(folderId)
        this.sharedBrowseFiles.clear()
        this.sharedBrowseFiles.addAll(contents.files())
        this.sharedBrowseFolders.clear()
        this.sharedBrowseFolders.addAll(contents.subfolders())
    }

    /**
     * Downloads an entire shared folder (recursively - every file and nested subfolder) into
     * `destinationDirectory/<folderName>`, reporting aggregate byte-level progress the same way
     * [downloadEntries] does for the caller's own folders - backs the download icon next to a
     * shared folder row (both at the top level in `SharedWithMeScreen` and while browsing deeper
     * in `SharedFolderBrowserScreen`). Every file download still goes through the caller's own
     * share-checked [de.lino.cloud.platform.desktop.client.CloudDriverClient.downloadFileStreaming]
     * - the same route/access check an owner's own download uses - so no separate "shared
     * download" primitive was needed once [planSharedFolderDownload] (below) can already resolve
     * which files exist.
     */
    fun downloadSharedFolder(folderId: String, folderName: String, destinationDirectory: Path) = run {
        val items = this.planSharedFolderDownload(folderId, destinationDirectory.resolve(sanitizedForLocalPath(folderName)))
        this.runTransfer(TransferKind.DOWNLOAD, items, DownloadItem::sizeBytes) { item, onBytesTransferred ->
            this.client.downloadFileStreaming(item.fileId, item.fileName, item.destinationDirectory, onBytesTransferred)
        }
    }

    /** Flattens a shared folder tree (via [de.lino.cloud.platform.desktop.client.CloudDriverClient.listSharedFolderContents], recursively) into one list of [DownloadItem]s, mirroring [planFolderDownload]'s own shape but reading through the share-aware listing instead of the caller's own [de.lino.cloud.platform.desktop.client.CloudDriverClient.listFiles]/[de.lino.cloud.platform.desktop.client.CloudDriverClient.listFolders]. */
    private suspend fun planSharedFolderDownload(folderId: String, destination: Path): List<DownloadItem> {
        val contents = this.client.listSharedFolderContents(folderId)
        val items = mutableListOf<DownloadItem>()
        contents.files().forEach { file -> items += DownloadItem(file.fileId(), file.fileName(), file.sizeBytes(), destination) }
        contents.subfolders().forEach { subfolder -> items += this.planSharedFolderDownload(subfolder.folderId(), destination.resolve(sanitizedForLocalPath(subfolder.name()))) }
        return items
    }

    // --- admin panel --------------------------------------------------------

    fun showAdmin() {
        this.screen = Screen.Admin
        this.errorMessage = null
    }

    /** Public, guarded entry point - use from a screen (button/`LaunchedEffect`). [showAll], if given, replaces [adminAuditLogShowAll] before reloading; otherwise the previous choice is kept (so e.g. `AdminScreen`'s own refresh button doesn't silently reset a "show all" toggle back to the default). */
    fun loadAdmin(showAll: Boolean? = null) = run {
        if (showAll != null) this.adminAuditLogShowAll = showAll
        this.refreshAdmin()
    }

    /** The actual reload, callable from inside another [run]-wrapped action without tripping [run]'s own `busy` guard - same reasoning as [refreshTrash]/[refreshSharedWithMe]. */
    private suspend fun refreshAdmin() {
        this.adminAuthUsers.clear()
        this.adminAuthUsers.addAll(this.client.listAdminAuthUsers())
        this.adminAuditLog.clear()
        this.adminAuditLog.addAll(this.client.listAdminAuditLog(all = this.adminAuditLogShowAll))
        this.adminMetrics = try {
            this.client.getAdminMetrics()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // metrics extension not running, or fetch failed - must never fail the rest of this panel
        }
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
        val folderPage = this.client.listFoldersPage(folderId, null, FOLDER_VIEW_PAGE_SIZE)
        val filePage = this.client.listFilesPage(folderId, null, FOLDER_VIEW_PAGE_SIZE)
        this.folders.clear()
        this.folders.addAll(folderPage.items())
        this.foldersNextCursor = folderPage.nextCursor()
        this.files.clear()
        this.files.addAll(filePage.items())
        this.filesNextCursor = filePage.nextCursor()
    }

    /**
     * Public, guarded entry point (see [loadCurrentFolder]) for `FileBrowserScreen`'s "Load more"
     * button: appends the next page of whichever of [folders]/[files] still has a [foldersNextCursor]/
     * [filesNextCursor], rather than restarting the listing from the top. A no-op if neither has
     * a next page ([hasMoreEntries] `false`) - the button that calls this is only shown while it's `true`.
     */
    fun loadMoreEntries() = run {
        val folderId = this.currentFolderId
        this.foldersNextCursor?.let { cursor ->
            val page = this.client.listFoldersPage(folderId, cursor, FOLDER_VIEW_PAGE_SIZE)
            this.folders.addAll(page.items())
            this.foldersNextCursor = page.nextCursor()
        }
        this.filesNextCursor?.let { cursor ->
            val page = this.client.listFilesPage(folderId, cursor, FOLDER_VIEW_PAGE_SIZE)
            this.files.addAll(page.items())
            this.filesNextCursor = page.nextCursor()
        }
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
     * Uploads [filePath] as [fileName] into [folderId], preferring the presigned direct-to-client
     * path (bypassing this app's own server for the data path entirely - see
     * `architecture/AWS_S3_IMPL.md`) and transparently falling back to the ordinary
     * server-mediated [CloudDriverClient.uploadFile] the moment the server reports (`503`) it
     * hasn't configured presigned transfer - so every upload call site below works unchanged
     * against an older or non-S3-configured deployment too, with no capability negotiation of its
     * own needed. Shared by [uploadFiles]/[uploadFolderAsZip]/[uploadDroppedPaths]; the
     * archive-extraction re-upload path (`extractArchive`) deliberately still calls
     * [CloudDriverClient.uploadFile] directly - a secondary, lower-volume flow not worth the same
     * treatment in this first pass.
     */
    private suspend fun uploadFileStreaming(
        fileName: String,
        filePath: Path,
        folderId: String?,
        onBytesTransferred: (Long) -> Unit,
    ): StoredFileSummaryResponse =
        try {
            this.client.uploadFileViaPresignedUrl(fileName, filePath, folderId, onBytesTransferred)
        } catch (e: ApiClient.ApiException) {
            if (e.statusCode() == 503) {
                this.client.uploadFile(fileName, filePath, folderId, onBytesTransferred)
            } else {
                throw e
            }
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
            this.uploadFileStreaming(path.fileName.toString(), path, this.currentFolderId, onBytesTransferred)
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
                this.uploadFileStreaming(zipName, path, this.currentFolderId, onBytesTransferred)
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
                this.uploadFileStreaming(item.uploadName, item.sourcePath, this.currentFolderId, onBytesTransferred)
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
     *    recreates its entire contents - every folder and file, nested structure included - inside
     *    a **new destination folder** created for this extraction (fixed 2026-09-04; previously
     *    extracted straight into the current folder, dumping the archive's contents alongside
     *    whatever else was already there): [entry]'s own name with its extension stripped
     *    ([archiveBaseName] - `"test.zip"` -> `"test"`), disambiguated against the current folder's
     *    already-loaded subfolder names via [uniqueFolderName] (`"test"`, then `"test 2"`,
     *    `"test 3"`, ... if a folder by that name already exists - deliberately not
     *    [uniqueCopyName]'s `"... copy"` convention, since a re-extracted archive isn't a duplicate
     *    of anything). [planAndCreateDirectoryTree] then creates every needed remote subfolder
     *    under that new destination folder and returns one flat list of files-to-upload with sizes
     *    already known, which a second batch uploads with one aggregated percentage across the
     *    whole tree - the same "plan first, so `runTransfer` never has to guess a total up front"
     *    shape [planDownload]/[downloadEntries] already use.
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
        val archiveTempFile = tempDir.resolve(sanitizedForLocalPath(entry.name))
        val extractedDir = tempDir.resolve("extracted")
        try {
            this.runTransfer(TransferKind.EXTRACT, listOf(entry), Entry.FileEntry::sizeBytes) { _, onBytesTransferred ->
                this.client.downloadFileToPath(entry.id, archiveTempFile, onBytesTransferred)
            }
            extractZip(archiveTempFile, extractedDir)
            val existingFolderNames = this.folders.map { it.name() }.toSet()
            val destinationFolderName = uniqueFolderName(archiveBaseName(entry.name), existingFolderNames)
            val destinationFolder = this.client.createFolder(destinationFolderName, this.currentFolderId)
            val plannedUploads = this.planAndCreateDirectoryTree(extractedDir, destinationFolder.folderId())
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
     * of restarting per subfolder. Mirrors [planFolderDuplicate]'s own "create the folder remotely
     * first, then recurse into it" shape, just sourced from the local filesystem instead of
     * another remote folder. Subdirectories are processed concurrently with their siblings (capped
     * - see [mapConcurrently]), each after its own remote folder is created (a child file/folder
     * needs a real parent id to upload/create under) - **note this still nests one
     * [mapConcurrently] call per directory level, the same uncoordinated-semaphore shape flagged
     * (and fixed) in [deleteEntries]/[duplicateEntries]'s own Javadoc**; not fixed here since a
     * source archive's own directory tree is typically shallow/narrow relative to a whole cloud
     * account, but the same "too many concurrent streams" risk applies in principle to a large
     * enough archive.
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
     * Deletes every entry in [entries] - backs both [deleteSelected] (the toolbar's "Delete
     * selected") and a single entry's context-menu "Delete". A folder among [entries] is
     * cascade-deleted client-side (every contained file and nested folder first, then the folder
     * itself) since the server's `deleteFolder` 409s on a non-empty folder by design.
     *
     * Plans the whole batch first ([planDelete] - a listings-only, sequential walk, no deletes
     * yet) rather than deleting recursively as each folder is discovered. **Fixed a real bug
     * (2026-09-01): the previous recursive shape ran one [mapConcurrently] call (capped at
     * [ApiClient.DEFAULT_MAX_CONCURRENT_TRANSFERS]) *per folder level*, uncoordinated with every
     * sibling/ancestor call** - each nested call gets its own fresh semaphore, so the actual
     * number of simultaneously in-flight HTTP/2 requests multiplied with the tree's depth/breadth
     * instead of ever being capped at 8. Against the one shared HTTP/2 connection [ApiClient]
     * multiplexes every request over, this could exceed the server's/JDK's concurrent-stream
     * limit, surfacing as `"too many concurrent streams"` when deleting a folder tree of any real
     * size. Flattening first means exactly one [mapConcurrently] call ever deletes files, so the
     * 8-way cap is real; every folder is then deleted sequentially, deepest-first (see
     * [PlannedDelete.folderIdsDeepestFirst]), so a parent is never asked to delete before its own
     * (already-emptied) children have been - folder deletes are cheap and typically far fewer than
     * files, so they don't need their own concurrency.
     */
    fun deleteEntries(entries: List<Entry>) = run {
        val plan = this.planDelete(entries)
        plan.fileIds.mapConcurrently { fileId -> this.client.deleteFile(fileId) }
        plan.folderIdsDeepestFirst.forEach { folderId -> this.client.deleteFolder(folderId) }
        this.selected.removeAll(entries)
        this.refreshCurrentFolder()
    }

    /** The flattened result of [planDelete] - every file id to delete, and every folder id to delete afterward, ordered deepest-first (a folder's own children always precede it in this list). */
    private data class PlannedDelete(val fileIds: List<String>, val folderIdsDeepestFirst: List<String>)

    /** Recursively (sequentially - listings only, no deletes issued) flattens [entries] into one [PlannedDelete] - see [deleteEntries] for why this replaced a per-folder-level recursive delete. */
    private suspend fun planDelete(entries: List<Entry>): PlannedDelete {
        val fileIds = mutableListOf<String>()
        val folderIdsDeepestFirst = mutableListOf<String>()

        suspend fun walkFolder(folderId: String) {
            this.client.listFiles(folderId).forEach { file -> fileIds += file.fileId() }
            this.client.listFolders(folderId).forEach { subFolder -> walkFolder(subFolder.folderId()) }
            folderIdsDeepestFirst += folderId
        }

        for (entry in entries) {
            when (entry) {
                is Entry.FileEntry -> fileIds += entry.id
                is Entry.FolderEntry -> walkFolder(entry.id)
            }
        }
        return PlannedDelete(fileIds, folderIdsDeepestFirst)
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
                is Entry.FolderEntry -> items += this.planFolderDownload(entry.id, destinationDirectory.resolve(sanitizedForLocalPath(entry.name)))
            }
        }
        return items
    }

    private suspend fun planFolderDownload(folderId: String, destination: Path): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        this.client.listFiles(folderId).forEach { file -> items += DownloadItem(file.fileId(), file.fileName(), file.sizeBytes(), destination) }
        this.client.listFolders(folderId).forEach { subFolder -> items += this.planFolderDownload(subFolder.folderId(), destination.resolve(sanitizedForLocalPath(subFolder.name()))) }
        return items
    }

    /** Duplicates every currently-selected entry - see [duplicateEntries]. */
    fun duplicateSelected() = this.duplicateEntries(this.selected.toList())

    /**
     * Duplicates every entry in [entries] within the current folder - backs both the toolbar's
     * "Duplicate selected" and a single entry's context-menu "Duplicate". Each copy's name is
     * picked up front, sequentially (not inside the concurrent batch itself, to avoid two
     * duplicates racing to the same name), via [uniqueCopyName] against this folder's
     * already-loaded [folders]/[files] - Finder's own "name copy", "name copy 2", ... convention.
     *
     * Plans the whole batch first ([planFolderDuplicate] - creates every needed destination folder
     * sequentially, top-down, while flattening every file to duplicate into one
     * [PlannedDuplicateFile] list with its already-created target folder id known) rather than
     * duplicating recursively as each folder is discovered. **Fixed a real bug (2026-09-01): the
     * previous recursive shape (`duplicateFolderInto`) ran one [mapConcurrently] call over a
     * folder's own files, and a second, independent one over its subfolders, at *every* level of
     * recursion** - the identical uncoordinated-nested-semaphore shape that made
     * [deleteEntries] throw `"too many concurrent streams"` on a large-enough tree (see that
     * function's own Javadoc for the full mechanism); duplication is if anything worse per file,
     * since each one opens *two* concurrent HTTP/2 streams (a download and a re-upload) rather
     * than one. A file is duplicated by [duplicateFileInto] (download to a local temp file,
     * re-upload under the new name) - now only ever invoked from the one flat, capped
     * [mapConcurrently] batch below, never nested inside the folder-walking recursion itself.
     */
    fun duplicateEntries(entries: List<Entry>) = run {
        val existingNames = (this.folders.map { it.name() } + this.files.map { it.fileName() }).toMutableSet()
        val plannedCopies = entries.map { entry ->
            val copyName = uniqueCopyName(entry.name, existingNames)
            existingNames += copyName
            entry to copyName
        }
        val filePlans = mutableListOf<PlannedDuplicateFile>()
        for ((entry, copyName) in plannedCopies) {
            when (entry) {
                is Entry.FileEntry -> filePlans += PlannedDuplicateFile(entry.id, copyName, this.currentFolderId)
                is Entry.FolderEntry -> this.planFolderDuplicate(entry.id, copyName, this.currentFolderId, filePlans)
            }
        }
        filePlans.mapConcurrently { plan -> this.duplicateFileInto(plan.sourceFileId, plan.newName, plan.targetFolderId) }
        this.refreshCurrentFolder()
    }

    /** One file's worth of a planned duplicate - a source file id plus the name/already-resolved target folder its copy uploads under - see [planFolderDuplicate]. */
    private data class PlannedDuplicateFile(val sourceFileId: String, val newName: String, val targetFolderId: String?)

    /** Downloads [fileId] to a fresh, throwaway local temp directory and re-uploads it as [newName] into [targetFolderId], then cleans the temp file/directory up regardless of outcome. */
    private suspend fun duplicateFileInto(fileId: String, newName: String, targetFolderId: String?) {
        val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("cloud-driver-duplicate") }
        val tempFile = tempDir.resolve(sanitizedForLocalPath(newName))
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

    /**
     * Creates a new folder named [newName] under [targetParentFolderId], then recursively walks
     * [folderId]'s contents *sequentially* (listings + folder-creation only - cheap, single-call
     * operations - no file duplication issued yet), appending every contained file to [filePlans]
     * with its already-resolved target folder id. See [duplicateEntries] for why the actual
     * file-duplicating work (a download + a re-upload per file) only ever runs afterward, as one
     * flat, capped batch - never nested inside this recursive walk. Nested copies never need
     * [uniqueCopyName] themselves, since each destination folder starts out empty.
     */
    private suspend fun planFolderDuplicate(folderId: String, newName: String, targetParentFolderId: String?, filePlans: MutableList<PlannedDuplicateFile>) {
        val newFolder = this.client.createFolder(newName, targetParentFolderId)
        this.client.listFiles(folderId).forEach { file -> filePlans += PlannedDuplicateFile(file.fileId(), file.fileName(), newFolder.folderId()) }
        this.client.listFolders(folderId).forEach { subFolder -> this.planFolderDuplicate(subFolder.folderId(), subFolder.name(), newFolder.folderId(), filePlans) }
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

/**
 * [archiveFileName] with its extension stripped - `"test.zip"` -> `"test"` - used by
 * [AppViewModel.extractArchive] to name the folder an archive's contents are extracted into.
 */
private fun archiveBaseName(archiveFileName: String): String {
    val dotIndex = archiveFileName.lastIndexOf('.')
    val hasExtension = dotIndex > 0 && dotIndex < archiveFileName.length - 1
    return if (hasExtension) archiveFileName.substring(0, dotIndex) else archiveFileName
}

/**
 * Picks a folder name for [baseName] that doesn't collide with anything in [existingNames] -
 * `"test"`, then `"test 2"`, `"test 3"`, ... if `"test"` is already taken. Used by
 * [AppViewModel.extractArchive] for the new folder an archive is extracted into - deliberately
 * not [uniqueCopyName]'s `"... copy"`/`"... copy 2"` convention, since a re-extracted archive
 * isn't a duplicate of anything, it's the same archive's content landing in a fresh folder.
 */
private fun uniqueFolderName(baseName: String, existingNames: Set<String>): String {
    if (baseName !in existingNames) return baseName
    var suffix = 2
    while ("$baseName $suffix" in existingNames) {
        suffix++
    }
    return "$baseName $suffix"
}
