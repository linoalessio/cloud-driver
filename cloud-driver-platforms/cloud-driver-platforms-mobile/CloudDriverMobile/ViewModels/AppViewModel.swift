import Foundation
import ZIPFoundation

/// One entry in the current folder-navigation trail - `folderId == nil` means the root ("Home").
/// A plain struct with its own `id` (rather than keying off `folderId`, which is `nil` at the
/// root) so `List`/`ForEach` can render the trail without special-casing the root entry.
struct Breadcrumb: Identifiable, Equatable {
    let id = UUID()
    let folderId: String?
    let name: String
}

/// A just-downloaded file's local temp-file location, wrapped so it can drive a SwiftUI
/// `.sheet(item:)` (which needs `Identifiable`, not just an optional `URL`).
struct IdentifiableURL: Identifiable {
    let id = UUID()
    let url: URL
}

/// A file or folder as a single, selectable browser entry - the one shared file/folder union type
/// backing both `FileBrowserView`'s multi-select set and the single-item payload its per-row "..."
/// menu already builds (a selection of one). Replaces what used to be two near-identical
/// single-item enums (`MoveTarget`/`ShareTarget`).
enum SelectableEntry: Identifiable, Hashable {
    case file(StoredFileSummaryResponse)
    case folder(FolderResponse)

    var id: String {
        switch self {
        case .file(let file): return "file-\(file.fileId)"
        case .folder(let folder): return "folder-\(folder.folderId)"
        }
    }

    var displayName: String {
        switch self {
        case .file(let file): return file.fileName
        case .folder(let folder): return folder.name
        }
    }

    /// The folder id this entry itself refers to, if it is a folder - `nil` for a file. Used by
    /// `MoveToFolderSheet` to exclude every selected folder from its own list of valid destinations.
    var ownFolderId: String? {
        if case .folder(let folder) = self { return folder.folderId }
        return nil
    }
}

/// Which kind of transfer `AppViewModel.transferProgress` is currently reporting on - the mobile
/// counterpart to cloud-driver-platforms-desktop's own `AppViewModel.kt#TransferKind`.
enum TransferKind: Equatable {
    case upload
    case download
    /// Covers both halves of `extractArchive` (downloading the archive, then re-uploading its
    /// extracted contents) under one label - from the user's perspective "unarchiving" is a
    /// single action, even though it's a download followed by a batch of uploads under the hood.
    case extract
    /// `emptyTrash` - a single `POST /trash/empty` call with no incremental byte signal of its own
    /// (unlike upload/download/extract, which stream), so `TransferProgressBar` renders this kind
    /// as an indeterminate spinner rather than a filling bar.
    case emptyTrash
}

/// A snapshot of an in-flight upload/download/extraction - `AppViewModel.transferProgress` is
/// `nil` whenever no transfer is running. Mirrors cloud-driver-platforms-desktop's own
/// `AppViewModel.kt#TransferProgress` (byte-level, not just a spinner), simplified for this
/// module's sequential (never concurrent) transfer model - `totalBytes`/`transferredBytes` are
/// summed across every item in a multi-item batch (`extractArchive`'s re-upload phase), not just
/// whichever single item happens to be in flight.
struct TransferProgress: Equatable {
    let kind: TransferKind
    let totalItems: Int
    let completedItems: Int
    let totalBytes: Int64
    let transferredBytes: Int64

    /// `1` if `totalBytes` is `0` (nothing to divide by, e.g. every item in the batch happened to
    /// be empty) - a full bar reads better than a division-by-zero for a batch with nothing left
    /// to transfer.
    var fraction: Double {
        guard totalBytes > 0 else { return 1 }
        return min(1, max(0, Double(transferredBytes) / Double(totalBytes)))
    }
}

/// Which screen `RootView` is currently showing - the mobile counterpart to
/// cloud-driver-platforms-desktop's `Screen.kt` sealed interface.
enum AppScreen: Equatable {
    case login
    case register
    case confirmRegistration(email: String)
    case resetPasswordRequest
    case resetPasswordConfirm(email: String)
    case browser
}

/// All mutable app state plus every user-triggered action - the mobile counterpart to
/// cloud-driver-platforms-desktop's `AppViewModel.kt`. Deliberately not a 1:1 port: this first
/// pass covers auth and a single-folder-at-a-time file browser
/// (list/upload/download/delete/create-folder/navigate) - sharing, trash, admin, live push, and
/// thumbnails/previews are not implemented yet. See this module's own README for the full list of
/// what's deferred and why.
@MainActor
final class AppViewModel: ObservableObject {
    @Published var screen: AppScreen = .login
    @Published var busy = false
    @Published var errorMessage: String?

    @Published var currentUserEmail: String?
    @Published var currentUserId: String?
    @Published var currentUserIsAdmin = false
    @Published var currentUserCreatedAtEpochMillis: Int64?
    @Published var currentUserMaxBytesToUpload: Int64?
    @Published var currentUserUploadedBytes: Int64?

    @Published var currentFolderId: String?
    @Published var breadcrumbs: [Breadcrumb] = [Breadcrumb(folderId: nil, name: "Home")]
    @Published var files: [StoredFileSummaryResponse] = []
    @Published var folders: [FolderResponse] = []

    /// Whether `FileBrowserView` is currently in multi-select mode - see `enterSelectionMode`/
    /// `exitSelectionMode`/`toggleSelection`/`selectAll`/`deleteSelected`/`moveEntries`/`shareEntries`.
    @Published var isSelecting = false
    @Published var selectedEntries: Set<SelectableEntry> = []

    @Published var sharedFiles: [SharedFileSummaryResponse] = []
    @Published var sharedFolders: [SharedFolderSummaryResponse] = []

    @Published var trashFiles: [TrashedFileSummaryResponse] = []
    @Published var trashFolders: [TrashedFolderSummaryResponse] = []

    @Published var pendingEmailChangeAddress: String?

    @Published var fileToShare: IdentifiableURL?
    /// A downloaded file's local temp-file location, ready to hand to `QLPreviewController` -
    /// `nil` whenever no preview is showing. See `previewFile(_:)`.
    @Published var previewURL: IdentifiableURL?

    /// Real, byte-level progress for whichever upload/download/extraction is currently running -
    /// `nil` whenever none is. Rendered by `RootView` as a bar visible across every tab, the same
    /// "owned above the tab content, so it survives which tab happens to be selected" placement
    /// `errorMessage`'s alert and `fileToShare`'s share sheet already use.
    @Published var transferProgress: TransferProgress?

    let client = APIClient.shared
    private lazy var sessionManager = SessionManager(client: client)

    // MARK: - Action dispatch

