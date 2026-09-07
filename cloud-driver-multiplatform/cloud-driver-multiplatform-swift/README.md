# cloud-driver-multiplatform-swift

A plain, UI-framework-free Swift client library for `cloud-driver`'s JWT-authenticated REST API: login/two-step registration/password-reset/e-mail-change, file/folder CRUD, presigned direct-to-storage transfer, file/folder sharing between accounts, and trash. Talks to a running `cloud-driver` server purely over HTTP — it holds no database credentials and never sees them. Product/import name: **`CloudDriverSwift`**.

**Extracted (2026-09-07)** out of [`cloud-driver-platforms-mobile`](../../cloud-driver-platforms/cloud-driver-platforms-mobile/README.md)'s own `Networking/` folder into this standalone Swift package, so that app could go GUI-only — the same "client SDK per ecosystem" role [`cloud-driver-multiplatform-java`](../cloud-driver-multiplatform-java/README.md) (Java) and [`cloud-driver-multiplatform-python`](../cloud-driver-multiplatform-python/README.md) (Python) already play for their own ecosystems, all three siblings under `cloud-driver-multiplatform`, all three talking to the exact same REST/WebSocket contract. `cloud-driver-platforms-mobile` is currently this package's only consumer, but nothing about it is mobile-specific — any Swift target (macOS included, see `Package.swift`'s `platforms:`) can depend on it.

## Project structure

A plain Swift Package Manager library, **not** an Xcode project of its own — `Package.swift` declares one product/target, `CloudDriverSwift`, with **no third-party dependencies** (`Foundation`/`CryptoKit`/`Security` only, matching every file's own pre-extraction imports exactly). `cloud-driver-platforms-mobile` depends on it as a **local path** package (`project.yml`'s `packages: CloudDriverSwift: { path: ../../cloud-driver-multiplatform/cloud-driver-multiplatform-swift }`) — resolved straight off this same repo checkout by `xcodegen generate`/Xcode, no git URL, no separate install step (unlike `cloud-driver-multiplatform-java`, which needs `mvn install` into `~/.m2` first).

```
Sources/CloudDriverSwift/
├── APIClient.swift          actor wrapping URLSession - one method per REST endpoint, plus
│                             presigned-upload/download orchestration and transparent 401-retry
├── Dtos.swift                Codable structs mirrored 1:1 against the server's JSON shapes
├── SessionManager.swift      ties APIClient's in-memory tokens to KeychainTokenStore
└── KeychainTokenStore.swift  persists the access+refresh token pair in the iOS/macOS Keychain
```

Every type/member an app actually calls is `public` (added during the extraction — everything
used to be plain `internal`, sufficient when this code lived inside the app's own target); a
handful of members only ever called *within* this package (`APIClient`'s low-level request-building
helpers, `KeychainTokenStore` itself — only ever reached through `SessionManager`, never directly)
stay at the default `internal`/`private` access, unreachable from outside this package on purpose.

Sits **outside** this repo's server-side dependency chain (`cloud-driver-api ← cloud-driver-auth ← cloud-driver-plugin ← cloud-driver-bootstrap`) entirely, and depends on **nothing else in this repo** — the same "client must never see the database" boundary `cloud-driver-multiplatform-java`/`cloud-driver-multiplatform-python` also enforce.

## Performance

- **`APIClient` is a Swift `actor`**, not a class with manual locking — every token read/write is already serialized without extra ceremony.
- **Every authenticated call transparently retries once after a `401`** (`execute`'s own retry branch) by exchanging the held refresh token first, mirroring the exact contract `cloud-driver-multiplatform-java`'s Java `ApiClient` and the server's refresh-token design (root `CLAUDE.md`, "Refresh tokens") both document. A caller only ever sees the original `401` if that retry also fails.
- **Presigned direct-to-storage transfer** (`uploadFileViaPresignedURL`/`downloadFileViaPresignedURL`) streams straight to/from disk via `URLSession.upload(for:fromFile:)`/`download(for:)` — a file's bytes are never fully materialized into memory as `Data` on that path. Both accept an optional `onProgress: (@MainActor (Int64, Int64) -> Void)?` callback, wired up via a per-request `URLSessionTaskDelegate` (`ProgressForwardingDelegate`), hopping onto `@MainActor` before invoking the callback so a caller can update `@Published` UI state directly with no extra dispatch of its own.
- **Falls back to the non-presigned `uploadFile(fileName:data:folderId:)`/`downloadFileContent(fileId:)` on a `503`** — the signal a deployment hasn't configured presigned transfer, or a particular file isn't eligible for it. That fallback path is a single, whole-body `Data` request/response (not streamed) — the caller decides when to fall back, this library doesn't do it automatically, since the caller usually wants to check a size cap first (see the mobile app's own `AppViewModel` for that pattern).
- **SHA-256 checksums for a presigned upload are computed streamed**, via `InputStream` reading fixed-size chunks — never loading the whole file into memory just to hash it.

## Data handling

No persistence of its own beyond the session token (see Safety & security below). Every DTO in `Dtos.swift` is a plain `Codable` `struct`, decoded/encoded directly against the server's JSON — no server-side type is shared or referenced (this package has no dependency on `cloud-driver-api`), the same "hand-mirrored, not shared code" convention `cloud-driver-multiplatform-java`'s `Dtos.java`/`cloud-driver-multiplatform-python`'s `models.py` both document for themselves. `Page<T>` is a real generic `Codable` type — Swift handles this directly, no `TypeToken`/reflection workaround needed the way the JVM client requires for the same cursor-pagination envelope.

## Safety & security

- **The server, never this package, holds the database credentials or the encryption keys** — this package speaks only HTTPS/JSON with the server described in the root `CLAUDE.md`'s "JWT authentication for end-user clients" section.
- **Session token persistence goes through the OS's own Keychain services** (`KeychainTokenStore`, `Security` framework, `kSecClassGenericPassword`) — no subprocess/shell-out involved, unlike `cloud-driver-multiplatform-java`'s `MacKeychainTokenStore`/etc., which reach the same OS keychain via a CLI. Stored under `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` — readable while the app runs in the background (e.g. a scheduled refresh) without requiring a freshly-unlocked device, while deliberately **excluded** from encrypted device backups and iCloud Keychain sync, so a 30-day-lived refresh token never travels to a different physical device via a backup/restore.
- **Two tokens are held and persisted together**: a short-lived (12h) access JWT and a longer-lived (30-day), opaque, single-use refresh token, rotated on every use. `SessionManager` installs a rotation handler on `APIClient` (`setTokensRotatedHandler`) so *every* token rotation — an explicit login/register/reset call, and a transparent 401-triggered refresh alike — gets persisted to the Keychain, not just the explicit ones; a silent refresh that's never written back is exactly the kind of bug that causes "I have to log in again after closing the app" (see the root `CLAUDE.md`'s own incident write-up for the desktop client's equivalent bug, fixed the same way).
- **`SessionManager.tryRestoreSession()` confirms a persisted token is still actually valid** (`GET /auth/me`) before trusting it, clearing both the in-memory tokens and the Keychain entry on any failure rather than leaving a dead token around.
- **`APIClient` is not thread-safe by manual design, only by being an `actor`** — the mutable in-memory tokens model a single logged-in session, matching a mobile/desktop app's single-user-at-a-time nature.

## Scalability

Purely a client — no server-side state to scale. `APIClient` holds two optional `String` tokens as actor-isolated state plus one `URLSession`; nothing here is process-shared or needs to be, since one instance models one logged-in user session on one device.

## API surface

- **`APIClient`** — the HTTP client itself, a `public actor` reached via `APIClient.shared` (hardcoded to `https://api.cloud-driver.de` — change and rebuild to point elsewhere) or `APIClient(baseURL:)` directly. Auth: `login`/`register`/`confirmRegistration`/`requestPasswordReset`/`confirmPasswordReset`/`me`/`cloudUser`/`requestEmailChange`/`confirmEmailChange`. Files: `listFiles`/`listFilesPage`/`uploadFile`/`downloadFileContent`/`deleteFile`/`moveFile`/`renameFile`/`uploadFileViaPresignedURL`/`downloadFileViaPresignedURL`. Folders: `listFolders`/`listFoldersPage`/`createFolder`/`deleteFolder`/`updateFolder`/`updateFolderColor`. Sharing: `listSharedFilesWithMe`/`listSharedFoldersWithMe`/`sharedFolderContents`/`shareFile`/`revokeFileShare`/`listFileShares`/`shareFolder`/`revokeFolderShare`/`listFolderShares`. Trash: `listDeletedFiles`/`listDeletedFolders`/`restoreFile`/`restoreFolder`/`emptyTrash`. Every method is `async throws`, no separate sync/callback-based form (Swift concurrency covers both use cases from one implementation).
- **`SessionManager`** — a `@MainActor public final class` tying `APIClient` to `KeychainTokenStore` so a session survives an app restart; `init(client:)`, `tryRestoreSession()`, `persistCurrentSession()`, `clearSession()`.
- **`Dtos.swift`** — every `Encodable`/`Decodable` request/response `struct` listed in "Data handling" above.
- **`APIError`** — thrown for any non-2xx response or transport failure: `.network(Error)`, `.server(status: Int, message: String)`, `.decoding(Error)`, `.notAuthenticated`. Conforms to `LocalizedError` — `error.localizedDescription`/`errorDescription` is directly display-ready.

## API usage + code sample

```swift
import CloudDriverSwift

let client = APIClient.shared
let sessionManager = SessionManager(client: client)

// Restore a previous session, or log in fresh.
if await !sessionManager.tryRestoreSession() {
    _ = try await client.login(email: "you@example.com", password: "hunter2!A")
    await sessionManager.persistCurrentSession()
}

// Upload a file straight from disk via the presigned path, falling back to the
// non-presigned route on a 503 (this deployment hasn't configured direct-to-storage transfer).
let fileURL = URL(fileURLWithPath: "report.pdf")
do {
    _ = try await client.uploadFileViaPresignedURL(
        fileName: fileURL.lastPathComponent, fileURL: fileURL, folderId: nil)
} catch APIError.server(let status, _) where status == 503 {
    let data = try Data(contentsOf: fileURL)
    _ = try await client.uploadFile(fileName: fileURL.lastPathComponent, data: data, folderId: nil)
}

// List everything owned at the root.
for file in try await client.listFiles(folderId: nil) {
    print("\(file.fileName) (\(file.sizeBytes) bytes)")
}

// Folders, sharing, trash - same "one async throws call" shape throughout:
let invoices = try await client.createFolder(name: "Invoices", parentFolderId: nil)
let firstFileId = try await client.listFiles(folderId: nil).first!.fileId
try await client.shareFile(fileId: firstFileId, granteeEmail: "colleague@example.com")
_ = try await client.listSharedFilesWithMe()

await sessionManager.clearSession()
```

Every method throws `APIError` on failure — catch it directly (as shown above with `.server`) rather than a generic `Error`, to branch on the specific failure kind (`.server(status:message:)` for a mapped HTTP failure, `.notAuthenticated` for a missing refresh token, etc.).
