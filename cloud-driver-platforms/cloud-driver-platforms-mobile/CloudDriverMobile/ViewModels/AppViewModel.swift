import Foundation

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

/// Which screen `RootView` is currently showing - the mobile counterpart to
/// cloud-driver-platforms-desktop's `Screen.kt` sealed interface.
enum AppScreen: Equatable {
    case login
    case register
    case confirmRegistration(email: String)
    case resetPasswordRequest
    case resetPasswordConfirm(email: String)
    case twoFactor(pendingToken: String, email: String)
    case browser
}

/// All mutable app state plus every user-triggered action - the mobile counterpart to
/// cloud-driver-platforms-desktop's `AppViewModel.kt`. Deliberately not a 1:1 port: this first
/// pass covers auth (including two-factor login) and a single-folder-at-a-time file browser
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
            let outcome = try await self.client.login(email: email, password: password)
            if outcome.twoFactorRequired, let pendingToken = outcome.pendingToken {
                self.screen = .twoFactor(pendingToken: pendingToken, email: email)
            } else {
                self.currentUserEmail = email
                await self.sessionManager.persistCurrentSession()
                try await self.onAuthenticated()
            }
        }
    }

    func completeTwoFactorLogin(pendingToken: String, email: String, code: String) {
        run {
            _ = try await self.client.completeTwoFactorLogin(pendingToken: pendingToken, code: code)
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

    /// Permanently removes everything currently trashed, bypassing the retention window -
    /// irreversible, unlike a single `restoreFile`/`restoreFolder`. `TrashView` gates this behind
    /// its own confirmation dialog before calling it.
    func emptyTrash() {
        run {
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

    func createFolder(name: String) {
        run {
            _ = try await self.client.createFolder(name: name, parentFolderId: self.currentFolderId)
            try await self.refreshCurrentFolder()
        }
    }

    func deleteFile(_ file: StoredFileSummaryResponse) {
        run {
            try await self.client.deleteFile(fileId: file.fileId)
            try await self.refreshCurrentFolder()
        }
    }

    func deleteFolder(_ folder: FolderResponse) {
        run {
            try await self.client.deleteFolder(folderId: folder.folderId)
            try await self.refreshCurrentFolder()
        }
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
        let entries = Array(selectedEntries)
        run {
            var firstError: Error?
            for entry in entries {
                do {
                    switch entry {
                    case .file(let file):
                        try await self.client.deleteFile(fileId: file.fileId)
                    case .folder(let folder):
                        try await self.client.deleteFolder(folderId: folder.folderId)
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
            try await self.uploadFileStreaming(fileName: url.lastPathComponent, sourceURL: url)
            try await self.refreshCurrentFolder()
        }
    }

    /// Uploads `sourceURL` (a security-scoped URL from `.fileImporter`) as `fileName`, preferring
    /// the presigned direct-to-client path - `APIClient.uploadFileViaPresignedURL`, which streams
    /// straight from disk via `URLSession.upload(for:fromFile:)`, bypassing this app's own server
    /// for the data path entirely (see cloud-driver's `architecture/AWS_S3_IMPL.md`) - and
    /// transparently falling back to the ordinary server-mediated `uploadFile(fileName:data:folderId:)`
    /// the moment the server reports (`503`) it hasn't configured presigned transfer, so this works
    /// unchanged against an older or non-S3-configured deployment too.
    ///
    /// The security-scoped access brackets the *whole* operation, not just a read - unlike the
    /// fallback path (which still needs the full file in memory as `Data`), the presigned path
    /// streams directly from `sourceURL` for as long as the upload takes, so the scope must stay
    /// open for that entire duration.
    private func uploadFileStreaming(fileName: String, sourceURL: URL) async throws {
        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessing { sourceURL.stopAccessingSecurityScopedResource() } }
        do {
            _ = try await client.uploadFileViaPresignedURL(fileName: fileName, fileURL: sourceURL, folderId: currentFolderId)
        } catch APIError.server(let status, _) where status == 503 {
            let data = try await Task.detached(priority: .userInitiated) {
                try Data(contentsOf: sourceURL)
            }.value
            _ = try await client.uploadFile(fileName: fileName, data: data, folderId: currentFolderId)
        }
    }

    /// Downloads a file's content to a throwaway temp file and, once ready, surfaces it via
    /// `fileToShare` - the view presents the system share sheet from there, letting the user save
    /// it into Files, AirDrop it, etc.
    func download(_ file: StoredFileSummaryResponse) {
        run {
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "_" + file.fileName)
            try await self.downloadFileStreaming(fileId: file.fileId, destination: destination)
            self.fileToShare = IdentifiableURL(url: destination)
        }
    }

    /// Downloads `fileId` directly to `destination`, preferring the presigned direct-to-client
    /// path (`APIClient.downloadFileViaPresignedURL`, bypassing this app's own server for the data
    /// path entirely) and transparently falling back to the ordinary server-mediated
    /// `downloadFileContent(fileId:)` the moment the server reports (`503`) presigned transfer
    /// isn't available for this file/deployment.
    private func downloadFileStreaming(fileId: String, destination: URL) async throws {
        do {
            try await client.downloadFileViaPresignedURL(fileId: fileId, destination: destination)
        } catch APIError.server(let status, _) where status == 503 {
            let data = try await client.downloadFileContent(fileId: fileId)
            try await Task.detached(priority: .userInitiated) {
                try data.write(to: destination, options: .atomic)
            }.value
        }
    }
}