    /// Runs `action` on this view model's own (main-actor) task, toggling `busy` and surfacing a
    /// thrown error as `errorMessage` - the same "every action is fire-and-forget from a view's
    /// perspective" shape cloud-driver-platforms-desktop's `AppViewModel.run` uses. A no-op while
    /// an action is already in flight, so a rapid double-tap can't fire the same action twice.
    private func run(_ action: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true
        errorMessage = nil
        Task {
            do {
                try await action()
            } catch is CancellationError {
                // Cancelled deliberately (e.g. the view disappearing) - not a user-facing failure.
            } catch let error as APIError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
            busy = false
        }
    }

    // MARK: - Session lifecycle

    func tryRestoreSession() {
        run {
            let restored = await self.sessionManager.tryRestoreSession()
            if restored {
                try await self.onAuthenticated()
            }
        }
    }

    private func onAuthenticated() async throws {
        if let me = try? await client.me() {
            currentUserId = me.authUserId
            currentUserEmail = me.emailAddress
            currentUserIsAdmin = me.isAdmin
        }
        await refreshAccountInfo()
        screen = .browser
        currentFolderId = nil
        breadcrumbs = [Breadcrumb(folderId: nil, name: "Home")]
        try await refreshCurrentFolder()
    }

    /// `GET /cloudUsers/{id}` - the account's creation timestamp and storage quota/usage, backing
    /// `DashboardView`. Best-effort: a failure here never fails sign-in itself, the same
    /// "optional display value" treatment `onAuthenticated`'s own `client.me()` call already gets.
    private func refreshAccountInfo() async {
        guard let currentUserId else { return }
        if let cloudUser = try? await client.cloudUser(authUserId: currentUserId) {
            currentUserCreatedAtEpochMillis = cloudUser.timeStamp
            currentUserMaxBytesToUpload = cloudUser.maxBytesToUpload
            currentUserUploadedBytes = cloudUser.currentUploadedBytes
        }
    }

    func loadAccountInfo() {
        run { await self.refreshAccountInfo() }
    }

    func logout() {
        run {
            await self.sessionManager.clearSession()
            self.currentUserEmail = nil
            self.currentUserId = nil
            self.currentUserIsAdmin = false
            self.currentUserCreatedAtEpochMillis = nil
            self.currentUserMaxBytesToUpload = nil
            self.currentUserUploadedBytes = nil
            self.files = []
            self.folders = []
            self.currentFolderId = nil
            self.breadcrumbs = [Breadcrumb(folderId: nil, name: "Home")]
            self.sharedFiles = []
            self.sharedFolders = []
            self.trashFiles = []
            self.trashFolders = []
            self.pendingEmailChangeAddress = nil
            self.screen = .login
        }
    }

    // MARK: - Auth actions

    func login(email: String, password: String) {
        run {
            _ = try await self.client.login(email: email, password: password)
            self.currentUserEmail = email
            await self.sessionManager.persistCurrentSession()
            try await self.onAuthenticated()
        }
    }

    func register(email: String, password: String) {
        run {
            _ = try await self.client.register(email: email, password: password)
            self.screen = .confirmRegistration(email: email)
        }
    }

    func confirmRegistration(email: String, code: String) {
        run {
            _ = try await self.client.confirmRegistration(email: email, code: code)
            self.currentUserEmail = email
            await self.sessionManager.persistCurrentSession()
            try await self.onAuthenticated()
        }
    }

    func requestPasswordReset(email: String) {
        run {
            _ = try await self.client.requestPasswordReset(email: email)
            self.screen = .resetPasswordConfirm(email: email)
        }
    }

    func confirmPasswordReset(email: String, code: String, newPassword: String) {
        run {
            _ = try await self.client.confirmPasswordReset(email: email, code: code, newPassword: newPassword)
            self.currentUserEmail = email
            await self.sessionManager.persistCurrentSession()
            try await self.onAuthenticated()
        }
    }

    // MARK: - Account settings (Dashboard)

    /// Starts an e-mail change - see `ChangeEmailSheet`, driven by `pendingEmailChangeAddress`
    /// going non-`nil`, the same shape cloud-driver-platforms-desktop's own `ChangeEmailDialog`
    /// uses.
    func requestEmailChange(newEmailAddress: String) {
        run {
            _ = try await self.client.requestEmailChange(newEmailAddress: newEmailAddress)
            self.pendingEmailChangeAddress = newEmailAddress
        }
    }

    /// Applies a pending e-mail change - no fresh token is issued (see `APIClient.confirmEmailChange`),
    /// so only the locally-displayed `currentUserEmail` needs updating.
    func confirmEmailChange(code: String) {
        run {
            _ = try await self.client.confirmEmailChange(code: code)
            self.currentUserEmail = self.pendingEmailChangeAddress
            self.pendingEmailChangeAddress = nil
        }
    }

    /// Explicit cancel (not a failure) - only clears local/pending state; the still-unconfirmed
    /// `PendingEmailChange` row server-side simply expires unused after its own TTL.
    func cancelEmailChangeRequest() {
        pendingEmailChangeAddress = nil
    }

    // MARK: - File browser actions

    private func refreshCurrentFolder() async throws {
        async let filesResult = client.listFiles(folderId: currentFolderId)
        async let foldersResult = client.listFolders(parentFolderId: currentFolderId)
        files = try await filesResult
        folders = try await foldersResult
    }

    func loadCurrentFolder() {
        run { try await self.refreshCurrentFolder() }
    }

    // MARK: - Shared with me

    /// `GET /files/shared-with-me` + `GET /folders/shared-with-me` - what other accounts have
    /// shared directly with this one. Downloading a shared file reuses `download(_:)` unchanged
    /// (the server's `GET /files/{id}/content` route already honors a share the same way it
    /// honors ownership); browsing into a shared folder goes through `SharedFolderBrowserView`,
    /// which calls `APIClient.sharedFolderContents` directly rather than through this view model.
    func loadSharedWithMe() {
        run {
            async let filesResult = self.client.listSharedFilesWithMe()
            async let foldersResult = self.client.listSharedFoldersWithMe()
            self.sharedFiles = try await filesResult
            self.sharedFolders = try await foldersResult
        }
    }

    // MARK: - Trash

    /// `DELETE /files/{id}`/`DELETE /folders/{id}` (see `deleteFile`/`deleteFolder` above) are
    /// already soft deletes server-side - `GET /files/trash`/`GET /folders/trash` list what that
    /// put there, and `restoreFile`/`restoreFolder`/`emptyTrash` act on it.
    private func refreshTrash() async throws {
        async let filesResult = client.listDeletedFiles()
        async let foldersResult = client.listDeletedFolders()
        trashFiles = try await filesResult
        trashFolders = try await foldersResult
    }

    func loadTrash() {
        run { try await self.refreshTrash() }
    }

    func restoreFile(_ entry: TrashedFileSummaryResponse) {
        run {
            try await self.client.restoreFile(fileId: entry.file.fileId)
            try await self.refreshTrash()
        }
    }

    func restoreFolder(_ entry: TrashedFolderSummaryResponse) {
        run {
            try await self.client.restoreFolder(folderId: entry.folder.folderId)
            try await self.refreshTrash()
        }
    }

