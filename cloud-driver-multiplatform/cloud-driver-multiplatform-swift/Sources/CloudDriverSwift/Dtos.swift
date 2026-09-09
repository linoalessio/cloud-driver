import Foundation

// Plain request/response shapes mirrored 1:1 against the server's REST contract - see
// cloud-driver's `DefaultRestFactory`/`CloudRestExtension` for the authoritative field names,
// and `cloud-driver-maven`'s `Dtos.java` for the JVM-side twin of this file. Deliberately
// hand-kept-in-sync rather than shared code: this app only ever talks HTTP, the same boundary
// `cloud-driver-maven` itself enforces against the server modules.
//
// Every type/member below is `public` - this file lives in the `CloudDriverSwift` package (moved
// out of cloud-driver-platforms-mobile, 2026-09-07), so anything the app (or another consumer)
// needs to read has to cross the module boundary explicitly. Request (`Encodable`) types are only
// ever constructed inside `APIClient.swift`, which lives in this same package/module - so their
// memberwise initializers don't strictly need to be `public` for this package's own use, but are
// marked `public` anyway (via an explicit `init`, since Swift's synthesized memberwise init is
// never `public` even on a `public` type) for any future external consumer of this library.

/// The `{"items", "nextCursor"}` envelope `GET /files`/`GET /folders` return when called with
/// `?limit=` (cursor pagination, mirrors the server's `CursorPage<T>` and
/// `cloud-driver-maven`'s `Dtos.Page<T>`/`cloud-driver-platforms-desktop`'s
/// `Dtos.Page<T>`). `nextCursor` is `nil` once the last page has been reached. Swift's `Codable`
/// handles a generic type directly - no `TypeToken`/reflection dance needed the way the JVM
/// clients require.
public struct Page<T: Decodable>: Decodable {
    public let items: [T]
    public let nextCursor: String?
}

public struct AuthRequest: Encodable {
    public let username: String
    public let password: String

    public init(username: String, password: String) {
        self.username = username
        self.password = password
    }
}

public struct ConfirmRegistrationRequest: Encodable {
    public let username: String
    public let code: String

    public init(username: String, code: String) {
        self.username = username
        self.code = code
    }
}

public struct RequestPasswordResetRequest: Encodable {
    public let username: String

    public init(username: String) {
        self.username = username
    }
}

public struct ConfirmPasswordResetRequest: Encodable {
    public let username: String
    public let code: String
    public let newPassword: String

    public init(username: String, code: String, newPassword: String) {
        self.username = username
        self.code = code
        self.newPassword = newPassword
    }
}

public struct RefreshRequest: Encodable {
    public let refreshToken: String

    public init(refreshToken: String) {
        self.refreshToken = refreshToken
    }
}

public struct CreateFolderRequest: Encodable {
    public let name: String
    public let parentFolderId: String?

    public init(name: String, parentFolderId: String?) {
        self.name = name
        self.parentFolderId = parentFolderId
    }
}

public struct MoveFileRequest: Encodable {
    public let folderId: String?

    public init(folderId: String?) {
        self.folderId = folderId
    }
}

/// Body for `PUT /files/{id}/rename` - a separate route from `MoveFileRequest`'s, matching the
/// server's own separate handling (renaming rewrites the file's own entity, unlike a move).
public struct RenameFileRequest: Encodable {
    public let fileName: String

    public init(fileName: String) {
        self.fileName = fileName
    }
}

/// Body for `POST /files/upload-url` - the first step of a presigned, direct-to-client upload.
/// `sizeBytes` is checked against quota now and again (against the real uploaded size) at
/// `CompleteUploadRequest`.
public struct BeginUploadUrlRequest: Encodable {
    public let fileName: String
    public let sizeBytes: Int64
    public let folderId: String?

    public init(fileName: String, sizeBytes: Int64, folderId: String?) {
        self.fileName = fileName
        self.sizeBytes = sizeBytes
        self.folderId = folderId
    }
}

