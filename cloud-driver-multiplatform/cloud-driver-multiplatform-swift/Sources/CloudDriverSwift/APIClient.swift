import CryptoKit
import Foundation

/// Failures surfaced from `APIClient`. `errorDescription` is what view models put directly into
/// `AppViewModel.errorMessage` for display.
public enum APIError: Error, LocalizedError {
    case network(Error)
    case server(status: Int, message: String)
    case decoding(Error)
    case notAuthenticated
    /// Thrown by `replaceFileContent` when an `expectedUpdatedAtEpochMillis` precondition no
    /// longer matches - the server left the original file untouched and instead persisted the
    /// caller's own bytes as a new "conflicted copy" file, returned here rather than folded into
    /// the generic `.server` case, since a caller needs the new file's own id/name to navigate to it.
    case syncConflict(conflictedCopy: StoredFileSummaryResponse)

    public var errorDescription: String? {
        switch self {
        case .network(let error):
            return error.localizedDescription
        case .server(_, let message):
            return message
        case .decoding(let error):
            return "Failed to read the server's response: \(error.localizedDescription)"
        case .notAuthenticated:
            return "You're signed out - please sign in again."
        case .syncConflict(let conflictedCopy):
            return "Someone else changed this file first - your edit was saved as \"\(conflictedCopy.fileName)\"."
        }
    }
}

private extension CharacterSet {
    /// Deliberately conservative: everything outside alphanumerics/`-._~` is percent-encoded,
    /// including spaces (as `%20`, never `+`) - avoids the `+`-means-space ambiguity between
    /// strict percent-encoding and form-encoding entirely.
    static let queryValueAllowed: CharacterSet = {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return allowed
    }()
}

private extension String {
    func queryEncoded() -> String {
        addingPercentEncoding(withAllowedCharacters: .queryValueAllowed) ?? self
    }
}

/// A per-request `URLSessionTaskDelegate` forwarding byte-level upload/download progress to
/// `onProgress(bytesTransferred, totalBytes)` - passed to `session.upload(for:fromFile:delegate:)`/
/// `session.download(for:delegate:)` (the task-scoped delegate overloads Apple added alongside
/// async/await, distinct from `URLSession`'s own session-wide delegate, which this app doesn't
/// use). `onProgress` is `@MainActor`-isolated (the type callers - `AppViewModel`, itself
/// `@MainActor` - actually want to update `@Published` state directly from), but this delegate's
/// own callbacks arrive on an arbitrary background queue, so each one hops via `Task { @MainActor in }`
/// before invoking it.
private final class ProgressForwardingDelegate: NSObject, URLSessionTaskDelegate {
    private let onProgress: @MainActor (Int64, Int64) -> Void