    /// Restores everything currently in the trash at once - the non-destructive counterpart to
    /// `emptyTrash()` below, added 2026-09-05 per Lino's own request ("Restore Trash" next to
    /// "Empty Trash", restoring the entire trash's contents). There is no server-side batch
    /// "restore everything" route (only the single-item `POST /files/{id}/restore`/
    /// `POST /folders/{id}/restore` `restoreFile`/`restoreFolder` already wrap) - this loops over
    /// every currently-listed trashed file and folder, attempting each one even if an earlier one
    /// failed, and only then rethrows the *first* failure encountered once every item has been
    /// attempted - the same batch convention this app's other multi-item actions
    /// (`AppViewModel`'s own `deleteEntries`-equivalents on desktop) already use, rather than
    /// aborting the whole restore the moment one item fails.
    func restoreAllTrash() {
        run {
            var firstError: Error?
            for entry in self.trashFiles {
                do {
                    try await self.client.restoreFile(fileId: entry.file.fileId)
                } catch {
                    if firstError == nil { firstError = error }
                }
            }
            for entry in self.trashFolders {
                do {
                    try await self.client.restoreFolder(folderId: entry.folder.folderId)
                } catch {
                    if firstError == nil { firstError = error }
                }
            }
            try await self.refreshTrash()
            if let firstError {
                throw firstError
            }
        }
    }

    /// Permanently removes everything currently trashed, bypassing the retention window -
    /// irreversible, unlike a single `restoreFile`/`restoreFolder`. `TrashView` gates this behind
    /// its own confirmation dialog before calling it.
    func emptyTrash() {
        run {
            self.transferProgress = TransferProgress(kind: .emptyTrash, totalItems: 1, completedItems: 0, totalBytes: 0, transferredBytes: 0)
            defer { self.transferProgress = nil }
            try await self.client.emptyTrash()
            try await self.refreshTrash()
        }
    }

    func openFolder(_ folder: FolderResponse) {
        run {
            self.currentFolderId = folder.folderId
            self.breadcrumbs.append(Breadcrumb(folderId: folder.folderId, name: folder.name))
            try await self.refreshCurrentFolder()
        }
    }

    func navigateToBreadcrumb(_ breadcrumb: Breadcrumb) {
        guard let index = breadcrumbs.firstIndex(of: breadcrumb) else { return }
        run {
            self.breadcrumbs.removeSubrange((index + 1)...)
            self.currentFolderId = breadcrumb.folderId
            try await self.refreshCurrentFolder()
        }
    }

    /// Resets the file browser straight back to the root ("Home") folder - called by `RootView`
    /// whenever the "Home" tab item is tapped (added 2026-09-05, per Lino's own request: tapping
    /// "Home" on the tab bar should always go directly back to the home path, not wherever folder
    /// navigation was last left inside it). A no-op if already at the root, so tapping "Home"
    /// while already there doesn't trigger a redundant reload.
    func goToHomeRoot() {
        guard currentFolderId != nil, let root = breadcrumbs.first else { return }
        navigateToBreadcrumb(root)
    }

    func createFolder(name: String) {
        run {
            _ = try await self.client.createFolder(name: name, parentFolderId: self.currentFolderId)
            try await self.refreshCurrentFolder()
        }
    }

    func deleteFile(_ file: StoredFileSummaryResponse) {
        deleteEntries([.file(file)])
    }

    func deleteFolder(_ folder: FolderResponse) {
        deleteEntries([.folder(folder)])
    }

    /// `folderId` `nil` moves the file back to the root - see `MoveToFolderSheet`.
    func moveFile(_ file: StoredFileSummaryResponse, toFolderId folderId: String?) {
        moveEntries([.file(file)], toFolderId: folderId)
    }

    /// A folder move is a `PUT` (full replace), so this carries the folder's current `name`
    /// through unchanged - only `parentFolderId` actually changes. The server itself rejects
    /// moving a folder into itself or one of its own descendants (409); that failure surfaces
    /// through the ordinary shared error alert like any other action here.
    func moveFolder(_ folder: FolderResponse, toFolderId folderId: String?) {
        moveEntries([.folder(folder)], toFolderId: folderId)
    }

    /// Renames `file` - added 2026-09-05, per Lino's own request ("Rename" to change the name of
    /// the folder/file, updated on the server). `PUT /files/{id}/rename`, a separate route/method
    /// from `moveFile` (renaming rewrites the file's own entity server-side, unlike a move).
    func renameFile(_ file: StoredFileSummaryResponse, to newName: String) {
        run {
            try await self.client.renameFile(fileId: file.fileId, newFileName: newName)
            try await self.refreshCurrentFolder()
        }
    }

    /// A folder rename is a `PUT` (full replace), so this carries the folder's current
    /// `parentFolderId` through unchanged - only `name` actually changes, the mirror image of
    /// `moveFolder`'s own "carries `name` through unchanged" comment above.
    func renameFolder(_ folder: FolderResponse, to newName: String) {
        run {
            _ = try await self.client.updateFolder(folderId: folder.folderId, name: newName, parentFolderId: folder.parentFolderId)
            try await self.refreshCurrentFolder()
        }
    }

    // MARK: - Multi-select

    func enterSelectionMode() {
        isSelecting = true
        selectedEntries = []
    }

    func exitSelectionMode() {
        isSelecting = false
        selectedEntries = []
    }

    func toggleSelection(_ entry: SelectableEntry) {
        if selectedEntries.contains(entry) {
            selectedEntries.remove(entry)
        } else {
            selectedEntries.insert(entry)
        }
    }

    /// Selects every currently-listed file and folder in `currentFolderId` - if everything is
    /// already selected, deselects instead (the standard "Select All" <-> "Deselect All" toggle).
    func selectAll() {
        let everything = Set(files.map(SelectableEntry.file) + folders.map(SelectableEntry.folder))
        selectedEntries = selectedEntries == everything ? [] : everything
    }

    /// Deletes every entry in `selectedEntries`, one `client.deleteFile`/`deleteFolder` call each -
    /// every item is attempted regardless of an earlier failure (e.g. a non-empty folder still
    /// 409s exactly like a single-item delete already does today), and the *first* failure
    /// encountered is surfaced only once every item has been attempted, matching
    /// cloud-driver-platforms-desktop's own batch-operation convention. Exits selection mode and
    /// refreshes the listing regardless of outcome, so successfully-deleted items disappear even
    /// if one item in the batch failed.
    func deleteSelected() {
        deleteEntries(Array(selectedEntries))
    }

    /// Every file id and every folder id (ordered deepest-first) a `deleteEntries` call needs to
    /// remove to fully delete the folders it was given - see `planDelete`'s own Javadoc for why a
    /// folder needs this at all, rather than a single `client.deleteFolder` call.
    private struct PlannedDelete {
        var fileIds: [String] = []
        var folderIdsDeepestFirst: [String] = []
    }

