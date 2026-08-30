# cloud-driver-platform-rest

A plain, JavaFX-free Java client library for `cloud-driver`'s JWT-authenticated REST API: login, two-step e-mail-verified registration, list/upload/delete files, and OS-native session-token persistence. Talks to a running `cloud-driver` server purely over HTTP — it holds no database credentials and never sees them.

## Project structure

Maven `packaging=jar`, `groupId=de.lino.cloud.platform.rest`, `artifactId=cloud-driver-platform-rest`, module/package root `de.lino.cloud.platform.rest`. Its own Maven `<parent>` is the repo root `pom.xml` directly (not the `cloud-driver-platform` aggregator, even though it lives under that directory and is listed as one of its `<modules>` for reactor purposes).

Depends on exactly one third-party library, `gson` (2.11.0), and **nothing else in this repo** — no `cloud-driver-api`/`cloud-driver-auth`/`cloud-driver-plugin`/`cloud-driver-bootstrap` dependency at all. This is deliberate, matching every other "client must never see the database" boundary in this codebase: any JVM-based client (this repo's own `cloud-driver-platform-app`, a second desktop app, an Android app, a CLI, a backend integration) can depend on just this one module to talk to the server, without pulling in a database driver, encryption stack, or JavaFX.

Package layout:

```
de.lino.cloud.platform.rest.api
├── ApiClient.java              HTTP client for the REST API
├── SessionManager.java         ties ApiClient to a TokenStore, handles 401s
├── dto/Dtos.java                request/response record shapes
└── session/
    ├── TokenStore.java          store/load/clear contract
    ├── TokenStoreException.java
    ├── TokenStoreFactory.java   picks the right OS implementation
    ├── mac/MacKeychainTokenStore.java
    ├── windows/WindowsDpapiTokenStore.java
    ├── linux/LinuxSecretServiceTokenStore.java
    └── file/FileTokenStore.java (fallback)
```

Extracted from what used to be `cloud-driver-platform-app`'s own `de.lino.cloud.platform.app.api` package (repackaged `de.lino.cloud.platform.rest.api`) once a second, non-JavaFX consumer became a real need.

## Performance

- **Every network method has a true async form** (`loginAsync`, `uploadFileAsync`, ...) returning `CompletableFuture<T>`, built directly on `HttpClient#sendAsync`. The blocking sync forms are **not** `asyncMethod(...).join()` — `CompletableFuture#join()` ignores `Thread#interrupt()` until the future completes, whereas the blocking `HttpClient#send` responds to interruption immediately, which matters since callers in practice run these from a cancellable virtual thread. Both forms share one private `parseResponse` so behavior never drifts between them.
- **One `HttpClient` per `ApiClient` instance**, built once, requesting `HTTP_2` explicitly and reusing one connection pool for every call — concurrent calls against the same host transparently multiplex over one TCP connection when the server negotiates HTTP/2 (true of this deployment: Caddy fronts the Javalin/Jetty server and speaks HTTP/2 to the client).
- **A dedicated `Executors.newVirtualThreadPerTaskExecutor()`** backs every async call (`ApiClient#executor()`), so response parsing (Gson) and any caller-chained follow-up work (e.g. `SessionManager`'s own async methods, or a `TokenStore`'s blocking OS shell-out) never run on an internal JDK HTTP-client thread.
- **`listFilesStream()`** returns a lazy `Stream<StoredFileResponse>` backed by a `com.google.gson.stream.JsonReader` parsing one array element at a time directly off the still-open response body — never materializes the full `GET /files` array (every owned file's base64 content, no pagination) in memory at once. The caller must close the returned `Stream`.
- **`uploadFile(Path)`** streams the request body straight from disk via `BodyPublishers.ofFile` instead of requiring a `Files.readAllBytes` first, avoiding doubling peak memory for a large upload.
- **`uploadFilesAsync(Map, int)`** runs uploads genuinely concurrently, capped at `DEFAULT_MAX_CONCURRENT_TRANSFERS` (8) simultaneous transfers by default (each open upload holds its own file handle); `deleteFilesAsync` is left uncapped since a delete carries no body. Both wait for every transfer to finish (success or failure) before surfacing a result, and throw the first failure encountered only once every item has been attempted — matching `EntityDatabaseClient`'s own batch-operation convention on the server side.
- **Response bodies are read via `BodyHandlers.ofInputStream()`, never `ofString()`**, avoiding materializing a large response as a single `String` before parsing.

## Data handling

No persistence of its own beyond the session token (see Safety & security below). Entities it exchanges with the server, as plain Gson-serialized records in `api.dto.Dtos` (deliberately not shared code with the server — this module has no dependency on `cloud-driver-api`):

- `AuthRequest` / `ConfirmRegistrationRequest` / `AuthResponse` / `MessageResponse` — login/registration request-response shapes.
- `StoredFileResponse` — one file's metadata + base64 content, mirroring the server's `StoredFile`.
- `ErrorResponse` — the shape Javalin's default error responses use.

## Safety & security

- **The server, never this module, holds the database credentials or the encryption keys** — this module speaks only HTTP/JSON with the server described in CLAUDE.md's "JWT authentication for end-user clients" section.
- **Session token persistence goes through the OS's native secret storage where available**: `MacKeychainTokenStore` (via the `security` CLI), `WindowsDpapiTokenStore` (DPAPI via a helper process), `LinuxSecretServiceTokenStore` (via `secret-tool`/libsecret) — each shells out with `ProcessBuilder` rather than a native-binding library. `FileTokenStore` is a permission-restricted (`PosixFilePermission`) plain-file fallback used only when no real keychain/secret-service is available; `TokenStoreFactory.Result#usedFallback()` tells the caller this happened so it can warn the user rather than silently degrading security.
- **`SessionManager#handleFailure`** centralizes "token expired mid-use" handling: on a `401`, it clears both the in-memory token and the persisted copy, since a stale JWT (12h TTL, no refresh mechanism server-side) is worthless to keep around. Every other failure (network error, 404, 500, ...) is left untouched.
- **`ApiClient` is not thread-safe by design choice**, only by accident of `HttpClient` itself being thread-safe — the mutable in-memory token models a single logged-in session, matching a desktop app's single-user-at-a-time nature.

## Scalability

Purely a client — no server-side state to scale. `ApiClient` holds one mutable session token in memory (`AtomicReference<String>`) plus one `HttpClient`/virtual-thread executor per instance; nothing here is process-shared or needs to be, since one instance models one logged-in user session on one machine. Concurrent uploads/deletes scale via HTTP/2 multiplexing rather than opening additional TCP connections, bounded by `uploadFilesAsync`'s configurable concurrency cap to avoid exhausting local file descriptors or overwhelming the server.

## API surface

- **`ApiClient`** — the HTTP client itself: `login`/`register`/`confirmRegistration`, `uploadFile`/`uploadFilesAsync`, `listFiles`/`listFilesAsync`/`listFilesStream`, `deleteFile`/`deleteFilesAsync`, each with sync + async forms. Implements `AutoCloseable`.
- **`SessionManager`** — wraps an `ApiClient` + `TokenStore` so a session survives an app restart; `tryRestoreSession`, `login`, `register`/`confirmRegistration`, `logout`, `handleFailure`.
- **`Dtos`** — the request/response record shapes listed above.
- **`TokenStore`** (interface) — `store`/`load`/`clear`. `TokenStoreFactory.create()` picks the right OS-specific implementation automatically.
- **`ApiClient.ApiException`** — thrown for any non-2xx response or transport failure; carries the HTTP status code and `isUnauthorized()`.

## API usage + code sample

```java
import de.lino.cloud.platform.rest.api.ApiClient;
import de.lino.cloud.platform.rest.api.SessionManager;
import de.lino.cloud.platform.rest.api.session.TokenStoreFactory;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileResponse;

try (ApiClient apiClient = new ApiClient("https://auth.cloud-driver.de", "https://api.cloud-driver.de")) {
    TokenStoreFactory.Result tokenStore = TokenStoreFactory.create();
    SessionManager session = new SessionManager(apiClient, tokenStore.store());

    // Restore a previous session, or log in fresh.
    if (!session.tryRestoreSession()) {
        session.login("you@example.com", "hunter2");
    }

    // Upload a file straight from disk.
    apiClient.uploadFile(java.nio.file.Path.of("report.pdf"));

    // List everything owned by the logged-in user.
    for (StoredFileResponse file : apiClient.listFiles()) {
        System.out.println(file.fileName() + " (" + file.contentBase64().length() + " base64 chars)");
    }
} catch (ApiClient.ApiException | de.lino.cloud.platform.rest.api.session.TokenStoreException e) {
    e.printStackTrace();
}
```
