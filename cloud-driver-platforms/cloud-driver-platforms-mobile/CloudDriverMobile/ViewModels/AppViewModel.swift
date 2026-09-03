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
        run {
            try await self.client.moveFile(fileId: file.fileId, folderId: folderId)
            try await self.refreshCurrentFolder()
        }
    }

    /// A folder move is a `PUT` (full replace), so this carries the folder's current `name`
    /// through unchanged - only `parentFolderId` actually changes. The server itself rejects
    /// moving a folder into itself or one of its own descendants (409); that failure surfaces
    /// through the ordinary shared error alert like any other action here.
    func moveFolder(_ folder: FolderResponse, toFolderId folderId: String?) {
        run {
            _ = try await self.client.updateFolder(folderId: folder.folderId, name: folder.name, parentFolderId: folderId)
            try await self.refreshCurrentFolder()
        }
    }

    /// `url` is a security-scoped URL handed back by `.fileImporter` - the actual (blocking) file
    /// read happens off the main actor via `readFileData`, so a large pick never stalls the UI.
    func uploadPickedFile(url: URL) {
        run {
            let data = try await Self.readFileData(at: url)
            _ = try await self.client.uploadFile(fileName: url.lastPathComponent, data: data, folderId: self.currentFolderId)
            try await self.refreshCurrentFolder()
        }
    }

    private static func readFileData(at url: URL) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            return try Data(contentsOf: url)
        }.value
    }

    /// Downloads a file's content to a throwaway temp file and, once ready, surfaces it via
    /// `fileToShare` - the view presents the system share sheet from there, letting the user save
    /// it into Files, AirDrop it, etc.
    func download(_ file: StoredFileSummaryResponse) {
        run {
            let data = try await self.client.downloadFileContent(fileId: file.fileId)
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + "_" + file.fileName)
            try await Task.detached(priority: .userInitiated) {
                try data.write(to: destination, options: .atomic)
            }.value
            self.fileToShare = IdentifiableURL(url: destination)
        }
    }
}