    init(onProgress: @escaping @MainActor (Int64, Int64) -> Void) {
        self.onProgress = onProgress
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        Task { @MainActor [onProgress] in
            onProgress(totalBytesSent, totalBytesExpectedToSend)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        Task { @MainActor [onProgress] in
            onProgress(totalBytesWritten, totalBytesExpectedToWrite)
        }
    }
}

/// A plain `URLSession`-based client for `cloud-driver`'s JWT-authenticated REST API - the iOS
/// counterpart to `cloud-driver-maven`'s Java `ApiClient`. An `actor` rather than a class
/// with manual locking, so every token read/write is already serialized without extra ceremony.
///
/// Every authenticated call transparently retries once after a `401` by exchanging the held
/// refresh token first (`execute`'s own retry branch) - mirroring the same refresh-token contract
/// the Java client and the server both implement. A caller only ever sees the original `401` if
/// that retry also fails.
///
/// Lives in the `CloudDriverSwift` package (extracted out of `cloud-driver-platforms-mobile`'s own
/// "Networking" folder, 2026-09-07, so that app can stay GUI-only) - every member an app target
/// actually calls is `public`; everything else (request-building/execution plumbing, the token
/// accessors `SessionManager` reads directly since it lives in this same package/module) stays at
/// the default `internal`/`private` access it always had.
public actor APIClient {

    /// The one deployment this app talks to - see cloud-driver-platforms-desktop's `Main.kt` for
    /// the equivalent hardcoded constant on the desktop client. Change and rebuild to point this
    /// app at a different server.
    public static let shared = APIClient(baseURL: URL(string: "https://api.cloud-driver.de")!)

    private let baseURL: URL
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private(set) var accessToken: String?
    private(set) var refreshToken: String?

    /// Notified with the fresh (access, refresh) pair every time `issueTokens(from:)` sets one -
    /// covers an explicit login/register-confirm/reset-confirm/refresh call *and* the transparent
    /// 401-retry-triggered refresh inside `execute`, since that path also funnels through
    /// `issueTokens`. `SessionManager` installs this once, before this actor's tokens can change
    /// for the first time, so a silent refresh is never left unpersisted in the Keychain - see
    /// `SessionManager.installTokenRotationHandlerIfNeeded`.
    private var onTokensRotated: (@Sendable (String, String) -> Void)?

    var isAuthenticated: Bool { accessToken != nil }

    func setTokensRotatedHandler(_ handler: @escaping @Sendable (String, String) -> Void) {
        self.onTokensRotated = handler
    }

    public init(baseURL: URL) {
        self.baseURL = baseURL
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 600
        self.session = URLSession(configuration: configuration)
    }

    func restoreTokens(access: String, refresh: String) {
        self.accessToken = access
        self.refreshToken = refresh
    }

    func clearTokens() {
        self.accessToken = nil
        self.refreshToken = nil
    }

    // MARK: - Auth

    public func login(email: String, password: String) async throws -> AuthResponse {
        let request = try jsonRequest("/auth/login", method: "POST", body: AuthRequest(username: email, password: password), authenticated: false)
        return try await issueTokens(from: request)
    }

    public func register(email: String, password: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/register", method: "POST", body: AuthRequest(username: email, password: password), authenticated: false)
        let (data, _) = try await execute(request, allowRefreshRetry: false)
        return try decode(data)
    }

    public func confirmRegistration(email: String, code: String) async throws -> AuthResponse {
        let request = try jsonRequest("/auth/register/confirm", method: "POST", body: ConfirmRegistrationRequest(username: email, code: code), authenticated: false)
        return try await issueTokens(from: request)
    }

    public func requestPasswordReset(email: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/reset-password", method: "POST", body: RequestPasswordResetRequest(username: email), authenticated: false)
        let (data, _) = try await execute(request, allowRefreshRetry: false)
        return try decode(data)
    }

    public func confirmPasswordReset(email: String, code: String, newPassword: String) async throws -> AuthResponse {
        let request = try jsonRequest("/auth/reset-password/confirm", method: "POST", body: ConfirmPasswordResetRequest(username: email, code: code, newPassword: newPassword), authenticated: false)
        return try await issueTokens(from: request)
    }

    @discardableResult
    func refresh() async throws -> AuthResponse {
        guard let refreshToken else { throw APIError.notAuthenticated }
        let request = try jsonRequest("/auth/refresh", method: "POST", body: RefreshRequest(refreshToken: refreshToken), authenticated: false)
        return try await issueTokens(from: request, allowRefreshRetry: false)
    }

    func logout() async {
        if let refreshToken, let request = try? jsonRequest("/auth/logout", method: "POST", body: RefreshRequest(refreshToken: refreshToken), authenticated: false) {
            _ = try? await execute(request, allowRefreshRetry: false)
        }
        self.accessToken = nil
        self.refreshToken = nil
    }

    public func me() async throws -> MeResponse {
        let request = plainRequest("/auth/me", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func cloudUser(authUserId: String) async throws -> CloudUserResponse {
        let request = plainRequest("/cloudUsers/\(authUserId)", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Answers "does any account exist under this address" - not scoped to the caller's own
    /// account. A missing/blank `email` isn't possible to construct here since `email` is required.
    public func checkCloudUserExists(email: String) async throws -> Bool {
        let request = plainRequest("/cloudUsers/exists?email=\(email.queryEncoded())", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        let response: EmailExistsResponse = try decode(data)
        return response.exists
    }

    /// Starts an e-mail change for the caller's own (already-authenticated) account - `newEmail`
    /// isn't live yet, only `confirmEmailChange` actually applies it.
    public func requestEmailChange(newEmailAddress: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/change-email", method: "POST", body: ChangeEmailRequest(newEmail: newEmailAddress), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Applies a pending e-mail change - does **not** issue a fresh token (a JWT's subject is the
    /// account id, never its e-mail, so the caller's already-held token stays valid unchanged).
    public func confirmEmailChange(code: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/change-email/confirm", method: "POST", body: ConfirmChangeEmailRequest(code: code), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Files

    public func listFiles(folderId: String?) async throws -> [StoredFileSummaryResponse] {
        let scope = (folderId ?? "root").queryEncoded()
        let request = plainRequest("/files?folderId=\(scope)", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Cursor-paginated counterpart to `listFiles(folderId:)` - opts the server into the
    /// `{"items", "nextCursor"}` envelope by sending `?limit=`, instead of every file in
    /// `folderId` in one unpaginated array. Added 2026-09-05: before this, every folder view
    /// fetched a folder's *entire* contents in one response, even though the server route (and
    /// the desktop client) already supported paging - a real cost for a folder with thousands of
    /// files. `folderId` maps `nil` to `"root"`, matching `listFiles(folderId:)`'s own convention
    /// (not `cloud-driver-maven`'s JVM client, which omits the parameter entirely on
    /// `nil` - a different, unscoped-listing meaning this app's folder browser never needs).
    ///
    /// - Parameters:
    ///   - cursor: the previous page's `Page.nextCursor`, or `nil` for the first page.
    ///   - limit: the maximum number of entries to return; must be positive.
    public func listFilesPage(folderId: String?, cursor: String?, limit: Int) async throws -> Page<StoredFileSummaryResponse> {
        var path = "/files?limit=\(limit)&folderId=\((folderId ?? "root").queryEncoded())"
        if let cursor {
            path += "&cursor=\(cursor.queryEncoded())"
        }
        let request = plainRequest(path, method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func uploadFile(fileName: String, data: Data, folderId: String?) async throws -> StoredFileSummaryResponse {
        var path = "/files?fileName=\(fileName.queryEncoded())"
        if let folderId {
            path += "&folderId=\(folderId.queryEncoded())"
        }
        var request = plainRequest(path, method: "POST", authenticated: true)
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.httpBody = data
        let (responseData, _) = try await execute(request)
        return try decode(responseData)
    }

    public func downloadFileContent(fileId: String) async throws -> Data {
        let request = plainRequest("/files/\(fileId)/content", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return data
    }

    public func deleteFile(fileId: String) async throws {
        let request = plainRequest("/files/\(fileId)", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    public func moveFile(fileId: String, folderId: String?) async throws {
        let request = try jsonRequest("/files/\(fileId)/folder", method: "PUT", body: MoveFileRequest(folderId: folderId), authenticated: true)
        _ = try await execute(request)
    }

    public func renameFile(fileId: String, newFileName: String) async throws {
        let request = try jsonRequest("/files/\(fileId)/rename", method: "PUT", body: RenameFileRequest(fileName: newFileName), authenticated: true)
        _ = try await execute(request)
    }

    /// Replaces `fileId`'s live content outright - the server automatically captures whatever was
    /// live immediately beforehand as a fresh version first (section 2, `GET /files/{id}/versions`),
    /// so this never discards history.
    ///
    /// `expectedUpdatedAtEpochMillis`, if given, is an optimistic-concurrency precondition (section
    /// 10): if the file's current `updatedAtEpochMilli` no longer matches it (some other write - a
    /// sync from another device, say - already landed first), the server leaves the original file
    /// completely untouched and instead persists `data` as a new "conflicted copy" file in the same
    /// folder, responding `409` with that new file's own summary - surfaced here as
    /// `APIError.syncConflict(conflictedCopy:)` rather than a generic `.server` failure, so a caller
    /// can navigate straight to the preserved edit instead of just being told something went wrong.
    /// Passing `nil` (the default) always overwrites unconditionally, exactly like before this
    /// precondition existed.
    public func replaceFileContent(fileId: String, data: Data, expectedUpdatedAtEpochMillis: Int64? = nil) async throws -> StoredFileSummaryResponse {
        var path = "/files/\(fileId)/content"
        if let expectedUpdatedAtEpochMillis {
            path += "?expectedUpdatedAt=\(expectedUpdatedAtEpochMillis)"
        }
        var request = plainRequest(path, method: "PUT", authenticated: true)
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.httpBody = data
        let (responseData, httpResponse) = try await execute(request, passthroughStatusCodes: [409])
        if httpResponse.statusCode == 409 {
            let conflictedCopy: StoredFileSummaryResponse = try decode(responseData)
            throw APIError.syncConflict(conflictedCopy: conflictedCopy)
        }
        return try decode(responseData)
    }

    /// Fetches `fileId`'s JPEG preview thumbnail, if one has already been generated - `nil` both
    /// for "no thumbnail available yet" (404, e.g. the file isn't an image/PDF, or generation
    /// hasn't finished) and "this deployment doesn't run the thumbnails extension at all" (503),
    /// since either way there's simply nothing to show. Any other failure is rethrown.
    public func getThumbnail(fileId: String) async throws -> Data? {
        let request = plainRequest("/files/\(fileId)/thumbnail", method: "GET", authenticated: true)
        do {
            let (data, _) = try await execute(request)
            return data
        } catch APIError.server(let status, _) where status == 404 || status == 503 {
            return nil
        }
    }

    // MARK: - Presigned direct-to-client transfer

    /// Uploads `fileURL` directly to the configured object store, bypassing this app's own server
    /// for the data path entirely - orchestrates `beginUploadURL`, a raw `PUT` to the returned
    /// URL, then `completeUpload`. Computes the SHA-256 checksum `completeUpload` needs via a
    /// dedicated pre-pass reading `fileURL` once before the upload itself streams it a second time.
    ///
    /// Throws `APIError.server(status: 503, ...)` if this deployment hasn't configured presigned
    /// transfer - callers should fall back to `uploadFile(fileName:data:folderId:)` on exactly
    /// that status.
    public func uploadFileViaPresignedURL(fileName: String, fileURL: URL, folderId: String?, onProgress: (@MainActor (Int64, Int64) -> Void)? = nil) async throws -> StoredFileSummaryResponse {
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let sizeBytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        let checksumSha256 = try sha256Hex(of: fileURL)

        let begin = try await beginUploadURL(fileName: fileName, sizeBytes: sizeBytes, folderId: folderId)
        guard let uploadURL = URL(string: begin.uploadUrl) else {
            throw APIError.network(URLError(.badURL))
        }
        try await putToPresignedURL(url: uploadURL, requiredHeaders: begin.requiredHeaders, fileURL: fileURL, onProgress: onProgress)
        return try await completeUpload(fileId: begin.fileId, fileName: fileName, checksumSha256: checksumSha256, folderId: folderId)
    }

    /// Downloads a file directly from the configured object store, bypassing this app's own
    /// server for the data path entirely - orchestrates `beginDownloadURL` then a raw `GET` to the
    /// returned URL, streamed straight to `destination` on disk.
    ///
    /// Throws `APIError.server(status: 503, ...)` if this deployment hasn't configured presigned
    /// transfer, or this particular file isn't eligible for it - callers should fall back to
    /// `downloadFileContent(fileId:)` on exactly that status.
    public func downloadFileViaPresignedURL(fileId: String, destination: URL, onProgress: (@MainActor (Int64, Int64) -> Void)? = nil) async throws {
        let begin = try await beginDownloadURL(fileId: fileId)
        guard let downloadURL = URL(string: begin.downloadUrl) else {
            throw APIError.network(URLError(.badURL))
        }
        try await downloadFromPresignedURL(url: downloadURL, destination: destination, onProgress: onProgress)
    }

    private func beginUploadURL(fileName: String, sizeBytes: Int64, folderId: String?) async throws -> BeginUploadUrlResponse {
        let request = try jsonRequest("/files/upload-url", method: "POST", body: BeginUploadUrlRequest(fileName: fileName, sizeBytes: sizeBytes, folderId: folderId), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    private func completeUpload(fileId: String, fileName: String, checksumSha256: String, folderId: String?) async throws -> StoredFileSummaryResponse {
        let request = try jsonRequest("/files/\(fileId)/complete-upload", method: "POST", body: CompleteUploadRequest(fileName: fileName, checksumSha256: checksumSha256, folderId: folderId), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    private func beginDownloadURL(fileId: String) async throws -> BeginDownloadUrlResponse {
        let request = plainRequest("/files/\(fileId)/download-url", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// `PUT`s `fileURL`'s bytes directly to `url` (a presigned upload URL, not this app's own
    /// server) - unauthenticated (no `Authorization` header; nothing needs one against the object
    /// store), replaying every one of `requiredHeaders` exactly, or the object store rejects the
    /// request's signature. Streams from disk via `URLSession.upload(for:fromFile:)`, not a
    /// fully-buffered `Data` upload - this is also the first upload path in this app that streams
    /// rather than fully buffering (see this module's own README on that pre-existing gap).
    /// `onProgress`, if given, is wired up via a per-request `ProgressForwardingDelegate` (the
    /// task-scoped delegate overload of this call, not this app's session-wide delegate).
    private func putToPresignedURL(url: URL, requiredHeaders: [String: String], fileURL: URL, onProgress: (@MainActor (Int64, Int64) -> Void)? = nil) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        for (key, value) in requiredHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let data: Data
        let response: URLResponse
        do {
            let delegate = onProgress.map { ProgressForwardingDelegate(onProgress: $0) }
            (data, response) = try await session.upload(for: request, fromFile: fileURL, delegate: delegate)
        } catch {
            throw APIError.network(error)
        }
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.network(URLError(.badServerResponse))
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            // The object store's own error body (XML, not this app's JSON ErrorResponse shape) -
            // included as-is rather than run through ErrorResponse decoding, which is built around
            // this app's own JSON error convention.
            let body = String(data: data, encoding: .utf8) ?? "(no body)"
            throw APIError.server(status: httpResponse.statusCode, message: "presigned upload failed: \(body)")
        }
    }

    /// Downloads directly from `url` (a presigned download URL, not this app's own server) straight
    /// to `destination` on disk via `URLSession.download(for:)` - `destination` must not already
    /// exist, the same contract `FileManager.moveItem` itself has. `onProgress`, if given, is wired
    /// up the same `ProgressForwardingDelegate` way `putToPresignedURL` uses for uploads.
    private func downloadFromPresignedURL(url: URL, destination: URL, onProgress: (@MainActor (Int64, Int64) -> Void)? = nil) async throws {
        let tempURL: URL
        let response: URLResponse
        do {
            let delegate = onProgress.map { ProgressForwardingDelegate(onProgress: $0) }
            (tempURL, response) = try await session.download(for: URLRequest(url: url), delegate: delegate)
        } catch {
            throw APIError.network(error)
        }
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.network(URLError(.badServerResponse))
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            let body = (try? String(contentsOf: tempURL, encoding: .utf8)) ?? "(no body)"
            try? FileManager.default.removeItem(at: tempURL)
            throw APIError.server(status: httpResponse.statusCode, message: "presigned download failed: \(body)")
        }
        try FileManager.default.moveItem(at: tempURL, to: destination)
    }

    /// Computes `fileURL`'s SHA-256 checksum as a lowercase hex string, streamed via `InputStream`
    /// rather than loading the whole file into memory - the shape the server's `FileChecksum`
    /// carries, so it can persist the value verbatim without knowing anything about this type.
    private func sha256Hex(of fileURL: URL) throws -> String {
        guard let stream = InputStream(url: fileURL) else {
            throw APIError.network(URLError(.cannotOpenFile))
        }
        stream.open()
        defer { stream.close() }

        var hasher = SHA256()
        let bufferSize = 1 << 16
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        while stream.hasBytesAvailable {
            let bytesRead = stream.read(&buffer, maxLength: bufferSize)
            if bytesRead < 0 {
                throw APIError.network(stream.streamError ?? URLError(.unknown))
            }
            if bytesRead == 0 {
                break
            }
            hasher.update(data: Data(buffer[0..<bytesRead]))
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Search

    /// Searches the caller's own account for a filename/content match - an empty or blank `query`
    /// still succeeds, just with an empty result. Throws `APIError.server(status: 503, ...)` if
    /// this deployment doesn't run the search extension.
    public func search(query: String, limit: Int) async throws -> [SearchResultResponse] {
        let request = plainRequest("/search?q=\(query.queryEncoded())&limit=\(limit)", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - File version history

    /// Lists every captured version of `fileId`'s content, newest first - a version is captured
    /// automatically each time `PUT /files/{id}/content` overwrites the file, never on upload.
    public func listFileVersions(fileId: String) async throws -> [FileVersionSummaryResponse] {
        let request = plainRequest("/files/\(fileId)/versions", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Downloads one specific version's content, exactly as it existed at the moment it was
    /// captured - independent of whatever the file's current, live content is.
    public func downloadFileVersion(fileId: String, versionNumber: Int) async throws -> Data {
        let request = plainRequest("/files/\(fileId)/versions/\(versionNumber)/content", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return data
    }

    /// Restores `versionNumber` as the file's live content - this itself captures whatever was live
    /// immediately beforehand as a fresh version first, so restoring never discards history.
    public func restoreFileVersion(fileId: String, versionNumber: Int) async throws -> StoredFileSummaryResponse {
        let request = plainRequest("/files/\(fileId)/versions/\(versionNumber)/restore", method: "POST", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Activity feed

    /// The account-wide activity feed - every recorded action across every file/folder the caller
    /// owns or has been shared, newest first. Cursor-paginated the same way `listFilesPage` is:
    /// pass `cursor: nil` for the first page, then each page's own `nextCursor` for the next.
    public func listActivity(cursor: String?, limit: Int) async throws -> Page<ActivityEntryResponse> {
        var path = "/activity?limit=\(limit)"
        if let cursor {
            path += "&cursor=\(cursor.queryEncoded())"
        }
        let request = plainRequest(path, method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// The activity feed scoped to one file - owner-or-share access-checked the same way `getFile`
    /// itself is, so whoever can currently view the file can see its history too.
    public func listFileActivity(fileId: String, cursor: String?, limit: Int) async throws -> Page<ActivityEntryResponse> {
        var path = "/files/\(fileId)/activity?limit=\(limit)"
        if let cursor {
            path += "&cursor=\(cursor.queryEncoded())"
        }
        let request = plainRequest(path, method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// The activity feed scoped to one folder - same access rules as `listFileActivity`.
    public func listFolderActivity(folderId: String, cursor: String?, limit: Int) async throws -> Page<ActivityEntryResponse> {
        var path = "/folders/\(folderId)/activity?limit=\(limit)"
        if let cursor {
            path += "&cursor=\(cursor.queryEncoded())"
        }
        let request = plainRequest(path, method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Folders

    public func listFolders(parentFolderId: String?) async throws -> [FolderResponse] {
        let scope = (parentFolderId ?? "root").queryEncoded()
        let request = plainRequest("/folders?parentFolderId=\(scope)", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Cursor-paginated counterpart to `listFolders(parentFolderId:)` - see `listFilesPage`'s own
    /// doc comment for the full contract; identical shape, scoped to folders instead of files.
    public func listFoldersPage(parentFolderId: String?, cursor: String?, limit: Int) async throws -> Page<FolderResponse> {
        var path = "/folders?limit=\(limit)&parentFolderId=\((parentFolderId ?? "root").queryEncoded())"
        if let cursor {
            path += "&cursor=\(cursor.queryEncoded())"
        }
        let request = plainRequest(path, method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func createFolder(name: String, parentFolderId: String?) async throws -> FolderResponse {
        let request = try jsonRequest("/folders", method: "POST", body: CreateFolderRequest(name: name, parentFolderId: parentFolderId), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func deleteFolder(folderId: String) async throws {
        let request = plainRequest("/folders/\(folderId)", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    /// `PUT /folders/{id}` - a full replace of both fields; moving a folder without renaming it
    /// means carrying its current `name` through unchanged (`nil` `parentFolderId` moves it to
    /// the top level).
    public func updateFolder(folderId: String, name: String, parentFolderId: String?) async throws -> FolderResponse {
        let request = try jsonRequest("/folders/\(folderId)", method: "PUT", body: UpdateFolderRequest(name: name, parentFolderId: parentFolderId), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// `PUT /folders/{id}/color` - sets a folder's display color, a separate call from
    /// `updateFolder` so recoloring never touches name/parent.
    public func updateFolderColor(folderId: String, color: String?) async throws {
        let request = try jsonRequest("/folders/\(folderId)/color", method: "PUT", body: UpdateFolderColorRequest(color: color), authenticated: true)
        _ = try await execute(request)
    }

    // MARK: - Sharing (grantee side: what's shared with me)

    public func listSharedFilesWithMe() async throws -> [SharedFileSummaryResponse] {
        let request = plainRequest("/files/shared-with-me", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func listSharedFoldersWithMe() async throws -> [SharedFolderSummaryResponse] {
        let request = plainRequest("/folders/shared-with-me", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Lists the non-trashed files/subfolders directly inside `folderId` - works for the shared
    /// folder itself, and (since a share on an ancestor covers every descendant) for any
    /// subfolder reached by navigating deeper into it, via the exact same route.
    public func sharedFolderContents(folderId: String) async throws -> SharedFolderContentsResponse {
        let request = plainRequest("/folders/\(folderId)/shared-contents", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Sharing (owner side: sharing my own files/folders out)

    public func shareFile(fileId: String, granteeEmail: String) async throws {
        let request = try jsonRequest("/files/\(fileId)/share", method: "POST", body: ShareRequest(granteeEmail: granteeEmail), authenticated: true)
        _ = try await execute(request)
    }

    /// Shares `fileId` with `granteeEmail`, with an explicit permission level (`"VIEW"`/`"EDIT"`,
    /// `nil` defaults to `"VIEW"` server-side) and/or expiry (`nil` never expires). `"EDIT"` is the
    /// only permission that lets the grantee overwrite the file's own content via `PUT
    /// /files/{id}/content` - every other mutation stays owner-only regardless of permission level.
    public func shareFile(fileId: String, granteeEmail: String, permissionLevel: String?, expiresAtEpochMillis: Int64?) async throws {
        let request = try jsonRequest("/files/\(fileId)/share", method: "POST", body: ShareRequest(granteeEmail: granteeEmail, permissionLevel: permissionLevel, expiresAtEpochMillis: expiresAtEpochMillis), authenticated: true)
        _ = try await execute(request)
    }

    public func revokeFileShare(fileId: String, granteeEmail: String) async throws {
        let request = plainRequest("/files/\(fileId)/share/\(granteeEmail.queryEncoded())", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    /// The emails of every account `fileId` is currently shared with - owner-only, backs the
    /// revoke UI in `ShareSheet`.
    public func listFileShares(fileId: String) async throws -> [String] {
        let request = plainRequest("/files/\(fileId)/share", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func shareFolder(folderId: String, granteeEmail: String) async throws {
        let request = try jsonRequest("/folders/\(folderId)/share", method: "POST", body: ShareRequest(granteeEmail: granteeEmail), authenticated: true)
        _ = try await execute(request)
    }

    /// Shares `folderId` with `granteeEmail`, with an explicit permission level and/or expiry - see
    /// `shareFile(fileId:granteeEmail:permissionLevel:expiresAtEpochMillis:)`'s own doc comment.
    /// Unlike a file share, `"EDIT"` on a folder grant carries no extra capability today - it's
    /// accepted and stored, but every folder mutation stays owner-only regardless.
    public func shareFolder(folderId: String, granteeEmail: String, permissionLevel: String?, expiresAtEpochMillis: Int64?) async throws {
        let request = try jsonRequest("/folders/\(folderId)/share", method: "POST", body: ShareRequest(granteeEmail: granteeEmail, permissionLevel: permissionLevel, expiresAtEpochMillis: expiresAtEpochMillis), authenticated: true)
        _ = try await execute(request)
    }

    public func revokeFolderShare(folderId: String, granteeEmail: String) async throws {
        let request = plainRequest("/folders/\(folderId)/share/\(granteeEmail.queryEncoded())", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    public func listFolderShares(folderId: String) async throws -> [String] {
        let request = plainRequest("/folders/\(folderId)/share", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Public file links

    /// Creates a new unauthenticated, read-only link to `fileId`'s content - owner-only.
    /// `expiresAtEpochMillis` `nil` creates a link that never expires. The returned token is
    /// reachable via `GET /public/files/{token}` with no bearer token at all - not implemented by
    /// this client, since that route is meant for an anonymous browser, not this app.
    public func createPublicFileLink(fileId: String, expiresAtEpochMillis: Int64?) async throws -> PublicFileLinkSummaryResponse {
        let request = try jsonRequest("/files/\(fileId)/public-link", method: "POST", body: CreatePublicFileLinkRequest(expiresAtEpochMillis: expiresAtEpochMillis), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Lists every currently active public link on `fileId` - owner-only.
    public func listPublicFileLinks(fileId: String) async throws -> [PublicFileLinkSummaryResponse] {
        let request = plainRequest("/files/\(fileId)/public-link", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Revokes one public link - owner-only, idempotent (a no-op if the token is already gone).
    public func revokePublicFileLink(fileId: String, token: String) async throws {
        let request = plainRequest("/files/\(fileId)/public-link/\(token.queryEncoded())", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    // MARK: - Trash

    /// `DELETE /files/{id}`/`DELETE /folders/{id}` (see `deleteFile`/`deleteFolder` above) are
    /// already soft deletes server-side - these three methods surface what that put there.
    public func listDeletedFiles() async throws -> [TrashedFileSummaryResponse] {
        let request = plainRequest("/files/trash", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func listDeletedFolders() async throws -> [TrashedFolderSummaryResponse] {
        let request = plainRequest("/folders/trash", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    public func restoreFile(fileId: String) async throws {
        let request = plainRequest("/files/\(fileId)/restore", method: "POST", authenticated: true)
        _ = try await execute(request)
    }

    public func restoreFolder(folderId: String) async throws {
        let request = plainRequest("/folders/\(folderId)/restore", method: "POST", authenticated: true)
        _ = try await execute(request)
    }

    /// Permanently removes everything currently in the trash, bypassing the retention window
    /// entirely - irreversible, unlike a single `restoreFile`/`restoreFolder`.
    public func emptyTrash() async throws {
        let request = plainRequest("/trash/empty", method: "POST", authenticated: true)
        _ = try await execute(request)
    }

    // MARK: - Low-level plumbing

    /// Issues `request` (a token-issuing route) and applies the resulting `AuthResponse` to the
    /// held tokens - shared by every route that mints a fresh access/refresh pair.
    private func issueTokens(from request: URLRequest, allowRefreshRetry: Bool = false) async throws -> AuthResponse {
        let (data, _) = try await execute(request, allowRefreshRetry: allowRefreshRetry)
        let response: AuthResponse = try decode(data)
        self.accessToken = response.token
        self.refreshToken = response.refreshToken
        self.onTokensRotated?(response.token, response.refreshToken)
        return response
    }

    private func plainRequest(_ path: String, method: String, authenticated: Bool) -> URLRequest {
        var request = URLRequest(url: URL(string: path, relativeTo: baseURL)!)
        request.httpMethod = method
        // The server content-negotiates its *error* body on the request's Accept header (Javalin's
        // default HttpResponseException mapping) - without this, a 4xx/5xx comes back as a bare
        // text/plain message ("invalid credentials") instead of the {"title": ...} JSON `decode`
        // below expects, silently defeating error-message decoding for every failed request.
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if authenticated, let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func jsonRequest<Body: Encodable>(_ path: String, method: String, body: Body, authenticated: Bool) throws -> URLRequest {
        var request = plainRequest(path, method: method, authenticated: authenticated)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return request
    }

    /// Runs `request`, transparently refreshing and retrying exactly once on a `401` for an
    /// authenticated request that still holds a refresh token - see this actor's own top-level
    /// documentation. `allowRefreshRetry: false` is used by every call that itself mints/rotates
    /// tokens, so a failing login/refresh/reset never recurses into its own retry path.
    ///
    /// `passthroughStatusCodes` lets a specific caller (e.g. `replaceFileContent`'s `409`
    /// sync-conflict response) receive a non-2xx response's raw body/status instead of this method
    /// mapping it to a generic `APIError.server` - the caller is responsible for decoding that body
    /// itself into whatever structured shape it actually carries. Empty by default, so every other
    /// call site's error handling is completely unchanged.
    private func execute(_ request: URLRequest, allowRefreshRetry: Bool = true, passthroughStatusCodes: Set<Int> = []) async throws -> (Data, HTTPURLResponse) {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIError.network(error)
        }
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.network(URLError(.badServerResponse))
        }
        if httpResponse.statusCode == 401,
           allowRefreshRetry,
           request.value(forHTTPHeaderField: "Authorization") != nil,
           self.refreshToken != nil {
            _ = try? await self.refresh()
            var retried = request
            if let accessToken {
                retried.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
            }
            return try await execute(retried, allowRefreshRetry: false, passthroughStatusCodes: passthroughStatusCodes)
        }
        guard (200..<300).contains(httpResponse.statusCode) || passthroughStatusCodes.contains(httpResponse.statusCode) else {
            let message = (try? decoder.decode(ErrorResponse.self, from: data))?.title
                ?? "Request failed (\(httpResponse.statusCode))"
            throw APIError.server(status: httpResponse.statusCode, message: message)
        }
        return (data, httpResponse)
    }

    private func decode<Response: Decodable>(_ data: Data) throws -> Response {
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }
}