    /// Recursively lists `folderId`'s own contents and flattens them into one `PlannedDelete`,
    /// with every subfolder's own id appended *after* it has recursed into and planned that
    /// subfolder's contents - so `folderIdsDeepestFirst` ends up ordered leaf-first regardless of
    /// nesting depth, the order `deleteEntries` needs to actually delete them in (a folder can
    /// only be deleted once every file/subfolder placed directly inside it is already gone).
    private func planDeleteFolder(_ folderId: String) async throws -> PlannedDelete {
        async let filesResult = client.listFiles(folderId: folderId)
        async let foldersResult = client.listFolders(parentFolderId: folderId)
        let files = try await filesResult
        let subfolders = try await foldersResult

        var plan = PlannedDelete()
        plan.fileIds.append(contentsOf: files.map(\.fileId))
        for subfolder in subfolders {
            let childPlan = try await planDeleteFolder(subfolder.folderId)
            plan.fileIds.append(contentsOf: childPlan.fileIds)
            plan.folderIdsDeepestFirst.append(contentsOf: childPlan.folderIdsDeepestFirst)
        }
        plan.folderIdsDeepestFirst.append(folderId)
        return plan
    }

    /// Flattens `entries` (files delete as-is; each folder recurses via `planDeleteFolder`) into
    /// one combined `PlannedDelete` covering the whole batch - mirrors
    /// cloud-driver-platforms-desktop's own `AppViewModel.kt#planDelete`.
    private func planDelete(_ entries: [SelectableEntry]) async throws -> PlannedDelete {
        var combined = PlannedDelete()
        for entry in entries {
            switch entry {
            case .file(let file):
                combined.fileIds.append(file.fileId)
            case .folder(let folder):
                let plan = try await planDeleteFolder(folder.folderId)
                combined.fileIds.append(contentsOf: plan.fileIds)
                combined.folderIdsDeepestFirst.append(contentsOf: plan.folderIdsDeepestFirst)
            }
        }
        return combined
    }

    /// Deletes every entry in `entries` - backs `deleteFile`/`deleteFolder`/`deleteSelected` alike,
    /// so there is only one delete code path in this app.
    ///
    /// **Fixed a real bug (2026-09-04): deleting a non-empty folder failed with the server's raw,
    /// unfriendly error message, `"@CloudUserService.deleteFolder: <id> is not empty"`.** The
    /// server's own `deleteFolder` deliberately 409s on a non-empty folder - a folder is never
    /// deleted recursively server-side (see cloud-driver's own `CLAUDE.md`) - so a client that
    /// wants "delete this folder and everything inside it" has to empty it client-side first, the
    /// same cascade `cloud-driver-platforms-desktop`'s own `deleteEntries`/`planDelete` already
    /// perform; this app's mobile client never did, and just called `client.deleteFolder` directly,
    /// surfacing that raw message as-is through the shared error alert.
    ///
    /// `planDelete` first walks every folder in `entries` recursively (listings only - no deletes
    /// issued yet) into one flat, deepest-first plan; every file id is then deleted (attempting
    /// every one regardless of an earlier failure), then every folder id, deepest first, so a
    /// parent folder is never deleted before its own already-emptied children. The *first* failure
    /// encountered across the whole batch is surfaced only once every item has been attempted,
    /// matching cloud-driver-platforms-desktop's own batch-operation convention. Exits selection
    /// mode (a harmless no-op if it wasn't active - e.g. a single-item delete via a row's own menu)
    /// and refreshes the listing regardless of outcome, so successfully-deleted items disappear
    /// even if one item in the batch failed.
    private func deleteEntries(_ entries: [SelectableEntry]) {
        run {
            let plan = try await self.planDelete(entries)
            var firstError: Error?
            for fileId in plan.fileIds {
                do {
                    try await self.client.deleteFile(fileId: fileId)
                } catch {
                    if firstError == nil { firstError = error }
                }
            }
            for folderId in plan.folderIdsDeepestFirst {
                do {
                    try await self.client.deleteFolder(folderId: folderId)
                } catch {
                    if firstError == nil { firstError = error }
                }
            }
            self.exitSelectionMode()
            try await self.refreshCurrentFolder()
            if let firstError { throw firstError }
        }
    }

    /// Moves every entry in `entries` into `folderId` (`nil` = the root) - same
    /// attempt-everything/surface-the-first-failure shape as `deleteSelected`. Backs both the
    /// single-item `moveFile`/`moveFolder` (a one-element array) and `MoveToFolderSheet`'s
    /// multi-select confirm.
    func moveEntries(_ entries: [SelectableEntry], toFolderId folderId: String?) {
        run {
            var firstError: Error?
            for entry in entries {
                do {
                    switch entry {
                    case .file(let file):
                        try await self.client.moveFile(fileId: file.fileId, folderId: folderId)
                    case .folder(let folder):
                        _ = try await self.client.updateFolder(folderId: folder.folderId, name: folder.name, parentFolderId: folderId)
                    }
                } catch {
                    if firstError == nil { firstError = error }
                }
            }
            self.exitSelectionMode()
            try await self.refreshCurrentFolder()
            if let firstError { throw firstError }
        }
    }

    /// Shares every entry in `entries` with `granteeEmail` - a plain `async` helper, not wrapped
    /// in `run` itself, since `ShareSheet` (the only caller) is deliberately self-contained with
    /// its own local loading/error state rather than routed through this view model's global
    /// `busy` guard (see that sheet's own doc comment for why). Attempts every item regardless of
    /// an earlier failure, then rethrows the first one encountered - the caller decides how to
    /// surface it.
    func shareEntries(_ entries: [SelectableEntry], granteeEmail: String) async throws {
        var firstError: Error?
        for entry in entries {
            do {
                switch entry {
                case .file(let file):
                    try await client.shareFile(fileId: file.fileId, granteeEmail: granteeEmail)
                case .folder(let folder):
                    try await client.shareFolder(folderId: folder.folderId, granteeEmail: granteeEmail)
                }
            } catch {
                if firstError == nil { firstError = error }
            }
        }
        if let firstError { throw firstError }
    }

    /// `url` is a security-scoped URL handed back by `.fileImporter` - see `uploadFileStreaming`
    /// for how the actual (blocking or streaming) file access happens off the main actor.
    func uploadPickedFile(url: URL) {
        run {
            defer { self.transferProgress = nil }
            let totalBytes = self.fileSize(at: url)
            self.transferProgress = TransferProgress(kind: .upload, totalItems: 1, completedItems: 0, totalBytes: totalBytes, transferredBytes: 0)
            try await self.uploadFileStreaming(fileName: url.lastPathComponent, sourceURL: url, folderId: self.currentFolderId) { transferred, total in
                self.transferProgress = TransferProgress(kind: .upload, totalItems: 1, completedItems: 0, totalBytes: total > 0 ? total : totalBytes, transferredBytes: transferred)
            }
            try await self.refreshCurrentFolder()
        }
    }

