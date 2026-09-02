# cloud-driver-platforms-rest

A plain, UI-framework-free Java client library for `cloud-driver`'s JWT-authenticated REST API: login/two-step registration/password-reset/e-mail-change, file/folder CRUD, trash, file/folder sharing between accounts, admin (read-only) endpoints, live push over WebSocket, and OS-native session-token persistence. Talks to a running `cloud-driver` server purely over HTTP — it holds no database credentials and never sees them.

## Project structure

Maven `packaging=jar`, `groupId=de.lino.cloud.platforms.rest`, `artifactId=cloud-driver-platforms-rest`, module/package root `de.lino.cloud.platform.rest` (singular "platform" in the package, plural "platforms" in the Maven coordinates — a historical mismatch, not a typo to "fix"). Its Maven `<parent>` is the `cloud-driver-platforms` aggregator (`../pom.xml`), which is itself a plain child `<module>` of the repo root `pom.xml`.

Sits **outside** this repo's server-side dependency chain (`cloud-driver-api ← cloud-driver-auth ← cloud-driver-plugin ← cloud-driver-bootstrap`) entirely. Depends on exactly one third-party library, `gson` (2.11.0), and **nothing else in this repo** — no `cloud-driver-api`/`cloud-driver-auth`/`cloud-driver-plugin`/`cloud-driver-bootstrap` dependency at all. This is deliberate, matching every other "client must never see the database" boundary in this codebase: any JVM-based client (this repo's own `cloud-driver-platforms-desktop`, a second desktop app, an Android app, a CLI, a backend integration) can depend on just this one module to talk to the server, without pulling in a database driver, encryption stack, or any UI toolkit.

Package layout:

```
de.lino.cloud.platform.rest.api
├── ApiClient.java              HTTP client for the REST API (the bulk of this module's surface)
├── SessionManager.java         ties ApiClient to a TokenStore, handles session persistence + 401s
├── dto/Dtos.java                request/response record shapes (~30 records)
├── push/
│   └── LiveUpdateClient.java   WebSocket client for item-10 live push, auto-reconnecting
└── session/
    ├── TokenStore.java          store/load/clear contract
    ├── TokenStoreException.java
    ├── TokenStoreFactory.java   picks the right OS implementation, reports fallback use
    ├── mac/MacKeychainTokenStore.java
    ├── windows/WindowsDpapiTokenStore.java
    ├── linux/LinuxSecretServiceTokenStore.java
    └── file/FileTokenStore.java (fallback)
```

Extracted from what used to be `cloud-driver-platforms-desktop`'s own `de.lino.cloud.platform.app.api` package (repackaged `de.lino.cloud.platform.rest.api`) once a second, non-UI consumer became a real need; `MacKeychainTokenStore`'s keychain service identifier is still the literal string `"de.lino.cloud.platform.app"`, a deliberate holdover so renaming it doesn't orphan an already-stored token.

## Performance

