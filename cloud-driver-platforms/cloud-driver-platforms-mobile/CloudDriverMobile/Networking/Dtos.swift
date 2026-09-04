import Foundation

// Plain request/response shapes mirrored 1:1 against the server's REST contract - see
// cloud-driver's `DefaultRestFactory`/`CloudRestExtension` for the authoritative field names,
// and `cloud-driver-platforms-rest`'s `Dtos.java` for the JVM-side twin of this file. Deliberately
// hand-kept-in-sync rather than shared code: this app only ever talks HTTP, the same boundary
// `cloud-driver-platforms-rest` itself enforces against the server modules.

struct AuthRequest: Encodable {
    let username: String
    let password: String
}

struct ConfirmRegistrationRequest: Encodable {
    let username: String
    let code: String
}

struct RequestPasswordResetRequest: Encodable {
    let username: String
}

struct ConfirmPasswordResetRequest: Encodable {
    let username: String
    let code: String
    let newPassword: String
}

struct RefreshRequest: Encodable {
    let refreshToken: String
}

struct TwoFactorLoginRequest: Encodable {
    let pendingToken: String
    let code: String
}

struct CreateFolderRequest: Encodable {
    let name: String
    let parentFolderId: String?
}

struct MoveFileRequest: Encodable {
    let folderId: String?
}

/// Body for `POST /files/upload-url` - the first step of a presigned, direct-to-client upload
/// (see cloud-driver's `architecture/AWS_S3_IMPL.md`). `sizeBytes` is checked against quota now
/// and again (against the real uploaded size) at `CompleteUploadRequest`.
struct BeginUploadUrlRequest: Encodable {
    let fileName: String
    let sizeBytes: Int64
    let folderId: String?
}

/// Response from `POST /files/upload-url` - `requiredHeaders` must be replayed exactly on this
/// app's own `PUT` to `uploadUrl`, or the object store rejects the request's signature.
struct BeginUploadUrlResponse: Decodable {
    let fileId: String
    let uploadUrl: String
    let requiredHeaders: [String: String]
    let expiresAtEpochMillis: Int64
}

/// Body for `POST /files/{id}/complete-upload` - the second step of a presigned upload. No
/// `sizeBytes` field here: the server always re-reads the real size from the object store itself.
struct CompleteUploadRequest: Encodable {
    let fileName: String
    let checksumSha256: String
    let folderId: String?
}

/// Response from `GET /files/{id}/download-url` - this app `GET`s `downloadUrl` directly, bypassing the server.
struct BeginDownloadUrlResponse: Decodable {
    let downloadUrl: String
    let expiresAtEpochMillis: Int64
}

/// Body for `PUT /folders/{id}` - a full replace of both fields (matching `PUT`'s
/// whole-resource-replace semantics), used here to change only `parentFolderId` while carrying
/// the folder's existing `name` through unchanged.
struct UpdateFolderRequest: Encodable {
    let name: String
    let parentFolderId: String?
}

/// Body for `POST /auth/change-email` - bearer-gated: the account being changed is the caller's
/// own, resolved server-side from its token, never from this body.
struct ChangeEmailRequest: Encodable {
    let newEmail: String
}

/// Body for `POST /auth/change-email/confirm`.
struct ConfirmChangeEmailRequest: Encodable {
    let code: String
}

/// Body for `POST /files/{id}/share` and `POST /folders/{id}/share` - grants `granteeEmail`'s
/// account read-only access.
struct ShareRequest: Encodable {
    let granteeEmail: String
}

struct MessageResponse: Decodable {
    let message: String
}

/// Superset of a completed login and a two-factor-pending login - mirrors the server's
/// `LoginOutcome`. `token`/`refreshToken` are `nil` when `twoFactorRequired` is `true`, and
/// `pendingToken` is `nil` otherwise.
///
/// **The server actually returns one of two entirely different record shapes**, not one merged
/// JSON object: a completed login is `DefaultRestFactory.LoginResponse{token, refreshToken}` -
/// no `twoFactorRequired` key at all - and a 2FA-pending login is
/// `DefaultRestFactory.TwoFactorRequiredResponse{twoFactorRequired, pendingToken}` - no
/// `token`/`refreshToken` keys at all. Gson (the server-side/Java-client serializer) tolerates a
/// missing primitive `boolean` field by defaulting it to `false`; Swift's synthesized
/// `Decodable` does not - a non-optional `Bool` throws `DecodingError.keyNotFound` the moment the
/// key isn't present, surfacing to a caller as the unhelpful "The data couldn't be read because
/// it is missing." This custom initializer restores Gson's lenient behavior for this one field.
struct LoginOutcome: Decodable {
    let token: String?
    let refreshToken: String?
    let twoFactorRequired: Bool
    let pendingToken: String?