    /// `url` is a security-scoped URL for a *directory*, handed back by `.fileImporter` with
    /// `allowedContentTypes: [.folder]`. The server has no folder-tree upload endpoint, only
    /// single-file `POST /files` - zips the folder client-side (the same "folder upload = zip"
    /// convention cloud-driver-platforms-desktop's own `FolderZipper.kt` uses) and uploads the
    /// result as `<folder name>.zip`.
    func uploadPickedFolder(url: URL) {
        run {
            defer { self.transferProgress = nil }
            try await self.zipAndUploadFolder(sourceURL: url)
            try await self.refreshCurrentFolder()
        }
    }

    /// Copies `sourceURL` (a directory, possibly security-scoped and/or backed by a cloud file
    /// provider - iCloud Drive, Google Drive, Dropbox, etc., all reachable via the `.fileImporter`
    /// folder picker) to `destinationURL`. `static` `nonisolated` (not just `static`) - `AppViewModel`
    /// is `@MainActor`, so a plain `static func` on it would still be main-actor-isolated by
    /// default, defeating the point of calling it from inside `Task.detached`'s own off-main-actor
    /// closure.
    ///
    /// **Fixed a real bug (2026-09-04), then fixed again more thoroughly (2026-09-05) after it
    /// recurred: zipping a picked folder failed with `"The operation couldn't be completed. Is a
    /// directory"`.** The first fix wrapped one single, top-level `FileManager.copyItem` call in
    /// `NSFileCoordinator.coordinate(readingItemAt:options:)` - correct for a folder that lives
    /// entirely on-device ("On My iPhone"), but not sufficient for a folder from a cloud-backed
    /// provider: a single coordinated read at the *root* only guarantees the OS has resolved the
    /// top-level item, not that every nested file several levels down is actually downloaded
    /// locally yet - a not-yet-downloaded nested item is still a scheme-valid but content-less
    /// placeholder, and `copyItem`'s own recursive walk can copy that placeholder as if it were a
    /// real, small file. ZIPFoundation later re-derives each entry's type from a fresh `lstat` on
    /// the *already-copied local* path - if a nested directory's placeholder didn't materialize as
    /// a real directory, this can still resurface the exact same `EISDIR`/`fopen` failure this
    /// class of bug already produced once.
    ///
    /// This second fix walks the tree itself, coordinating **every** descendant individually (not
    /// just the root) - `NSFileCoordinator` forces a file provider to materialize/download an item
    /// as part of coordinating read access to it, so per-item coordination alone (not a manual
    /// "wait for iCloud download" loop, which an earlier revision of this fix added and which
    /// turned out to be the wrong tool) is what actually guarantees every item is real, readable
    /// content by the time this app touches it.
    ///
    /// **A third, genuinely root-causing fix (2026-09-05, same day): switched the directory/file
    /// check itself from `URL.resourceValues(forKeys: [.isDirectoryKey])` to
    /// `FileManager.fileExists(atPath:isDirectory:)`.** The second fix above (per-item coordination)
    /// was the right idea but still didn't fully hold up - the *type check* it used to decide
    /// "recurse into this as a directory" vs. "copy this as a file" was itself unreliable:
    /// `.isDirectoryKey` is a `URLResourceValues` property the file provider itself has to populate,
    /// and several real-world providers (and even some iCloud Drive items reached through a
    /// `.fileImporter` folder pick) don't reliably report it for a nested item, coming back `nil`
    /// rather than `true`/`false`. Since the check only matched `== true`, a `nil` silently fell
    /// through to the **file** branch - meaning an actual subdirectory got a single, non-recursive
    /// `FileManager.copyItem` call instead of being recursed into, so *its own* children were only
    /// ever read via `copyItem`'s internal, uncoordinated traversal - the exact same
    /// under-materialized-nested-content gap the very first fix already failed to close, just one
    /// level deeper in the tree. `FileManager.fileExists(atPath:isDirectory:)` performs a plain
    /// POSIX `stat()` on the coordinated (and therefore already-materialized) path instead of
    /// asking the provider for a resource-value property - reliable regardless of what any given
    /// provider does or doesn't populate.
    /// **Diagnostic instrumentation (2026-09-05), added after three straight fix attempts all
    /// failed to actually resolve the recurring `"Is a directory"` error - each one was a
    /// plausible-sounding theory about *why* it might happen, but none was confirmed against the
    /// real folder that actually triggers it (not reproducible in this environment - no device/
    /// simulator UI interaction available here). Rather than guess a fourth mechanism blindly,
    /// every throw site in `copyItemRecursively`/`copyCoordinated` now wraps its error with the
    /// exact item path and phase ("Reading"/"Creating directory for"/"Listing"/"Coordinating
    /// access to"/"Copying") involved, and `zipAndUploadFolder` does the same around the `zipItem`
    /// call itself - so the *next* on-device attempt surfaces, in the error alert itself, exactly
    /// which item and which step actually failed, instead of the generic "Is a directory" message
    /// that could mean almost anything. Once that's known, apply the real, targeted fix instead of
    /// another guess.
    private struct FolderCopyDiagnosticError: Error, LocalizedError {
        let phase: String
        let path: String?
        let underlying: Error

        var errorDescription: String? {
            let nsError = underlying as NSError
            let failingPath = path ?? (nsError.userInfo[NSFilePathErrorKey] as? String)
            var description = "\(phase) failed"
            if let failingPath {
                description += " on \"\(failingPath)\""
            }
            description += ": [\(nsError.domain) \(nsError.code)] \(nsError.localizedDescription)"
            return description
        }
    }

    private nonisolated static func copyCoordinated(from sourceURL: URL, to destinationURL: URL) throws {
        var coordinatorError: NSError?
        var thrown: Error?
        NSFileCoordinator().coordinate(readingItemAt: sourceURL, options: [], error: &coordinatorError) { coordinatedURL in
            do {
                try copyItemRecursively(from: coordinatedURL, to: destinationURL)
            } catch {
                thrown = error
            }
        }
        if let coordinatorError {
            throw coordinatorError
        }
        if let thrown {
            throw thrown
        }
    }