/// Response from `POST /files/upload-url` - `requiredHeaders` must be replayed exactly on this
/// app's own `PUT` to `uploadUrl`, or the object store rejects the request's signature.
public struct BeginUploadUrlResponse: Decodable {
    public let fileId: String
    public let uploadUrl: String
    public let requiredHeaders: [String: String]
    public let expiresAtEpochMillis: Int64
}

/// Body for `POST /files/{id}/complete-upload` - the second step of a presigned upload. No
/// `sizeBytes` field here: the server always re-reads the real size from the object store itself.
public struct CompleteUploadRequest: Encodable {
    public let fileName: String
    public let checksumSha256: String
    public let folderId: String?

    public init(fileName: String, checksumSha256: String, folderId: String?) {
        self.fileName = fileName
        self.checksumSha256 = checksumSha256
        self.folderId = folderId
    }
}

/// Response from `GET /files/{id}/download-url` - this app `GET`s `downloadUrl` directly, bypassing the server.
public struct BeginDownloadUrlResponse: Decodable {
    public let downloadUrl: String
    public let expiresAtEpochMillis: Int64
}

/// Body for `PUT /folders/{id}` - a full replace of both fields (matching `PUT`'s
/// whole-resource-replace semantics), used here to change only `parentFolderId` while carrying
/// the folder's existing `name` through unchanged.
public struct UpdateFolderRequest: Encodable {
    public let name: String
    public let parentFolderId: String?

    public init(name: String, parentFolderId: String?) {
        self.name = name
        self.parentFolderId = parentFolderId
    }
}

/// Body for `POST /auth/change-email` - bearer-gated: the account being changed is the caller's
/// own, resolved server-side from its token, never from this body.
public struct ChangeEmailRequest: Encodable {
    public let newEmail: String

    public init(newEmail: String) {
        self.newEmail = newEmail
    }
}

/// Body for `POST /auth/change-email/confirm`.
public struct ConfirmChangeEmailRequest: Encodable {
    public let code: String

    public init(code: String) {
        self.code = code
    }
}

/// Body for `POST /files/{id}/share` and `POST /folders/{id}/share` - grants `granteeEmail`'s
/// account access. `permissionLevel` (`"VIEW"`/`"EDIT"`, `nil` defaults to `VIEW` server-side) and
/// `expiresAtEpochMillis` (`nil` never expires) are both optional, so an existing call passing only
/// `granteeEmail` keeps compiling and behaving exactly as before either field existed.
public struct ShareRequest: Encodable {
    public let granteeEmail: String
    public let permissionLevel: String?
    public let expiresAtEpochMillis: Int64?

    public init(granteeEmail: String, permissionLevel: String? = nil, expiresAtEpochMillis: Int64? = nil) {
        self.granteeEmail = granteeEmail
        self.permissionLevel = permissionLevel
        self.expiresAtEpochMillis = expiresAtEpochMillis
    }
}

public struct MessageResponse: Decodable {
    public let message: String
}

/// Response shape returned by every token-issuing route (login, register/confirm,
/// reset-password/confirm, refresh).
public struct AuthResponse: Decodable {
    public let token: String
    public let refreshToken: String
}

/// Shape of one entry in `GET /files`'s response array, and of `POST /files`'s own response body
/// (upload responses carry no content - see cloud-driver's "Large-file upload/download streaming"
/// notes). Deliberately without content; fetch bytes separately via `GET /files/{id}/content`.
///
/// `scanStatus` mirrors the server's content-scan verdict (`"CLEAN"`/`"PENDING"`/`"FLAGGED"`) -
/// treated as opaque text here, not a Swift enum, the same "kept as a plain string, real meaning
/// lives server-side" convention this file already applies to `FolderResponse.color`.
public struct StoredFileSummaryResponse: Decodable, Identifiable, Hashable {
    public var id: String { fileId }
    public let fileId: String
    public let fileName: String
    public let contentType: String
    public let sizeBytes: Int64
    public let createdAtEpochMilli: Int64
    public let updatedAtEpochMilli: Int64
    public let folderId: String?
    public let scanStatus: String
}

