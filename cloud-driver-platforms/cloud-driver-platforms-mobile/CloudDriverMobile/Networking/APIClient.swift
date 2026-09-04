import CryptoKit
import Foundation

/// Failures surfaced from `APIClient`. `errorDescription` is what view models put directly into
/// `AppViewModel.errorMessage` for display.
enum APIError: Error, LocalizedError {
    case network(Error)
    case server(status: Int, message: String)
    case decoding(Error)
    case notAuthenticated

    var errorDescription: String? {
        switch self {
        case .network(let error):
            return error.localizedDescription
        case .server(_, let message):
            return message
        case .decoding(let error):
            return "Failed to read the server's response: \(error.localizedDescription)"
        case .notAuthenticated:
            return "You're signed out - please sign in again."
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

/// A plain `URLSession`-based client for `cloud-driver`'s JWT-authenticated REST API - the iOS
/// counterpart to `cloud-driver-platforms-rest`'s Java `ApiClient`. An `actor` rather than a class
/// with manual locking, so every token read/write is already serialized without extra ceremony.
///
/// Every authenticated call transparently retries once after a `401` by exchanging the held
/// refresh token first (`execute`'s own retry branch) - mirroring the same contract the Java
/// client and the server's refresh-token design document (see cloud-driver's CLAUDE.md, "Refresh
/// tokens") describe. A caller only ever sees the original `401` if that retry also fails.
actor APIClient {

    /// The one deployment this app talks to - see cloud-driver-platforms-desktop's `Main.kt` for
    /// the equivalent hardcoded constant on the desktop client. Change and rebuild to point this
    /// app at a different server.
    static let shared = APIClient(baseURL: URL(string: "https://api.cloud-driver.de")!)

    private let baseURL: URL
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private(set) var accessToken: String?
    private(set) var refreshToken: String?

    var isAuthenticated: Bool { accessToken != nil }

    init(baseURL: URL) {
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

    func login(email: String, password: String) async throws -> LoginOutcome {
        let request = try jsonRequest("/auth/login", method: "POST", body: AuthRequest(username: email, password: password), authenticated: false)
        let (data, _) = try await execute(request, allowRefreshRetry: false)
        let outcome: LoginOutcome = try decode(data)
        if !outcome.twoFactorRequired, let token = outcome.token, let freshRefresh = outcome.refreshToken {
            self.accessToken = token
            self.refreshToken = freshRefresh
        }
        return outcome
    }

    func completeTwoFactorLogin(pendingToken: String, code: String) async throws -> AuthResponse {
        let request = try jsonRequest("/auth/2fa/login", method: "POST", body: TwoFactorLoginRequest(pendingToken: pendingToken, code: code), authenticated: false)
        return try await issueTokens(from: request)
    }

    func register(email: String, password: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/register", method: "POST", body: AuthRequest(username: email, password: password), authenticated: false)
        let (data, _) = try await execute(request, allowRefreshRetry: false)
        return try decode(data)
    }

    func confirmRegistration(email: String, code: String) async throws -> AuthResponse {
        let request = try jsonRequest("/auth/register/confirm", method: "POST", body: ConfirmRegistrationRequest(username: email, code: code), authenticated: false)
        return try await issueTokens(from: request)
    }

    func requestPasswordReset(email: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/reset-password", method: "POST", body: RequestPasswordResetRequest(username: email), authenticated: false)
        let (data, _) = try await execute(request, allowRefreshRetry: false)
        return try decode(data)
    }

    func confirmPasswordReset(email: String, code: String, newPassword: String) async throws -> AuthResponse {
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

    func me() async throws -> MeResponse {
        let request = plainRequest("/auth/me", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func cloudUser(authUserId: String) async throws -> CloudUserResponse {
        let request = plainRequest("/cloudUsers/\(authUserId)", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Starts an e-mail change for the caller's own (already-authenticated) account - `newEmail`
    /// isn't live yet, only `confirmEmailChange` actually applies it.
    func requestEmailChange(newEmailAddress: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/change-email", method: "POST", body: ChangeEmailRequest(newEmail: newEmailAddress), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Applies a pending e-mail change - does **not** issue a fresh token (a JWT's subject is the
    /// account id, never its e-mail, so the caller's already-held token stays valid unchanged).
    func confirmEmailChange(code: String) async throws -> MessageResponse {
        let request = try jsonRequest("/auth/change-email/confirm", method: "POST", body: ConfirmChangeEmailRequest(code: code), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Files

    func listFiles(folderId: String?) async throws -> [StoredFileSummaryResponse] {
        let scope = (folderId ?? "root").queryEncoded()
        let request = plainRequest("/files?folderId=\(scope)", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func uploadFile(fileName: String, data: Data, folderId: String?) async throws -> StoredFileSummaryResponse {
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

    func downloadFileContent(fileId: String) async throws -> Data {
        let request = plainRequest("/files/\(fileId)/content", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return data
    }

    func deleteFile(fileId: String) async throws {
        let request = plainRequest("/files/\(fileId)", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    func moveFile(fileId: String, folderId: String?) async throws {
        let request = try jsonRequest("/files/\(fileId)/folder", method: "PUT", body: MoveFileRequest(folderId: folderId), authenticated: true)
        _ = try await execute(request)
    }

    // MARK: - Presigned direct-to-client transfer (architecture/AWS_S3_IMPL.md)

    /// Uploads `fileURL` directly to the configured object store, bypassing this app's own server
    /// for the data path entirely - orchestrates `beginUploadURL`, a raw `PUT` to the returned
    /// URL, then `completeUpload`. Computes the SHA-256 checksum `completeUpload` needs via a
    /// dedicated pre-pass reading `fileURL` once before the upload itself streams it a second time.
    ///
    /// Throws `APIError.server(status: 503, ...)` if this deployment hasn't configured presigned
    /// transfer - callers should fall back to `uploadFile(fileName:data:folderId:)` on exactly
    /// that status.
    func uploadFileViaPresignedURL(fileName: String, fileURL: URL, folderId: String?) async throws -> StoredFileSummaryResponse {
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let sizeBytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        let checksumSha256 = try sha256Hex(of: fileURL)

        let begin = try await beginUploadURL(fileName: fileName, sizeBytes: sizeBytes, folderId: folderId)
        guard let uploadURL = URL(string: begin.uploadUrl) else {
            throw APIError.network(URLError(.badURL))
        }
        try await putToPresignedURL(url: uploadURL, requiredHeaders: begin.requiredHeaders, fileURL: fileURL)
        return try await completeUpload(fileId: begin.fileId, fileName: fileName, checksumSha256: checksumSha256, folderId: folderId)
    }

    /// Downloads a file directly from the configured object store, bypassing this app's own
    /// server for the data path entirely - orchestrates `beginDownloadURL` then a raw `GET` to the
    /// returned URL, streamed straight to `destination` on disk.
    ///
    /// Throws `APIError.server(status: 503, ...)` if this deployment hasn't configured presigned
    /// transfer, or this particular file isn't eligible for it - callers should fall back to
    /// `downloadFileContent(fileId:)` on exactly that status.
    func downloadFileViaPresignedURL(fileId: String, destination: URL) async throws {
        let begin = try await beginDownloadURL(fileId: fileId)
        guard let downloadURL = URL(string: begin.downloadUrl) else {
            throw APIError.network(URLError(.badURL))
        }
        try await downloadFromPresignedURL(url: downloadURL, destination: destination)
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
    private func putToPresignedURL(url: URL, requiredHeaders: [String: String], fileURL: URL) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        for (key, value) in requiredHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.upload(for: request, fromFile: fileURL)
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
    /// exist, the same contract `FileManager.moveItem` itself has.
    private func downloadFromPresignedURL(url: URL, destination: URL) async throws {
        let tempURL: URL
        let response: URLResponse
        do {
            (tempURL, response) = try await session.download(for: URLRequest(url: url))
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

    // MARK: - Folders

    func listFolders(parentFolderId: String?) async throws -> [FolderResponse] {
        let scope = (parentFolderId ?? "root").queryEncoded()
        let request = plainRequest("/folders?parentFolderId=\(scope)", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func createFolder(name: String, parentFolderId: String?) async throws -> FolderResponse {
        let request = try jsonRequest("/folders", method: "POST", body: CreateFolderRequest(name: name, parentFolderId: parentFolderId), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func deleteFolder(folderId: String) async throws {
        let request = plainRequest("/folders/\(folderId)", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    /// `PUT /folders/{id}` - a full replace of both fields; moving a folder without renaming it
    /// means carrying its current `name` through unchanged (`nil` `parentFolderId` moves it to
    /// the top level).
    func updateFolder(folderId: String, name: String, parentFolderId: String?) async throws -> FolderResponse {
        let request = try jsonRequest("/folders/\(folderId)", method: "PUT", body: UpdateFolderRequest(name: name, parentFolderId: parentFolderId), authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Sharing (grantee side: what's shared with me)

    func listSharedFilesWithMe() async throws -> [SharedFileSummaryResponse] {
        let request = plainRequest("/files/shared-with-me", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func listSharedFoldersWithMe() async throws -> [SharedFolderSummaryResponse] {
        let request = plainRequest("/folders/shared-with-me", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    /// Lists the non-trashed files/subfolders directly inside `folderId` - works for the shared
    /// folder itself, and (since a share on an ancestor covers every descendant) for any
    /// subfolder reached by navigating deeper into it, via the exact same route.
    func sharedFolderContents(folderId: String) async throws -> SharedFolderContentsResponse {
        let request = plainRequest("/folders/\(folderId)/shared-contents", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Sharing (owner side: sharing my own files/folders out)

    func shareFile(fileId: String, granteeEmail: String) async throws {
        let request = try jsonRequest("/files/\(fileId)/share", method: "POST", body: ShareRequest(granteeEmail: granteeEmail), authenticated: true)
        _ = try await execute(request)
    }

    func revokeFileShare(fileId: String, granteeEmail: String) async throws {
        let request = plainRequest("/files/\(fileId)/share/\(granteeEmail.queryEncoded())", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    /// The emails of every account `fileId` is currently shared with - owner-only, backs the
    /// revoke UI in `ShareSheet`.
    func listFileShares(fileId: String) async throws -> [String] {
        let request = plainRequest("/files/\(fileId)/share", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func shareFolder(folderId: String, granteeEmail: String) async throws {
        let request = try jsonRequest("/folders/\(folderId)/share", method: "POST", body: ShareRequest(granteeEmail: granteeEmail), authenticated: true)
        _ = try await execute(request)
    }

    func revokeFolderShare(folderId: String, granteeEmail: String) async throws {
        let request = plainRequest("/folders/\(folderId)/share/\(granteeEmail.queryEncoded())", method: "DELETE", authenticated: true)
        _ = try await execute(request)
    }

    func listFolderShares(folderId: String) async throws -> [String] {
        let request = plainRequest("/folders/\(folderId)/share", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    // MARK: - Trash

    /// `DELETE /files/{id}`/`DELETE /folders/{id}` (see `deleteFile`/`deleteFolder` above) are
    /// already soft deletes server-side - these three methods surface what that put there.
    func listDeletedFiles() async throws -> [TrashedFileSummaryResponse] {
        let request = plainRequest("/files/trash", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func listDeletedFolders() async throws -> [TrashedFolderSummaryResponse] {
        let request = plainRequest("/folders/trash", method: "GET", authenticated: true)
        let (data, _) = try await execute(request)
        return try decode(data)
    }

    func restoreFile(fileId: String) async throws {
        let request = plainRequest("/files/\(fileId)/restore", method: "POST", authenticated: true)
        _ = try await execute(request)
    }

    func restoreFolder(folderId: String) async throws {
        let request = plainRequest("/folders/\(folderId)/restore", method: "POST", authenticated: true)
        _ = try await execute(request)
    }

    /// Permanently removes everything currently in the trash, bypassing the retention window
    /// entirely - irreversible, unlike a single `restoreFile`/`restoreFolder`.
    func emptyTrash() async throws {
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
    private func execute(_ request: URLRequest, allowRefreshRetry: Bool = true) async throws -> (Data, HTTPURLResponse) {
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
            return try await execute(retried, allowRefreshRetry: false)
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
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