    /// The actual per-item recursive walk `copyCoordinated` drives - a directory is recreated and
    /// then recursed into, with each child individually re-coordinated (`NSFileCoordinator` doesn't
    /// recursively coordinate a directory's descendants just because the directory itself was
    /// coordinated, and coordinating each item is also what forces its own materialization); a
    /// file is copied directly. See `copyCoordinated`'s own doc comment for why the type check is
    /// `FileManager.fileExists(atPath:isDirectory:)`, not a `URLResourceValues` lookup.
    private nonisolated static func copyItemRecursively(from sourceURL: URL, to destinationURL: URL) throws {
        var isDirectoryObjC: ObjCBool = false
        guard FileManager.default.fileExists(atPath: sourceURL.path, isDirectory: &isDirectoryObjC) else {
            throw FolderCopyDiagnosticError(
                phase: "Reading",
                path: sourceURL.path,
                underlying: CocoaError(.fileReadNoSuchFile, userInfo: [NSFilePathErrorKey: sourceURL.path])
            )
        }
        if isDirectoryObjC.boolValue {
            do {
                try FileManager.default.createDirectory(at: destinationURL, withIntermediateDirectories: true)
            } catch {
                throw FolderCopyDiagnosticError(phase: "Creating directory for", path: sourceURL.path, underlying: error)
            }
            let children: [URL]
            do {
                children = try FileManager.default.contentsOfDirectory(
                    at: sourceURL,
                    includingPropertiesForKeys: nil,
                    options: [.skipsHiddenFiles]
                )
            } catch {
                throw FolderCopyDiagnosticError(phase: "Listing", path: sourceURL.path, underlying: error)
            }
            for child in children {
                var childCoordinatorError: NSError?
                var childThrown: Error?
                NSFileCoordinator().coordinate(readingItemAt: child, options: [], error: &childCoordinatorError) { coordinatedChild in
                    do {
                        try copyItemRecursively(from: coordinatedChild, to: destinationURL.appendingPathComponent(child.lastPathComponent))
                    } catch {
                        childThrown = error
                    }
                }
                if let childCoordinatorError {
                    throw FolderCopyDiagnosticError(phase: "Coordinating access to", path: child.path, underlying: childCoordinatorError)
                }
                if let childThrown {
                    // Already a `FolderCopyDiagnosticError` from the deepest throw site that
                    // actually failed - rethrown as-is, not re-wrapped, so the reported path stays
                    // the real failing item, not this ancestor directory.
                    throw childThrown
                }
            }
        } else {
            do {
                try FileManager.default.copyItem(at: sourceURL, to: destinationURL)
            } catch {
                throw FolderCopyDiagnosticError(phase: "Copying", path: sourceURL.path, underlying: error)
            }
        }
    }

    /// Reads `url`'s file size, bracketed by security-scoped access the same way every other
    /// operation on a `.fileImporter`-provided URL is - harmless (a no-op bracket) for a plain
    /// local file this app created itself, per Apple's documented behavior for a non-scoped URL.
    private func fileSize(at url: URL) -> Int64 {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }

    /// Zips `sourceURL` (a security-scoped directory URL from `.fileImporter`) into a throwaway
    /// temp file via ZIPFoundation, then uploads it through the same `uploadFileStreaming` path a
    /// single file goes through - deleting every temp item afterward regardless of outcome. The
    /// zipping itself runs off the main actor via `Task.detached` (a real, blocking, synchronous
    /// disk operation), the same "blocking work never runs on the main actor" convention
    /// `downloadFileStreaming`'s own fallback branch already uses. No progress is reported during
    /// the zip step itself (a local, no-network operation with no natural byte-progress signal of
    /// its own) - `transferProgress` only starts once the real upload begins, against the now-known
    /// zip file size.
    ///
    /// **Fixed a real bug (2026-09-04): zipping a folder that contained nested subfolders failed
    /// with `"The operation couldn't be completed. Is a directory"`.** ZIPFoundation determines
    /// each entry's type (file/directory/symlink) via a raw `lstat()` call on its filesystem path,
    /// and silently falls back to treating it as a plain **file** if `lstat` fails for any reason -
    /// then calls `fopen()` on it, which throws exactly this error if the path turns out to still be
    /// a directory. `lstat`-ing a path nested *inside* a security-scoped folder URL (rather than the
    /// top-level picked URL itself) isn't reliable without `NSFileCoordinator` - the OS may not have
    /// fully vended access to everything beneath the root by the time raw POSIX calls reach it,
    /// which is exactly why this only surfaced for a folder containing subfolders, never a flat one.
    /// Fixed by first copying the whole picked folder into this app's own sandbox (via
    /// `NSFileCoordinator.coordinate(readingItemAt:options:)` wrapping `FileManager.copyItem`) and
    /// zipping *that* local copy instead - once the contents are plain local files with no security
    /// scope involved at all, ZIPFoundation's raw `lstat` calls behave reliably.
    private func zipAndUploadFolder(sourceURL: URL) async throws {
        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessing { sourceURL.stopAccessingSecurityScopedResource() } }

        let folderName = sourceURL.lastPathComponent
        let localCopyURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let zipURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip")
        defer {
            try? FileManager.default.removeItem(at: localCopyURL)
            try? FileManager.default.removeItem(at: zipURL)
        }

        try await Task.detached(priority: .userInitiated) {
            // Deliberately not re-wrapped here - `copyCoordinated`'s own throw sites already
            // attach the exact failing item's path via `FolderCopyDiagnosticError`; wrapping
            // again at this level would just lose that detail behind a generic "Copying" label
            // with no specific path of its own.
            try Self.copyCoordinated(from: sourceURL, to: localCopyURL)
            do {
                try FileManager().zipItem(at: localCopyURL, to: zipURL, shouldKeepParent: false, compressionMethod: .deflate)
            } catch {
                // ZIPFoundation's own thrown errors already carry the failing item's path via
                // `NSFilePathErrorKey` (see `POSIXError(_:path:)` in its source) - `path: nil`
                // lets `FolderCopyDiagnosticError` fall back to reading that straight off the
                // underlying error instead of this app having to know it independently.
                throw FolderCopyDiagnosticError(phase: "Zipping", path: nil, underlying: error)
            }
        }.value