/// Shape of one entry in `GET /folders`'s response array, and of what `POST /folders`/`PUT
/// /folders/{id}` return on success. `color` mirrors the server's `Folder#getColor()` - an
/// opaque, client-defined string (e.g. `"BLUE"`), `nil` if never explicitly set (this app falls
/// back to its own default - see `FolderColorOption.forName(_:)` in Theme.swift).
public struct FolderResponse: Decodable, Identifiable, Hashable {
    public var id: String { folderId }
    public let folderId: String
    public let ownerId: String
    public let name: String
    public let parentFolderId: String?
    public let createdAtEpochMillis: Int64
    public let modifiedAtEpochMillis: Int64
    public let color: String?
}

/// Body for `PUT /folders/{id}/color` - a separate route from `UpdateFolderRequest`'s, matching
/// the server's own separate handling (recoloring never touches name/parent).
public struct UpdateFolderColorRequest: Encodable {
    public let color: String?

    public init(color: String?) {
        self.color = color
    }
}

/// Shape of one entry in `GET /files/shared-with-me`'s response array - a file another account
/// shared with the caller, paired with that account's email (mirrors the server's
/// `SharedFileSummary`).
public struct SharedFileSummaryResponse: Decodable, Identifiable, Hashable {
    public var id: String { file.fileId }
    public let file: StoredFileSummaryResponse
    public let ownerEmail: String
}

/// Shape of one entry in `GET /folders/shared-with-me`'s response array - the folder counterpart
/// to `SharedFileSummaryResponse` (mirrors the server's `SharedFolderSummary`).
public struct SharedFolderSummaryResponse: Decodable, Identifiable, Hashable {
    public var id: String { folder.folderId }
    public let folder: FolderResponse
    public let ownerEmail: String
}

/// Response of `GET /folders/{id}/shared-contents` - the non-trashed files/subfolders directly
/// inside a folder reached via ownership or a share (mirrors the server's `SharedFolderContents`).
/// A share on an ancestor folder covers browsing into any descendant, so this same call also
/// works for a subfolder reached by navigating deeper into an already-shared folder.
public struct SharedFolderContentsResponse: Decodable {
    public let files: [StoredFileSummaryResponse]
    public let subfolders: [FolderResponse]
}

/// Shape of one entry in `GET /files/trash`'s response array - a soft-deleted file (via `DELETE
/// /files/{id}`) paired with when it becomes eligible for permanent removal under the server's
/// configured trash retention window (mirrors the server's `TrashedFileSummary`).
public struct TrashedFileSummaryResponse: Decodable, Identifiable, Hashable {
    public var id: String { file.fileId }
    public let file: StoredFileSummaryResponse
    public let purgeAtEpochMillis: Int64
}

/// Shape of one entry in `GET /folders/trash`'s response array - the folder counterpart to
/// `TrashedFileSummaryResponse` (mirrors the server's `TrashedFolderSummary`).
public struct TrashedFolderSummaryResponse: Decodable, Identifiable, Hashable {
    public var id: String { folder.folderId }
    public let folder: FolderResponse
    public let purgeAtEpochMillis: Int64
}

/// Response of `GET /auth/me` (bearer-gated) - the caller's own account id/email/admin flag.
public struct MeResponse: Decodable {
    public let authUserId: String
    public let emailAddress: String
    public let isAdmin: Bool
}

/// Response of `GET /cloudUsers/{id}` - mirrors `CloudUser`'s Gson-serialized fields server-side.
public struct CloudUserResponse: Decodable {
    public let authUserId: String
    public let timeStamp: Int64
    public let maxBytesToUpload: Int64
    public let currentUploadedBytes: Int64
}

/// Body Javalin's default error responses use (`BadRequestResponse` etc. all share this shape).
public struct ErrorResponse: Decodable {
    public let title: String
}

/// Response of `GET /cloudUsers/exists?email=` - answers "does any account exist under this
/// address", not scoped to the caller's own account. Used to live-check a grantee's address
/// before sharing, the same way `cloud-driver-platforms-desktop`'s `ShareDialog` does.
public struct EmailExistsResponse: Decodable {
    public let exists: Bool
}