    private enum CodingKeys: String, CodingKey {
        case token, refreshToken, twoFactorRequired, pendingToken
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        token = try container.decodeIfPresent(String.self, forKey: .token)
        refreshToken = try container.decodeIfPresent(String.self, forKey: .refreshToken)
        twoFactorRequired = try container.decodeIfPresent(Bool.self, forKey: .twoFactorRequired) ?? false
        pendingToken = try container.decodeIfPresent(String.self, forKey: .pendingToken)
    }
}

/// Response shape every other token-issuing route returns (register/confirm, reset-password/confirm,
/// 2fa/login, refresh).
struct AuthResponse: Decodable {
    let token: String
    let refreshToken: String
}

/// Shape of one entry in `GET /files`'s response array, and of `POST /files`'s own response body
/// (upload responses carry no content - see cloud-driver's "Large-file upload/download streaming"
/// notes). Deliberately without content; fetch bytes separately via `GET /files/{id}/content`.
struct StoredFileSummaryResponse: Decodable, Identifiable, Hashable {
    var id: String { fileId }
    let fileId: String
    let fileName: String
    let contentType: String
    let sizeBytes: Int64
    let createdAtEpochMilli: Int64
    let updatedAtEpochMilli: Int64
    let folderId: String?
}

/// Shape of one entry in `GET /folders`'s response array, and of what `POST /folders`/`PUT
/// /folders/{id}` return on success.
struct FolderResponse: Decodable, Identifiable, Hashable {
    var id: String { folderId }
    let folderId: String
    let ownerId: String
    let name: String
    let parentFolderId: String?
    let createdAtEpochMillis: Int64
    let modifiedAtEpochMillis: Int64
}

/// Shape of one entry in `GET /files/shared-with-me`'s response array - a file another account
/// shared with the caller, paired with that account's email (mirrors the server's
/// `SharedFileSummary`).
struct SharedFileSummaryResponse: Decodable, Identifiable, Hashable {
    var id: String { file.fileId }
    let file: StoredFileSummaryResponse
    let ownerEmail: String
}

/// Shape of one entry in `GET /folders/shared-with-me`'s response array - the folder counterpart
/// to `SharedFileSummaryResponse` (mirrors the server's `SharedFolderSummary`).
struct SharedFolderSummaryResponse: Decodable, Identifiable, Hashable {
    var id: String { folder.folderId }
    let folder: FolderResponse
    let ownerEmail: String
}

/// Response of `GET /folders/{id}/shared-contents` - the non-trashed files/subfolders directly
/// inside a folder reached via ownership or a share (mirrors the server's `SharedFolderContents`).
/// A share on an ancestor folder covers browsing into any descendant, so this same call also
/// works for a subfolder reached by navigating deeper into an already-shared folder.
struct SharedFolderContentsResponse: Decodable {
    let files: [StoredFileSummaryResponse]
    let subfolders: [FolderResponse]
}

/// Shape of one entry in `GET /files/trash`'s response array - a soft-deleted file (via `DELETE
/// /files/{id}`) paired with when it becomes eligible for permanent removal under the server's
/// configured trash retention window (mirrors the server's `TrashedFileSummary`).
struct TrashedFileSummaryResponse: Decodable, Identifiable, Hashable {
    var id: String { file.fileId }
    let file: StoredFileSummaryResponse
    let purgeAtEpochMillis: Int64
}

/// Shape of one entry in `GET /folders/trash`'s response array - the folder counterpart to
/// `TrashedFileSummaryResponse` (mirrors the server's `TrashedFolderSummary`).
struct TrashedFolderSummaryResponse: Decodable, Identifiable, Hashable {
    var id: String { folder.folderId }
    let folder: FolderResponse
    let purgeAtEpochMillis: Int64
}

/// Response of `GET /auth/me` (bearer-gated) - the caller's own account id/email/admin flag.
struct MeResponse: Decodable {
    let authUserId: String
    let emailAddress: String
    let isAdmin: Bool
}

/// Response of `GET /cloudUsers/{id}` - mirrors `CloudUser`'s Gson-serialized fields server-side.
struct CloudUserResponse: Decodable {
    let authUserId: String
    let timeStamp: Int64
    let maxBytesToUpload: Int64
    let currentUploadedBytes: Int64
}

/// Body Javalin's default error responses use (`BadRequestResponse` etc. all share this shape).
struct ErrorResponse: Decodable {
    let title: String
}