        let totalBytes = fileSize(at: zipURL)
        self.transferProgress = TransferProgress(kind: .upload, totalItems: 1, completedItems: 0, totalBytes: totalBytes, transferredBytes: 0)
        try await self.uploadFileStreaming(fileName: "\(folderName).zip", sourceURL: zipURL, folderId: self.currentFolderId) { transferred, total in
            self.transferProgress = TransferProgress(kind: .upload, totalItems: 1, completedItems: 0, totalBytes: total > 0 ? total : totalBytes, transferredBytes: transferred)
        }
    }

    /// Uploads `sourceURL` as `fileName` into `folderId` (`nil` for the root), preferring the
    /// presigned direct-to-client path - `APIClient.uploadFileViaPresignedURL`, which streams
    /// straight from disk via `URLSession.upload(for:fromFile:)`, bypassing this app's own server
    /// for the data path entirely (see cloud-driver's `architecture/AWS_S3_IMPL.md`) - and
    /// transparently falling back to the ordinary server-mediated `uploadFile(fileName:data:folderId:)`
    /// the moment the server reports (`503`) it hasn't configured presigned transfer, so this works
    /// unchanged against an older or non-S3-configured deployment too.
    ///
    /// The security-scoped access brackets the *whole* operation, not just a read - unlike the
    /// fallback path (which still needs the full file in memory as `Data`), the presigned path
    /// streams directly from `sourceURL` for as long as the upload takes, so the scope must stay
    /// open for that entire duration. Harmless to call on a plain, non-security-scoped local file
    /// (e.g. a temp zip this app created itself, or an extracted archive entry) -
    /// `startAccessingSecurityScopedResource()` simply returns `false` for those, per Apple's own
    /// documented behavior, and the `defer` below then no-ops.
    ///
    /// `onProgress(bytesTransferred, totalBytes)` is called repeatedly on the presigned path (real,
    /// byte-level progress from `URLSessionTaskDelegate`, already hopped onto the main actor by
    /// `APIClient`'s own `ProgressForwardingDelegate`) and exactly once, with the full size both
    /// times, on the fallback path - which has no incremental signal of its own (a single, whole-body
    /// `Data` request), so this is the best this app can report there without much more plumbing.
    private func uploadFileStreaming(fileName: String, sourceURL: URL, folderId: String?, onProgress: (@MainActor (Int64, Int64) -> Void)? = nil) async throws {
        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessing { sourceURL.stopAccessingSecurityScopedResource() } }
        do {
            _ = try await client.uploadFileViaPresignedURL(fileName: fileName, fileURL: sourceURL, folderId: folderId, onProgress: onProgress)
        } catch APIError.server(let status, _) where status == 503 {
            let data = try await Task.detached(priority: .userInitiated) {
                try Data(contentsOf: sourceURL)
            }.value
            _ = try await client.uploadFile(fileName: fileName, data: data, folderId: folderId)
            onProgress?(Int64(data.count), Int64(data.count))
        }
    }

    /// Uploads a freshly-scanned document (`DocumentScannerView`, VisionKit's built-in document
    /// camera combined into one PDF via PDFKit) into the current folder - added 2026-09-05, per
    /// Lino's own request. Named `"<UUID>_<timestamp-in-milliseconds>.pdf"` exactly as specified -
    /// a scan has no original file name of its own to preserve the way a picked file/folder does.
    func uploadScannedDocument(pdfData: Data) {
        guard !pdfData.isEmpty else {
            errorMessage = "The scan produced no pages to upload."
            return
        }
        run {
            defer { self.transferProgress = nil }
            let fileName = "\(UUID().uuidString)_\(Int(Date().timeIntervalSince1970 * 1000)).pdf"
            let sourceURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pdf")
            try await Task.detached(priority: .userInitiated) {
                try pdfData.write(to: sourceURL, options: .atomic)
            }.value
            defer { try? FileManager.default.removeItem(at: sourceURL) }

            let totalBytes = Int64(pdfData.count)
            self.transferProgress = TransferProgress(kind: .upload, totalItems: 1, completedItems: 0, totalBytes: totalBytes, transferredBytes: 0)
            try await self.uploadFileStreaming(fileName: fileName, sourceURL: sourceURL, folderId: self.currentFolderId) { transferred, total in
                self.transferProgress = TransferProgress(kind: .upload, totalItems: 1, completedItems: 0, totalBytes: total > 0 ? total : totalBytes, transferredBytes: transferred)
            }
            try await self.refreshCurrentFolder()
        }
    }

    /// Downloads a file's content to a throwaway temp file and, once ready, surfaces it via
    /// `fileToShare` - the view presents the system share sheet from there, letting the user save
    /// it into Files, AirDrop it, etc.
    func download(_ file: StoredFileSummaryResponse) {
        run {
            defer { self.transferProgress = nil }
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "_" + sanitizedForLocalPath(file.fileName))
            self.transferProgress = TransferProgress(kind: .download, totalItems: 1, completedItems: 0, totalBytes: file.sizeBytes, transferredBytes: 0)
            try await self.downloadFileStreaming(fileId: file.fileId, destination: destination) { transferred, total in
                self.transferProgress = TransferProgress(kind: .download, totalItems: 1, completedItems: 0, totalBytes: total > 0 ? total : file.sizeBytes, transferredBytes: transferred)
            }
            self.fileToShare = IdentifiableURL(url: destination)
        }
    }

    /// Previews `file` in-app via `QLPreviewController` (`FilePreviewView`, presented by `RootView`
    /// from `previewURL`) instead of the system share sheet `download(_:)` opens - triggered by a
    /// single tap on a file row in `FileBrowserView` (added 2026-09-05, per Lino's own request).
    /// Only offered for the same content-type scope cloud-driver-platforms-desktop's own preview
    /// dialog supports (`PreviewSupport.swift`: text/PDF/DOCX) - QuickLook can render plenty of
    /// other formats too (images, in particular), but staying within this scope keeps both clients'
    /// "what's previewable" policy consistent, and avoids downloading a large image/video/archive
    /// just to preview it when `download(_:)` (via the row's "..." menu) already covers that case.
    /// Checked, and the file's already-known `sizeBytes` checked against the matching size cap,
    /// **before any network call** - an unsupported or oversized file reports through
    /// `errorMessage` immediately rather than being downloaded first only to be rejected.
    func previewFile(_ file: StoredFileSummaryResponse) {
        let kind = previewKind(for: file.contentType)
        guard kind != .none else {
            errorMessage = "Preview isn't available for this file type - download it to view it."
            return
        }
        if let sizeLimit = maxPreviewSourceBytes(for: kind), file.sizeBytes > sizeLimit {
            errorMessage = "This file is too large to preview here - download it to view it."
            return
        }
        run {
            defer { self.transferProgress = nil }
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "_" + sanitizedForLocalPath(file.fileName))
            self.transferProgress = TransferProgress(kind: .download, totalItems: 1, completedItems: 0, totalBytes: file.sizeBytes, transferredBytes: 0)
            try await self.downloadFileStreaming(fileId: file.fileId, destination: destination) { transferred, total in
                self.transferProgress = TransferProgress(kind: .download, totalItems: 1, completedItems: 0, totalBytes: total > 0 ? total : file.sizeBytes, transferredBytes: transferred)
            }
            self.previewURL = IdentifiableURL(url: destination)
        }
    }

    /// Downloads `fileId` directly to `destination`, preferring the presigned direct-to-client
    /// path (`APIClient.downloadFileViaPresignedURL`, bypassing this app's own server for the data
    /// path entirely) and transparently falling back to the ordinary server-mediated
    /// `downloadFileContent(fileId:)` the moment the server reports (`503`) presigned transfer
    /// isn't available for this file/deployment. `onProgress` has the same "real progress on the
    /// presigned path, one final call on the fallback path" contract `uploadFileStreaming` documents.
    private func downloadFileStreaming(fileId: String, destination: URL, onProgress: (@MainActor (Int64, Int64) -> Void)? = nil) async throws {
        do {
            try await client.downloadFileViaPresignedURL(fileId: fileId, destination: destination, onProgress: onProgress)
        } catch APIError.server(let status, _) where status == 503 {
            let data = try await client.downloadFileContent(fileId: fileId)
            try await Task.detached(priority: .userInitiated) {
                try data.write(to: destination, options: .atomic)
            }.value
            onProgress?(Int64(data.count), Int64(data.count))
        }
    }

    // MARK: - Archive extraction

    /// Double-tapping/extracting a ZIP archive on cloud-driver-platforms-desktop unarchives it
    /// into a **new destination folder** created for this extraction, named after the archive
    /// (extension stripped, e.g. `test.zip` -> `test`) - never directly into the current folder.
    /// This mirrors that exact behavior: downloads `file`, extracts it locally via ZIPFoundation,
    /// creates a destination folder (disambiguated against the current folder's already-loaded
    /// subfolder names via `uniqueFolderName` - `"test"`, then `"test 2"`, ... if `"test"` is
    /// already taken), and recreates the extracted contents - files and nested folders alike -
    /// inside it. The archive file itself is left in place, per this app's "never silently delete
    /// something the user didn't ask to delete" convention.
    func extractArchive(_ file: StoredFileSummaryResponse) {
        run {
            defer { self.transferProgress = nil }
            try await self.downloadAndExtractArchive(file: file)
            try await self.refreshCurrentFolder()
        }
    }

    /// One file discovered while walking an extracted archive's local directory tree, paired with
    /// its remote destination folder id and known size - the "plan" half of the re-upload phase,
    /// letting `downloadAndExtractArchive` show one continuous, accurate progress bar across the
    /// whole batch instead of restarting per file. Mirrors cloud-driver-platforms-desktop's own
    /// `AppViewModel.kt#PlannedUpload`.
    private struct PlannedUpload {
        let localURL: URL
        let remoteFolderId: String?
        let sizeBytes: Int64
    }

    /// Downloads `file` to a throwaway temp file (reporting progress against its own known size),
    /// extracts it (via ZIPFoundation, off the main actor - a real, blocking, synchronous disk
    /// operation) into a sibling temp directory, creates the destination folder, then walks the
    /// extracted tree via `planDirectoryTree` (creating every needed remote subfolder up front and
    /// summing every file's size) so the re-upload phase's own total is known before it starts -
    /// the same "plan first, so progress never has to guess a total up front" shape
    /// cloud-driver-platforms-desktop's own `planAndCreateDirectoryTree` uses. The whole temp
    /// directory (archive + extracted contents) is removed afterward regardless of outcome.
    private func downloadAndExtractArchive(file: StoredFileSummaryResponse) async throws {
        let tempDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let archiveURL = tempDirectory.appendingPathComponent(sanitizedForLocalPath(file.fileName))
        let extractedDirectory = tempDirectory.appendingPathComponent("extracted")
        defer { try? FileManager.default.removeItem(at: tempDirectory) }

        try FileManager.default.createDirectory(at: extractedDirectory, withIntermediateDirectories: true)

        self.transferProgress = TransferProgress(kind: .extract, totalItems: 1, completedItems: 0, totalBytes: file.sizeBytes, transferredBytes: 0)
        try await self.downloadFileStreaming(fileId: file.fileId, destination: archiveURL) { transferred, total in
            self.transferProgress = TransferProgress(kind: .extract, totalItems: 1, completedItems: 0, totalBytes: total > 0 ? total : file.sizeBytes, transferredBytes: transferred)
        }

        try await Task.detached(priority: .userInitiated) {
            try FileManager().unzipItem(at: archiveURL, to: extractedDirectory)
        }.value

        let existingFolderNames = Set(self.folders.map(\.name))
        let destinationFolderName = uniqueFolderName(archiveBaseName(file.fileName), existingNames: existingFolderNames)
        let destinationFolder = try await self.client.createFolder(name: destinationFolderName, parentFolderId: self.currentFolderId)

        let plans = try await planDirectoryTree(localDirectory: extractedDirectory, remoteParentFolderId: destinationFolder.folderId)
        let uploadTotalBytes = plans.reduce(Int64(0)) { $0 + $1.sizeBytes }
        let overallTotalBytes = file.sizeBytes + uploadTotalBytes
        self.transferProgress = TransferProgress(
            kind: .extract, totalItems: plans.count + 1, completedItems: 1,
            totalBytes: overallTotalBytes, transferredBytes: file.sizeBytes
        )
        try await uploadPlannedFiles(plans, alreadyTransferredBytes: file.sizeBytes, totalBytes: overallTotalBytes)
    }

    /// Recursively creates every subfolder directly/transitively inside `localDirectory` under
    /// `remoteParentFolderId` (`nil` = root), and flattens every plain file into one
    /// `PlannedUpload` list with its size already known (a local file-size read) - mirrors
    /// cloud-driver-platforms-desktop's own `planAndCreateDirectoryTree`. Deliberately sequential
    /// (one entry at a time), not a capped-concurrency batch - this app has no existing precedent
    /// for concurrent transfer batches, and a source archive's own tree is typically shallow/narrow
    /// enough that sequential planning is simple and safe rather than risking the "too many
    /// concurrent streams" issue a wide/deep tree could otherwise trigger.
    private func planDirectoryTree(localDirectory: URL, remoteParentFolderId: String?) async throws -> [PlannedUpload] {
        var plans: [PlannedUpload] = []
        let entries = try FileManager.default.contentsOfDirectory(at: localDirectory, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey])
        for entry in entries {
            let resourceValues = try? entry.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            if resourceValues?.isDirectory == true {
                let remoteFolder = try await client.createFolder(name: entry.lastPathComponent, parentFolderId: remoteParentFolderId)
                plans += try await planDirectoryTree(localDirectory: entry, remoteParentFolderId: remoteFolder.folderId)
            } else {
                let sizeBytes = Int64(resourceValues?.fileSize ?? 0)
                plans.append(PlannedUpload(localURL: entry, remoteFolderId: remoteParentFolderId, sizeBytes: sizeBytes))
            }
        }
        return plans
    }

    /// Uploads every planned file sequentially, aggregating byte-level progress across the whole
    /// batch (`alreadyTransferredBytes` is the archive download's own size, already "spent" against
    /// `totalBytes` before this phase starts) into one continuous `transferProgress` update per
    /// callback - so the bar never jumps backwards between files, and reads as one unbroken
    /// operation from the download through the last re-uploaded file.
    private func uploadPlannedFiles(_ plans: [PlannedUpload], alreadyTransferredBytes: Int64, totalBytes: Int64) async throws {
        var completedBytes = alreadyTransferredBytes
        for (index, plan) in plans.enumerated() {
            let baseline = completedBytes
            try await uploadFileStreaming(fileName: plan.localURL.lastPathComponent, sourceURL: plan.localURL, folderId: plan.remoteFolderId) { transferred, _ in
                self.transferProgress = TransferProgress(
                    kind: .extract, totalItems: plans.count + 1, completedItems: index + 1,
                    totalBytes: totalBytes, transferredBytes: baseline + transferred
                )
            }
            completedBytes += plan.sizeBytes
        }
    }
}
