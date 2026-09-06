# API Usage Guide

This page is task-oriented: for each way a caller actually reaches `cloud-driver`, it shows the
minimal working code. For the full REST route table, see [api-reference.md](api-reference.md); for
a single module's complete contract (every method, every exception), see that module's own
`README.md` linked from the [root README](../README.md)'s module map. This page exists so those
two don't have to be cross-referenced by hand just to write a first working call.

## Which layer do I actually want?

| I want to... | Use |
|---|---|
| Build a new backend feature that runs inside the `cloud-driver-bootstrap` process | The in-process Java API — [§1](#1-in-process-java-api-same-jvm) |
| Talk to a running deployment from any language, over the network | The REST API — [§2](#2-the-rest-api-over-http) |
| Talk to it from a JVM app without hand-rolling HTTP | `cloud-driver-platforms-rest`'s `ApiClient` — [§3](#3-java-client-library-cloud-driver-platforms-rest) |
| Talk to it from the Kotlin/Compose desktop app's own code | `CloudDriverClient` — [§4](#4-kotlin-desktop-client) |
| Talk to it from the Swift/iOS app's own code | `APIClient` — [§5](#5-swift-ios-client) |
| Inspect/administer a running deployment as an operator | The interactive terminal — [§6](#6-operator-terminal-commands) |

Everything in §3–§5 ultimately calls the same REST routes described in §2; they exist so three very
different runtimes (plain JVM, Kotlin Multiplatform/Compose Desktop, Swift/SwiftUI) don't each have
to hand-roll HTTP, JSON, retry-on-401, and OS credential storage from scratch.

## 1. In-process Java API (same JVM)

Everything below lives in `cloud-driver-api` (contracts) with implementations in
`cloud-driver-plugin`/`cloud-driver-auth`. It's what you use when writing a new
`cloud-driver-extensions-*` feature module, or any other code that runs inside the
`cloud-driver-bootstrap` process itself. See `cloud-driver-api/README.md` for the complete contract
of every class named here.

### 1.1 Get hold of `CloudDriver`

Exactly one `CloudDriver` is installed process-wide. `cloud-driver-bootstrap`'s `main` does this
once, at startup; everything else (extensions, terminal commands, event handlers) just reaches the
already-installed instance:

```java
// Once, at process startup (cloud-driver-plugin):
DatabaseProvider databaseProvider = DatabaseProvider.create(DatabaseType.POSTGRE_SQL, credentials);
EnvelopeEncryptionService envelopeEncryptionService =
        new EnvelopeEncryptionService(new AwsKmsKeyEncryptionService(region, kmsKeyId));

CloudDriver cloudDriver = DefaultCloudDriver.setInstance(databaseProvider, envelopeEncryptionService);

// From anywhere else in the same process, afterward:
CloudDriver driver = CloudDriver.getInstance();
driver.getFactoryContainer().getDataFactory();   // entities
driver.getFactoryContainer().getFileFactory();   // files
driver.getFactoryContainer().getExtensionFactory();
driver.getFactoryContainer().getEventFactory();
driver.getFactoryContainer().getRestFactory();   // always the unauthenticated instance - see 1.6
driver.getServiceContainer().getAuthService();       // null until cloud-driver-extensions-rest has started
driver.getServiceContainer().getCloudUserService();  // same
```

### 1.2 `DataFactory` — persisting entities

Any `de.lino.database.database.entity.Serialized` subclass can be persisted, envelope-encrypted
transparently on the way in and out:

```java
final class CustomerRecord extends Serialized {
    private final int id;
    private final String iban;

    CustomerRecord(int id, String iban) { this.id = id; this.iban = iban; }

    @Override
    public List<String> keysOf() {
        return List.of(String.valueOf(id)); // first element = primary key
    }
}

DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();

dataFactory.register(new CustomerRecord(42, "DE00..."));              // insert-or-update
CustomerRecord fetched = dataFactory.fetch("42", CustomerRecord.class);        // throws if absent
Optional<CustomerRecord> maybe = dataFactory.findById("42", CustomerRecord.class); // empty() if absent
List<CustomerRecord> all = dataFactory.getEntities(CustomerRecord.class);
dataFactory.delete("42", CustomerRecord.class);

// Every sync method above has a *Async counterpart, generated once on DataFactory itself:
dataFactory.registerAsync(new CustomerRecord(43, "DE01..."))
        .thenRun(() -> System.out.println("stored"));
```

`reload(CustomerRecord.class)` re-reads that type's section from the database — needed if a
*different* process (or a different `DataFactory` instance) wrote a row this one hasn't seen yet;
see `cloud-driver-plugin/README.md`'s "Cross-process staleness" note for why this isn't automatic.

### 1.3 `FileFactory` — files, and offline-safe uploads

`StoredFile` is itself a `Serialized` entity, so it goes through the exact same pipeline as any
other record, plus a double integrity check on the way back out (AEAD tag, then a plaintext
checksum):

```java
FileFactory fileFactory = CloudDriver.getInstance().getFactoryContainer().getFileFactory();

StoredFile report = new StoredFile("report-1", "quarterly-report.pdf", pdfBytes);
fileFactory.upload(report);

StoredFile downloaded = fileFactory.download("report-1");
downloaded.downloadToDevice(Path.of("/tmp/downloads")); // re-creates the file locally under its own name

fileFactory.delete("report-1");
```

`FileFactory.upload` is already offline-safe — no wrapping needed. If connectivity is down (per
`CloudDriver.getInstance().getConnectivityChecker()`), the file is queued into a
`PendingUploadCache` instead of failing; run a `PendingUploadScheduler` to retry it once
connectivity returns:

```java
PendingUploadScheduler scheduler = new PendingUploadScheduler(
        dataFactory, ((DefaultFileFactory) fileFactory).getPendingUploadCache(),
        cloudDriver.getConnectivityChecker());
scheduler.start(Duration.ofSeconds(30));
```

### 1.4 `ExtensionFactory` — writing a feature module

A feature module (everything under `cloud-driver-extensions-*`) is a class extending `Extension`
with lifecycle hooks, discovered from a jar dropped into the configured extensions folder — see
`docs/architecture.md`'s "How the backend actually runs" section:

```java
public final class DemoExtension extends Extension {
    @Override public void onLoading() { /* prepare resources */ }
    @Override public void onRunning(String[] args) {
        this.cloudDriver().getTerminal().getCommandService().register(new MyCommand());
    }
    @Override public void onEnding() { /* release resources */ }
    @Override public void onException(RuntimeException reason) { /* report/recover */ }
}
```

```json
{ "name": "my-extension", "version": "1.0.0", "dependencies": ["cloud-driver-bootstrap"] }
```

`extension.json` (in the module's `resources` folder) is required — `name`/`version` are mandatory,
`dependencies` names other extensions by *their own* `extension.json` `name`, and
`ExtensionFactory#startAll` won't start this one until every declared dependency is registered and
already `RUNNING`. Registration itself is manual (`extensionFactory.register(new DemoExtension())`)
and normally only ever happens automatically via the folder scan — you rarely call it by hand.

### 1.5 `EventFactory` — reacting to something happening

One singleton instance per registered class, constructed reflectively by the factory itself:

```java
public final class OrderPlacedEvent extends Event {
    @Override
    public void handle(JsonDocument properties) {
        String orderId = properties.get("orderId", String.class);
        // ... react ...
    }
}

EventFactory eventFactory = CloudDriver.getInstance().getFactoryContainer().getEventFactory();
eventFactory.registerEvent(OrderPlacedEvent.class);

eventFactory.dispatch(OrderPlacedEvent.class, new JsonDocument().append("orderId", "42"));
```

Two built-in events already ship and fire on their own: `DatabaseWatchEvent`
(`de.lino.cloud.api.event.database`) fires on a Postgres change notification (see
`cloud-driver-extensions-watcher/README.md`), and `PendingUploadEvent` fires once a queued offline
upload from §1.3 finally succeeds. Register a handler for either the same way as above.

### 1.6 `RestFactory` — exposing HTTP routes

`CloudDriver.getInstance().getFactoryContainer().getRestFactory()` is **always the unauthenticated
instance** — fine for local development, never for a real deployment. Two other constructors gate
every route; construct one directly instead of using the `CloudDriver` facet:

```java
// Static key, one header check - simplest option for a service-to-service integration:
RestFactory guarded = new DefaultRestFactory(dataFactory, apiKey); // ApiKey from cloud-driver-api

guarded.register("/notes", NoteRecord.class); // POST /notes
guarded.fetch("/notes", NoteRecord.class);     // GET /notes/{id}, GET /notes
guarded.update("/notes", NoteRecord.class);    // PUT /notes/{id}
guarded.delete("/notes", NoteRecord.class);    // DELETE /notes/{id}
guarded.start("127.0.0.1", 8080);              // register every verb *before* start()
```

```java
// Per-user JWT - what cloud-driver-extensions-rest actually stands up for end-user clients:
RestFactory api = new DefaultRestFactory(dataFactory, authService, cloudUserService);
api.start("0.0.0.0", 8080);
```

All four verbs (`register`/`fetch`/`update`/`delete`) must be called before `start(...)` —
routes are assembled once, up front. Only entities implementing `Owned` are scoped to the calling
user's own data on the JWT-gated instance; see `cloud-driver-api/README.md`'s `Owned` section.

### 1.7 `cloud-driver-auth` services — accounts, files, sharing

`AuthService` (account lifecycle → JWTs) and `CloudUserService` (per-account file/folder
bookkeeping) are what `cloud-driver-extensions-rest` wires the REST routes in §2 to. Wiring them up
directly is useful for a terminal command, a batch job, or a test:

```java
AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);
ICloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService);
IAuthService authService = new AuthService(
        dataFactory, passwordHasher, jwtSigner, emailSender, cloudUserService, auditLogService);

// Registration is two-step and e-mail-verified:
authService.register("jane@example.com", "Str0ng!Pass".toCharArray());
AuthTokens tokens = authService.confirmRegistration("jane@example.com", codeFromEmail);

// Everyday use, once registered:
AuthTokens login = authService.login("jane@example.com", "Str0ng!Pass".toCharArray());
String userId = authService.validate(login.accessToken());

StoredFile uploaded = cloudUserService.uploadFile(userId, "report.pdf", pdfBytes, null); // null = root folder
cloudUserService.shareFile(userId, uploaded.fileId(), "colleague@example.com");
cloudUserService.deleteFile(userId, uploaded.fileId());   // soft delete - moves to trash, revokes the share
cloudUserService.restoreFile(userId, uploaded.fileId());  // back out of trash, stays unshared
```

See `cloud-driver-auth/README.md` for the full method list (folders, trash, sharing, quotas,
password reset, e-mail change, refresh tokens, admin).

## 2. The REST API, over HTTP

The full route table lives in [api-reference.md](api-reference.md); this section shows the shape of
an actual request/response for the flows a client goes through most often. Every route other than
the six auth-flow ones below requires `Authorization: Bearer <access-token>`.

### 2.1 Register, confirm, log in

```bash
# Step 1: e-mails a 6-digit code, does not create the account yet.
curl -X POST https://api.cloud-driver.de/auth/register \
     -H "Content-Type: application/json" \
     -d '{"username":"jane@example.com","password":"Str0ng!Pass9"}'
# -> 202 Accepted  {"message": "..."}

# Step 2: confirm the code - this is what actually creates the account.
curl -X POST https://api.cloud-driver.de/auth/register/confirm \
     -H "Content-Type: application/json" \
     -d '{"username":"jane@example.com","code":"123456"}'
# -> 201 Created  {"token": "<jwt>", "refreshToken": "<opaque>"}

# Logging in later:
curl -X POST https://api.cloud-driver.de/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"jane@example.com","password":"Str0ng!Pass9"}'
# -> 200 OK  {"token": "<jwt>", "refreshToken": "<opaque>"}

# The access token expires after 12h - exchange the refresh token for a fresh pair
# (the old refresh token is invalidated as part of this call - store the new one):
curl -X POST https://api.cloud-driver.de/auth/refresh \
     -H "Content-Type: application/json" \
     -d '{"refreshToken":"<opaque>"}'
```

### 2.2 Upload, list, download, move a file

```bash
TOKEN="<jwt>"

# The upload body is the raw file bytes - not JSON - fileName travels in the query string.
curl -X POST "https://api.cloud-driver.de/files?fileName=report.pdf" \
     -H "Authorization: Bearer $TOKEN" \
     --data-binary @report.pdf
# -> 201 Created  {"fileId": "...", "fileName": "report.pdf", "sizeBytes": ..., "folderId": null}

# List everything owned by the caller at the root (no content, cheap):
curl -H "Authorization: Bearer $TOKEN" "https://api.cloud-driver.de/files?folderId=root"

# Stream a specific file's content straight to disk:
curl -H "Authorization: Bearer $TOKEN" \
     "https://api.cloud-driver.de/files/<fileId>/content" -o report.pdf

# Move it into a folder (a real JSON null moves it back to the root):
curl -X PUT "https://api.cloud-driver.de/files/<fileId>/folder" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"folderId":"<folderId>"}'
```

### 2.3 Folders, sharing, trash

```bash
# Create a folder, then list the caller's top-level folders:
curl -X POST https://api.cloud-driver.de/folders \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"name":"Invoices","parentFolderId":null}'
curl -H "Authorization: Bearer $TOKEN" https://api.cloud-driver.de/folders

# Share a file with another account (read-only), then let them list what's shared with them:
curl -X POST "https://api.cloud-driver.de/files/<fileId>/share" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"granteeEmail":"colleague@example.com"}'
curl -H "Authorization: Bearer <colleague-jwt>" https://api.cloud-driver.de/files/shared-with-me

# Delete a file (soft - moves to trash), then restore it:
curl -X DELETE "https://api.cloud-driver.de/files/<fileId>" -H "Authorization: Bearer $TOKEN"
curl -X POST "https://api.cloud-driver.de/files/<fileId>/restore" -H "Authorization: Bearer $TOKEN"
```

### 2.4 Live updates (WebSocket)

`GET /ws/updates` (bearer token via `Authorization` header, or `?token=` for a client that can't set
one, e.g. a browser) pushes `{"table","operation","id"}` whenever the connected account's own data
changes elsewhere — another device, a share, a live-push-triggering database write. See
`cloud-driver-extensions-watcher/README.md` for what triggers it server-side.

## 3. Java client library (`cloud-driver-platforms-rest`)

For any JVM app that would rather not hand-roll HTTP, retry-on-401, and OS keychain access. Depends
on nothing else in this repo (see `cloud-driver-platforms-rest/README.md`).

```java
import de.lino.cloud.platform.rest.api.ApiClient;
import de.lino.cloud.platform.rest.api.SessionManager;
import de.lino.cloud.platform.rest.api.session.TokenStoreFactory;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse;

try (ApiClient apiClient = new ApiClient("https://api.cloud-driver.de", "https://api.cloud-driver.de")) {
    SessionManager session = new SessionManager(apiClient, TokenStoreFactory.create().store());

    if (!session.tryRestoreSession()) {
        session.login("jane@example.com", "Str0ng!Pass9"); // or session.register(...) + confirmRegistration(...)
    }

    // Upload straight from disk, then list what's owned at the root.
    java.nio.file.Path report = java.nio.file.Path.of("report.pdf");
    apiClient.uploadFile(report.getFileName().toString(), report, null);

    for (StoredFileSummaryResponse file : apiClient.listFiles()) {
        System.out.println(file.fileName() + " (" + file.sizeBytes() + " bytes)");
    }

    // Folders, sharing, trash - same "one call, sync or *Async" shape throughout:
    var invoices = apiClient.createFolder("Invoices", null);
    apiClient.shareFile(apiClient.listFiles().get(0).fileId(), "colleague@example.com");
    apiClient.listSharedWithMe();

    session.logout();
} catch (ApiClient.ApiException e) {
    e.printStackTrace();
}
```

Every method above also has an `*Async` form returning `CompletableFuture<T>` (`loginAsync`,
`uploadFileAsync`, `listFilesAsync`, ...) — see `cloud-driver-platforms-rest/README.md`'s full
method list for uploads/downloads streamed to/from disk with progress callbacks, cursor-paginated
listings, and the admin/audit-log routes.

## 4. Kotlin desktop client

`cloud-driver-platforms-desktop`'s `CloudDriverClient` wraps `ApiClient` above as `suspend`
functions, adding session persistence and live-update push:

```kotlin
val client = CloudDriverClient(
    authPanelBaseUrl = "https://api.cloud-driver.de",
    apiBaseUrl = "https://api.cloud-driver.de",
)

// Restore a previous session, or fall back to a fresh login.
val restoredToken = client.tryRestoreSession()
if (restoredToken == null) {
    client.login("jane@example.com", "Str0ng!Pass9")
}

val uploaded = client.uploadFile(Path.of("report.pdf"), folderId = null)
val files = client.listFiles(folderId = null)
client.shareFile(uploaded.fileId(), "colleague@example.com")

client.startLiveUpdates { /* another device or a share changed something - reload */ }

client.close() // shuts down the wrapped ApiClient's executor
```

`client.usedKeychainFallback` is `true` if no real OS keychain was found and the session token fell
back to a permission-restricted plain file — surface that to the user rather than silently
degrading. See `cloud-driver-platforms-desktop/README.md` for the full call list (folders, trash,
admin, metrics).

## 5. Swift iOS client

`cloud-driver-platforms-mobile` has its own, independent networking layer (a Swift `actor`, no
shared code with the JVM client — see that module's README for why) built on `async`/`await`:

```swift
let client = APIClient(baseURL: URL(string: "https://api.cloud-driver.de")!)
let sessionManager = SessionManager(client: client)

if await !sessionManager.tryRestoreSession() {
    _ = try await client.login(email: "jane@example.com", password: "Str0ng!Pass9")
    await sessionManager.persistCurrentSession()
}

let uploaded = try await client.uploadFile(
    fileName: "report.pdf", data: fileData, folderId: nil)
let files = try await client.listFiles(folderId: nil)
try await client.shareFile(fileId: uploaded.fileId, granteeEmail: "colleague@example.com")
```

## 6. Operator terminal commands

`cloud-driver-extensions-terminal` registers a fixed catalog of commands against the interactive
console (see `cloud-driver-api/README.md`'s "`terminal` package" section for the engine itself).
Typed directly at the console the running `cloud-driver-bootstrap` process opens — not called from
code:

| Command | Aliases | Purpose |
|---|---|---|
| `help` | `?`, `h` | List every registered command |
| `exit` | `quit`, `q` | Shut down the whole process (`CloudDriver#shutdown()`) |
| `clear` | `clc` | Clear the terminal window |
| `screen-leave` | `l`, `sl` | Detach the terminal session without killing the process |
| `extensions` | `extension`, `ext` | List every registered extension and its status |
| `dispatch` | `exec`, `sudo`, `d` | Run a system-level command through the terminal |
| `statistics` | `stats` | Basic counts (accounts, files, uploaded bytes) |
| `cloudUser list` / `info <email>` / `reset <email>` / `delete <email>` / `update <email> <bytes>` | `cu`, `user` | Inspect/manage one or every account |
| `recomputeStorage <email>` / `recomputeStorage all` | `recompute` | Recompute an account's uploaded-bytes total from its actual files |
| `admin grant <email>` / `admin revoke <email>` | `isAdmin` | The only writer of the admin flag anywhere in this codebase |
| `auditLog` / `auditLog all` / `auditLog <email>` | `audit`, `log` | Browse the persisted security-audit trail |
| `migrateToS3` | `migrateS3` | Move every not-yet-S3-backed file's content onto the configured bucket |
| `hardReset` (run twice within 5s to confirm) | `reset` | Wipe every entity section — irreversible, no undo |

Registering your own command from a new extension:

```java
public void onRunning(String[] args) {
    this.cloudDriver().getTerminal().getCommandService().register(new MyCommand());
}
```

## Where to go next

- [architecture.md](architecture.md) — how these pieces run together as one process
- [api-reference.md](api-reference.md) — the complete REST route table
- [security.md](security.md) — what's actually encrypted, and how authentication/authorization work
- [configuration.md](configuration.md) — every config key referenced above (`jwt-signing-key`,
  SMTP, quotas, rate limiting)
- [contributing.md](contributing.md) — adding a new REST route or feature module