- **Every network method has a true async form** (`loginAsync`, `uploadFileAsync`, `shareFileAsync`, ...) returning `CompletableFuture<T>`, built directly on `HttpClient#sendAsync`. The blocking sync forms are **not** `asyncMethod(...).join()` — `CompletableFuture#join()` ignores `Thread#interrupt()` until the future completes, whereas the blocking `HttpClient#send` overload responds to interruption immediately, which matters since callers in practice run these from a cancellable virtual thread. Both forms share one private `parseResponse` (in `Class<T>`- and generic-`Type`-based overloads, the latter needed to deserialize a `Page<T>`/list-of-record response whose type argument a bare `Class` literal can't carry) so behavior never drifts between them.
- **One `HttpClient` per `ApiClient` instance**, built once, requesting `HTTP_2` explicitly and reusing one connection pool for every call — concurrent calls against the same host transparently multiplex over one TCP connection when the server negotiates HTTP/2 (true of this deployment: a reverse proxy fronts the Javalin/Jetty server and speaks HTTP/2 to the client).
- **A dedicated `Executors.newVirtualThreadPerTaskExecutor()`** backs every async call (`ApiClient#executor()`), so response parsing (Gson) and any caller-chained follow-up work (e.g. `SessionManager`'s own async methods, or a `TokenStore`'s blocking OS shell-out) never run on an internal JDK HTTP-client thread.
- **Transparent, single-retry-on-401 via the held refresh token.** `send`/`sendAsync` — the two methods every authenticated call funnels through — detect a `401`, call `refresh()`/`refreshAsync()` to rotate the held refresh token for a fresh pair, rebuild the original request with the new access token (`HttpRequest` has no public "copy with one header changed" builder, so this reconstructs one from the original request's own getters), and retry once. A caller only ever sees the original `401` if that retry also fails.
- **`listFilesStream()`** returns a lazy `Stream<StoredFileSummaryResponse>` backed by a `com.google.gson.stream.JsonReader` parsing one array element at a time directly off the still-open response body — avoids materializing the whole unpaginated `GET /files` array in memory at once. The caller must close the returned `Stream`. `listFilesPage`/`listFoldersPage` (cursor-paginated) are the alternative for a folder that may hold many entries.
- **`uploadFile(Path, ...)`/`downloadFileToPath(...)`** stream straight from/to disk via `BodyPublishers.ofFile`/`BodyHandlers.ofFile`, never materializing a large file's content as a single in-memory `byte[]`/`String`. Both accept an optional `LongConsumer onBytesTransferred` callback (cumulative bytes so far, fired from an internal HTTP I/O thread — keep it cheap/non-blocking) backing real progress reporting.
- **`uploadFilesAsync(Map, int)`/`deleteFilesAsync`** run transfers genuinely concurrently over the same HTTP/2 connection, uploads capped at `DEFAULT_MAX_CONCURRENT_TRANSFERS` (8) by default (each open upload holds its own file handle); deletes are uncapped (no body/file handle to bound). Both wait for every item to finish (success or failure) before surfacing a result, throwing the first failure only once every item has been attempted — matching `EntityDatabaseClient`'s own batch-operation convention on the server side.
- **Response bodies are read via `BodyHandlers.ofInputStream()`, never `ofString()`**, avoiding materializing a large response as a single `String` before parsing.
- **`LiveUpdateClient` reconnects automatically on any drop** — a fixed 5-second delay (not exponential backoff, matching this codebase's "simple, not maximally clever" trade-off elsewhere), retrying indefinitely until `close()`.

## Data handling

No persistence of its own beyond the session token (see Safety & security below). Entities it exchanges with the server are plain Gson-serialized records in `api.dto.Dtos` (deliberately not shared code with the server — this module has no dependency on `cloud-driver-api`), grouped by concern:

- **Auth/session** — `AuthRequest`/`LoginOutcome`/`AuthResponse`, `TwoFactorLoginRequest`/`TwoFactorSetupResponse`/`ConfirmTwoFactorSetupRequest`/`DisableTwoFactorRequest`, `RefreshRequest`, `ConfirmRegistrationRequest`/`RequestPasswordResetRequest`/`ConfirmPasswordResetRequest`, `ChangeEmailRequest`/`ConfirmChangeEmailRequest`, `MessageResponse`, `MeResponse` (the caller's own id/email/admin flag).
- **Files/folders** — `StoredFileResponse` (one file's metadata + base64 content), `StoredFileSummaryResponse` (descriptive fields only, no content — what a listing returns), `FolderResponse`, `CreateFolderRequest`/`UpdateFolderRequest`/`MoveFileRequest`, `Page<T>` (cursor-pagination envelope).
- **Trash** — `TrashedFileSummaryResponse`/`TrashedFolderSummaryResponse` (a file/folder summary paired with `purgeAtEpochMillis`).
- **Sharing (item 9)** — `ShareRequest`, `SharedFileSummaryResponse`/`SharedFolderSummaryResponse` (paired with the sharing account's email), `SharedFolderContentsResponse` (a shared folder's own files/subfolders), `EmailExistsResponse` (live grantee-email check).
- **Admin (read-only)** — `AuthUserResponse`, `AuditLogEntryResponse`, `CloudUserResponse`.
- **Transport** — `ErrorResponse` (the shape Javalin's default error responses use).

## Safety & security

- **The server, never this module, holds the database credentials or the encryption keys** — this module speaks only HTTP/JSON with the server described in `CLAUDE.md`'s "JWT authentication for end-user clients" section.
- **Session token persistence goes through the OS's native secret storage where available**: `MacKeychainTokenStore` (via the `security` CLI), `WindowsDpapiTokenStore` (DPAPI via a helper process), `LinuxSecretServiceTokenStore` (via `secret-tool`/libsecret) — each shells out with `ProcessBuilder` rather than a native-binding library. `FileTokenStore` is a permission-restricted (`PosixFilePermission`) plain-file fallback used only when no real keychain/secret-service is available; `TokenStoreFactory.Result#usedFallback()` tells the caller this happened so it can warn the user rather than silently degrading security.
- **Two tokens are held and persisted together**: a short-lived (12h) access JWT and a longer-lived (30-day), opaque, single-use refresh token, rotated on every use (see Performance above). `SessionManager` encodes both into one opaque value a `TokenStore` implementation can carry, so restoring a session no longer requires the user to log in again just because the access token happened to expire since the app last ran.
- **`SessionManager#handleFailure`** centralizes "still unauthorized after the automatic refresh retry" handling: on a `401` that survives `ApiClient`'s own retry, it clears both the in-memory tokens and the persisted copy, since a refresh token that's itself expired/revoked is worthless to keep around. Every other failure (network error, 404, 500, ...) is left untouched.
- **`ApiClient` is not thread-safe by design choice**, only by accident of `HttpClient` itself being thread-safe — the mutable in-memory tokens model a single logged-in session, matching a desktop app's single-user-at-a-time nature.
- **`LiveUpdateClient` authenticates the WebSocket handshake via the `Authorization` header directly** (`WebSocket.Builder#header`), unlike a browser client, which can't set a custom header on a handshake and must instead rely on the server's `?token=` query-parameter fallback.

## Scalability

Purely a client — no server-side state to scale. `ApiClient` holds two mutable session tokens in memory (`AtomicReference<String>` each) plus one `HttpClient`/virtual-thread executor per instance; nothing here is process-shared or needs to be, since one instance models one logged-in user session on one machine. Concurrent uploads/deletes scale via HTTP/2 multiplexing rather than opening additional TCP connections, bounded by `uploadFilesAsync`'s configurable concurrency cap to avoid exhausting local file descriptors or overwhelming the server.

## API surface

- **`ApiClient`** — the HTTP client itself. Auth: `login`/`completeTwoFactorLogin`/`register`/`confirmRegistration`/`requestPasswordReset`/`confirmPasswordReset`/`requestEmailChange`/`confirmEmailChange`/`refresh`/`revokeRefreshToken`/`beginTwoFactorSetup`/`confirmTwoFactorSetup`/`disableTwoFactor`. Files: `uploadFile`/`uploadFilesAsync`/`listFiles`/`listFilesPage`/`listFilesStream`/`downloadFile`/`downloadFileToPath`/`deleteFile`/`deleteFilesAsync`/`moveFile`. Folders: `createFolder`/`listFolders`/`listFoldersPage`/`updateFolder`/`deleteFolder`. Trash: `listDeletedFiles`/`restoreFile`/`listDeletedFolders`/`restoreFolder`/`emptyTrash`. Sharing: `shareFile`/`revokeFileShare`/`listFileShares`/`listSharedWithMe`/`shareFolder`/`revokeFolderShare`/`listFolderShares`/`listSharedFoldersWithMe`/`listSharedFolderContents`/`checkCloudUserExists`. Admin: `listAdminAuthUsers`/`listAdminAuditLog`. Misc: `getCloudUser`/`getMe`. Every method above has a sync + `*Async` form. Implements `AutoCloseable`.
- **`SessionManager`** — wraps an `ApiClient` + `TokenStore` so a session survives an app restart; `tryRestoreSession`, `login`/`completeTwoFactorLogin`, `register`/`confirmRegistration`, `confirmPasswordReset`, `logout`, `handleFailure`.
- **`LiveUpdateClient`** — item-10 live push over WebSocket; `connect()`/`close()`, a `Listener` callback delivering `Update(table, operation, id)` notifications, auto-reconnecting.
- **`Dtos`** — the request/response record shapes listed above.
- **`TokenStore`** (interface) — `store`/`load`/`clear`. `TokenStoreFactory.create()` picks the right OS-specific implementation automatically and reports whether it fell back to the plain-file store.
- **`ApiClient.ApiException`** — thrown for any non-2xx response or transport failure; carries the HTTP status code and `isUnauthorized()`.

## API usage + code sample

```java
import de.lino.cloud.platform.rest.api.ApiClient;
import de.lino.cloud.platform.rest.api.SessionManager;
import de.lino.cloud.platform.rest.api.session.TokenStoreFactory;
import de.lino.cloud.platform.rest.api.dto.Dtos.LoginOutcome;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse;

try (ApiClient apiClient = new ApiClient("https://api.cloud-driver.de", "https://api.cloud-driver.de")) {
    TokenStoreFactory.Result tokenStore = TokenStoreFactory.create();
    SessionManager session = new SessionManager(apiClient, tokenStore.store());

    // Restore a previous session, or log in fresh.
    if (!session.tryRestoreSession()) {
        LoginOutcome outcome = session.login("you@example.com", "hunter2");
        if (outcome.twoFactorRequired()) {
            session.completeTwoFactorLogin(outcome.pendingToken(), /* code from an authenticator app */ "123456");
        }
    }

    // Upload a file straight from disk, optionally into a folder (null = root).
    java.nio.file.Path report = java.nio.file.Path.of("report.pdf");
    apiClient.uploadFile(report.getFileName().toString(), report, null);

    // List everything owned by the logged-in user at the root (descriptive fields only, no content).
    for (StoredFileSummaryResponse file : apiClient.listFiles()) {
        System.out.println(file.fileName() + " (" + file.sizeBytes() + " bytes)");
    }

    // Share a file with another account.
    apiClient.shareFile(apiClient.listFiles().get(0).fileId(), "colleague@example.com");
} catch (ApiClient.ApiException | de.lino.cloud.platform.rest.api.session.TokenStoreException e) {
    e.printStackTrace();
}
```