/// Shape of one entry in `GET /search`'s response array - a filename/content match against the
/// caller's own account, scoped per-account (a search never matches another account's files).
public struct SearchResultResponse: Decodable, Identifiable, Hashable {
    public var id: String { storedFileId }
    public let storedFileId: String
    public let fileName: String
    public let folderId: String?
}

/// Shape of one entry in `GET /search/semantic`'s response array.
///
/// `SearchResultResponse` plus a `score` - the cosine similarity between the query and the file,
/// higher being closer. Kept as its own type rather than folding an optional score onto the
/// keyword shape, because the two answer different questions: a keyword search either matched or
/// it did not, whereas every semantic result matched to *some* degree and the score is what makes
/// the list interpretable at all.
public struct SemanticSearchResultResponse: Decodable, Identifiable, Hashable {
    public var id: String { storedFileId }
    public let storedFileId: String
    public let fileName: String
    public let folderId: String?
    public let score: Double
}

/// One member of a `DuplicateFileGroupResponse`.
public struct DuplicateFileEntryResponse: Decodable, Identifiable, Hashable {
    public var id: String { storedFileId }
    public let storedFileId: String
    public let fileName: String
    public let folderId: String?
}

/// Shape of one entry in `GET /files/duplicates`'s response array - files the server considers
/// near-identical in meaning, plus the group's weakest pairwise similarity.
///
/// **Not the same as byte-identical.** The server deduplicates identical content exactly and
/// invisibly; this is a similarity judgement meant for a human to review, so a client must present
/// it as a suggestion and must never delete anything on its own.
public struct DuplicateFileGroupResponse: Decodable, Hashable {
    public let files: [DuplicateFileEntryResponse]
    public let similarity: Double
}

/// Shape of one entry in `GET /files/{id}/tags`'s response array.
///
/// `confidence` is a *relative* similarity against a fixed label vocabulary, not a calibrated
/// probability - do not render it to a user as a percentage of correctness.
public struct TagSuggestionResponse: Decodable, Identifiable, Hashable {
    public var id: String { tag }
    public let tag: String
    public let confidence: Double
}

/// Body for `POST /files/{id}/public-link` - `expiresAtEpochMillis` `nil` creates a link that
/// never expires.
public struct CreatePublicFileLinkRequest: Encodable {
    public let expiresAtEpochMillis: Int64?

    public init(expiresAtEpochMillis: Int64? = nil) {
        self.expiresAtEpochMillis = expiresAtEpochMillis
    }
}

/// Shape of one entry in `GET /files/{id}/public-link`'s response array, and of what `POST
/// /files/{id}/public-link` returns on success - an unauthenticated, read-only link to one file's
/// content, reachable via `GET /public/files/{token}` with no bearer token at all.
public struct PublicFileLinkSummaryResponse: Decodable, Identifiable, Hashable {
    public var id: String { token }
    public let token: String
    public let createdAtEpochMillis: Int64
    public let expiresAtEpochMillis: Int64?
}

/// Shape of one entry in `GET /files/{id}/versions`'s response array - a previously-captured
/// version of a file's content (captured automatically whenever `PUT /files/{id}/content`
/// overwrites it). Deliberately narrower than `StoredFileSummaryResponse` - a version carries no
/// `fileName`/`contentType` of its own in this response, only what changed release to release.
public struct FileVersionSummaryResponse: Decodable, Identifiable, Hashable {
    public var id: Int { versionNumber }
    public let versionNumber: Int
    public let capturedAtEpochMillis: Int64
    public let sizeBytes: Int64
}

/// One entry in an activity feed page (`GET /activity`, `GET /files/{id}/activity`, `GET
/// /folders/{id}/activity`) - a narrowed view of the server's audit-log entry, decoding only the
/// fields this app actually displays (`Decodable` silently ignores the rest, e.g. `actorAuthUserId`/
/// `metadata`). `id` is synthesized from timestamp+target rather than decoded, since the server's
/// own entry id isn't one of the fields read here.
public struct ActivityEntryResponse: Decodable, Identifiable, Hashable {
    public var id: String { "\(timestampEpochMillis)-\(targetId ?? "")" }
    public let timestampEpochMillis: Int64
    public let action: String
    public let targetId: String?
}
