# API Usage Guide

This page is task-oriented: for each way a caller actually reaches `cloud-driver`, it shows the
minimal working code. For the full REST route table, see [api-reference.md](api-reference.md); for
a single class's complete contract (every method, every exception), the source and its Javadoc in
the module named alongside each sample are the reference. This page exists so a first working call
never requires reading either end to end.

## Which layer do I actually want?

```mermaid
flowchart TD
    Q{"Where does your<br/>code run?"}
    Q -->|"Inside the backend process<br/>(an extension, bootstrap code)"| L1["In-process Java API — §1"]
    Q -->|"A JVM app elsewhere"| L3["Java client library — §3"]
    Q -->|"The desktop app itself"| L4["Kotlin CloudDriverClient — §4"]
    Q -->|"The iOS app itself"| L5["Swift APIClient — §5"]
    Q -->|"A Python service/script"| L7["Python CloudDriverClient — §7"]
    Q -->|"Anything else / curl"| L2["Raw REST — §2"]
    Q -->|"A human operator at the console"| L6["Terminal commands — §6"]
```

| I want to... | Use |
|---|---|
| Build a new backend feature that runs inside the `cloud-driver-bootstrap` process | The in-process Java API — [§1](#1-in-process-java-api-same-jvm) |
| Talk to a running deployment from any language, over the network | The REST API — [§2](#2-the-rest-api-over-http) |
| Talk to it from a JVM app without hand-rolling HTTP | `cloud-driver-multiplatform-java`'s `ApiClient` — [§3](#3-java-client-library-cloud-driver-multiplatform-java) |
| Talk to it from the Kotlin/Compose desktop app's own code | `CloudDriverClient` — [§4](#4-kotlin-desktop-client) |
| Talk to it from the Swift/iOS app's own code | `cloud-driver-multiplatform-swift`'s `APIClient` — [§5](#5-swift-ios-client) |
| Inspect/administer a running deployment as an operator | The interactive terminal — [§6](#6-operator-terminal-commands) |
| Talk to it from a Python service or script | `cloud-driver-multiplatform-python`'s `CloudDriverClient` — [§7](#7-python-client-library-cloud-driver-multiplatform-python) |

Everything in §3–§5 and §7 ultimately calls the same REST routes described in §2; they exist so four
very different runtimes (plain JVM, Kotlin Multiplatform/Compose Desktop, Swift/SwiftUI, CPython)
don't each have to hand-roll HTTP, JSON, retry-on-401, and OS credential storage from scratch.

## 1. In-process Java API (same JVM)

Everything below lives in `cloud-driver-api` (contracts) with implementations in
`cloud-driver-plugin`/`cloud-driver-auth`. It's what you use when writing a new
`cloud-driver-extensions-*` feature module, or any other code that runs inside the
`cloud-driver-bootstrap` process itself. Every class named here is declared in
`cloud-driver-api` — its Javadoc is the complete contract.

### 1.1 Get hold of `CloudDriver`

Exactly one `CloudDriver` is installed process-wide. `cloud-driver-bootstrap`'s `main` does this
once, at startup; everything else (extensions, terminal commands, event handlers) just reaches the
already-installed instance:

```java
// Once, at process startup (cloud-driver-bootstrap's own main):
final Credentials credentials =
        Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).orElseThrow();
DatabaseProvider databaseProvider = DatabaseRepository.getInstance()
        .registerDatabaseProvider(0, DatabaseType.POSTGRES_SQL, credentials);
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
final class CustomerRecord extends Serialized implements SecondaryIndexed {
    static final String INDEX_IBAN = "iban"; // hand-declared index name, never reflective

    private final int id;
    private final String iban;

    CustomerRecord(int id, String iban) { this.id = id; this.iban = iban; }

    @Override
    public List<String> keysOf() {
        return List.of(String.valueOf(id)); // first element = primary key
    }

    @Override
    public Map<String, String> secondaryIndexKeys() {
        return Map.of(INDEX_IBAN, this.iban);
    }
}

DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();

dataFactory.register(new CustomerRecord(42, "DE00..."));              // insert-or-update
CustomerRecord fetched = dataFactory.fetch("42", CustomerRecord.class);        // throws if absent
Optional<CustomerRecord> maybe = dataFactory.findById("42", CustomerRecord.class); // empty() if absent
List<CustomerRecord> all = dataFactory.getEntities(CustomerRecord.class);
dataFactory.delete("42", CustomerRecord.class);

// Equality lookup on one field: declare a secondary index instead of filtering a full scan.
List<CustomerRecord> byIban = dataFactory.getEntitiesByIndex(CustomerRecord.class, "iban", "DE00...");

// Every sync method above has a *Async counterpart, generated once on DataFactory itself:
dataFactory.registerAsync(new CustomerRecord(43, "DE01..."))
        .thenRun(() -> System.out.println("stored"));
```

An entity opts in by implementing `SecondaryIndexed` and returning its `index name → index key`
pairs from `secondaryIndexKeys()`; asking for an index no entity of that type declares throws rather
than quietly returning empty. These are in-memory indexes, not SQL ones (the rows are ciphertext) —
see [architecture.md](architecture.md)'s "Performance and scalability notes" for the rebuild
contract.

`reload(CustomerRecord.class)` re-reads that type's section from the database — needed if a
*different* process (or a different `DataFactory` instance) wrote a row this one hasn't seen yet;
see the "Cross-process staleness" note in [architecture.md](architecture.md)'s "Data handling"
section for why this isn't automatic.

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
{
  "name": "my-extension",
  "version": "1.0.0",
  "description": "What this extension does",
  "authors": ["Jane Doe"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

`extension.json` (in the module's `resources` folder) is required — `name`, `version` and
`description` are all mandatory (`description` may be blank, but the key must be present or loading
throws); `authors` and `dependencies` are optional and default to an empty list.
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

Four built-in events already ship and fire on their own: `DatabaseWatchEvent`
(`de.lino.cloud.api.event.database`) fires on a Postgres change notification against `StoredFile`'s
table (installed by the `cloud-driver-extensions-watcher` feature module); `PendingUploadEvent`
(same package) fires once a queued offline upload from §1.3 finally succeeds; and
`ExtensionRegisterEvent`/`ExtensionUnregisterEvent` (`de.lino.cloud.api.event.extension`) fire per
extension at startup and whenever the `extensions` terminal command registers or unregisters one.
Register a handler for any of them the same way as above.

### 1.6 `RestFactory` — exposing HTTP routes

`CloudDriver.getInstance().getFactoryContainer().getRestFactory()` is **always the unauthenticated
instance** — fine for local development, never for a real deployment. `DefaultRestFactory` has four
further constructors, in two gating shapes — one static-API-key form and three per-user-JWT
overloads; construct one directly instead of using the `CloudDriver` facet:

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
// Per-user JWT. cloud-driver-extensions-rest stands up the four-argument form, whose
// ObjectStorageService lets GET /files/{id}/content stream straight from S3 instead of
// materializing the whole file as a byte[]; null keeps the fully-materializing behaviour:
RestFactory api = new DefaultRestFactory(dataFactory, authService, cloudUserService, objectStorageService);
api.start("127.0.0.1", 8080);   // Javalin serves plain HTTP - keep it loopback-bound behind Caddy
```

All four verbs (`register`/`fetch`/`update`/`delete`) must be called before `start(...)` —
routes are assembled once, up front. Only entities implementing `Owned` are scoped to the calling
user's own data on the JWT-gated instance; see `Owned`'s Javadoc (`cloud-driver-api`,
`de.lino.cloud.api.jwt.rest`) for the exact scoping rules.

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

See `ICloudUserService`/`IAuthService` (`cloud-driver-api`) for the full method list (folders,
trash, sharing, quotas, password reset, e-mail change, refresh tokens, admin).

## 2. The REST API, over HTTP

The full route table lives in [api-reference.md](api-reference.md); this section shows the shape of
an actual request/response for the flows a client goes through most often. Every route requires
`Authorization: Bearer <access-token>` except seven `/auth/*` flow routes — `POST /auth/register`,
`/auth/register/confirm`, `/auth/login`, `/auth/refresh`, `/auth/logout` and the password-reset pair
(`/auth/reset-password`, `/auth/reset-password/confirm`) — and the public-link download route, each
of which carries its own authority in the request itself. The e-mail-change pair
(`POST /auth/change-email`, `/auth/change-email/confirm`) and `GET /auth/me` are *not* exempt: they
identify the account from the bearer token.

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

# List the caller's files at the root (no content, cheap). Without ?limit= the bare array is
# capped at 500 items - add ?limit=&cursor= for a complete listing (see api-reference.md).
curl -H "Authorization: Bearer $TOKEN" "https://api.cloud-driver.de/files?folderId=root"

# Stream a specific file's content straight to disk:
curl -H "Authorization: Bearer $TOKEN" \
     "https://api.cloud-driver.de/files/<fileId>/content" -o report.pdf

# Move it into a folder (a real JSON null moves it back to the root):
curl -X PUT "https://api.cloud-driver.de/files/<fileId>/folder" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"folderId":"<folderId>"}'
```

### 2.3 Conditional download, and patching only what changed

```bash
# Re-download only if the content actually changed. The ETag is the content checksum, quoted.
curl -D- -o report.pdf -H "Authorization: Bearer $TOKEN" \
     "https://api.cloud-driver.de/files/<fileId>/content"
# -> 200 OK  ETag: "9f2b…"  Cache-Control: private, must-revalidate

curl -D- -o /dev/null -H "Authorization: Bearer $TOKEN" -H 'If-None-Match: "9f2b…"' \
     "https://api.cloud-driver.de/files/<fileId>/content"
# -> 304 Not Modified, no body

# A sync client diffs against the per-chunk manifest instead of re-uploading the whole file:
curl -H "Authorization: Bearer $TOKEN" \
     "https://api.cloud-driver.de/files/<fileId>/chunk-manifest"
# -> {"chunkSizeBytes":1048576,"totalSizeBytes":5242880,"chunkHashes":["<hex>", ...]}

# ...then sends only the chunks whose hash moved. Positional index, base64 payload.
curl -X PATCH "https://api.cloud-driver.de/files/<fileId>/content" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"totalSizeBytes":5242880,"changedChunks":[{"index":3,"contentBase64":"<...>"}]}'
```

A `404` from the manifest route means the file has none (a dedup alias, a presigned upload, or
content written before manifests existed) — fall back to a full `PUT`. A `400` from `PATCH` means
the chunk set no longer lines up with current content: re-fetch the manifest, or fall back.

### 2.4 Large uploads: resumable multipart sessions

Only available where direct-to-storage transfer is configured (`503` otherwise — clients fall
back to a plain `POST /files`).

```bash
# Begin. checksumSha256 is required here, and doubles as a dedup precheck.
curl -X POST https://api.cloud-driver.de/files/upload-session \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"fileName":"archive.zip","sizeBytes":2147483648,"checksumSha256":"<hex>"}'
# -> {"alreadyStored": {...}}                       # dedup hit: nothing to upload at all
# -> {"fileId":"...","partSizeBytes":8388608,"partCount":257,
#     "totalObjectBytes":...,"encryption":{...}}    # otherwise: the session's geometry

# Per part: presign, then PUT that byte range of the client-encrypted object stream directly.
curl -X POST "https://api.cloud-driver.de/files/upload-session/<fileId>/parts/1/url" \
     -H "Authorization: Bearer $TOKEN"
curl -X PUT "<presigned-url>" --data-binary @part-1.bin

# After a crash, the session id alone is enough — the part list comes from the object store itself.
curl -H "Authorization: Bearer $TOKEN" \
     "https://api.cloud-driver.de/files/upload-session/<fileId>"
# -> {..., "uploadedPartNumbers":[1,2,5], "encryption":{...}}   # re-send only what's missing

curl -X POST "https://api.cloud-driver.de/files/upload-session/<fileId>/complete" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"fileName":"archive.zip","checksumSha256":"<hex>","folderId":null}'
# fileName is required (this is the same body POST /files/{id}/complete-upload takes);
# checksum is always over the PLAINTEXT, and folderId null means the root.

# Abandoning one costs money until aborted — the store bills for uploaded parts. Idempotent:
curl -X DELETE "https://api.cloud-driver.de/files/upload-session/<fileId>" \
     -H "Authorization: Bearer $TOKEN"
```

The `encryption` object on the begin/status responses is not optional bookkeeping: the client
must chunk-encrypt its content into the same v2 layout the server writes, using the
server-issued (KEK-wrapped) content key, before uploading any part. Completion verifies the
stored object's exact length and deletes it on a mismatch. See
[api-reference.md](api-reference.md#direct-to-storage-transfer-optional-if-configured) for the
field-by-field contract, and [security.md](security.md) for why it works this way.

### 2.5 Folders, sharing, trash

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

### 2.6 Live updates (WebSocket)

`GET /ws/updates` (bearer token via `Authorization` header, or `?token=` for a client that can't set
one, e.g. a browser) pushes `{"table","operation","id"}` — never row data; the client re-fetches. It
fires on one thing only: a write to the `StoredFile` table, fanned out to every socket belonging to
the file's **owner**. Folder, share, webhook and account writes push nothing, and a grantee is not
notified when a file shared with them changes. Server-side, the `cloud-driver-extensions-watcher`
feature module installs a Postgres `LISTEN`/`NOTIFY` trigger on that one table. A logout or a
server-side session revocation closes the socket rather than leaving it live.

## 3. Java client library (`cloud-driver-multiplatform-java`)

For any JVM app that would rather not hand-roll HTTP, retry-on-401, and OS keychain access. Depends
on nothing else in this repo — it talks to the backend purely over HTTP/WebSocket. Lives
under `cloud-driver-multiplatform` — the Maven-built sibling of `cloud-driver-multiplatform-swift` (Swift) and
`cloud-driver-multiplatform-python` (Python), formerly named `cloud-driver-platforms-rest`.

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
`uploadFileAsync`, `listFilesAsync`, ...). `ApiClient` also covers uploads/downloads streamed
to/from disk with progress callbacks, cursor-paginated listings, and the admin/audit-log routes —
its Javadoc is the full method list.

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
degrading. `CloudDriverClient` (the desktop app's thin suspend-function wrapper over
`ApiClient`) covers the full surface — folders, trash, sharing, admin, metrics.

## 5. Swift iOS client

`cloud-driver-platforms-mobile`'s networking/session layer is `cloud-driver-multiplatform-swift`
(`cloud-driver-multiplatform/cloud-driver-multiplatform-swift`, a local Swift Package Manager dependency — no
shared code with the JVM client, deliberately: each SDK hand-mirrors the same REST contract in its
own ecosystem's idiom), a Swift `actor` built on
`async`/`await`:

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
console (the terminal engine itself lives in `cloud-driver-api`'s `terminal` package).
Typed directly at the console the running `cloud-driver-bootstrap` process opens — not called from
code:

| Command | Aliases | Purpose |
|---|---|---|
| `help` / `help --description` / `help <command>` | `?`, `h` | List every registered command. The default listing is identifying metadata only — name, aliases, declared flags; `--description` adds each command's description, and naming one command prints everything known about it: its sub-command syntax and its flags |
| `exit` | `quit`, `q` | Shut down the whole process (`CloudDriver#shutdown()`) |
| `clear` | `clc`, `reset` | Clear the terminal window and reprint the banner. `reset` resolves here, so the word an operator reaches for at a console that has stopped redrawing repairs the screen |
| `more` / `more all` / `more drop` | `m`, `next` | Print the next page of a command whose output did not fit on one screen (see "Paged output" below) |
| `screen-leave` | `l`, `sl` | Detach the terminal session without killing the process |
| `extensions` | `extension`, `ext` | List every registered extension and its status |
| `dispatch <service> [args...]` / `dispatch stream …` / `dispatch status` / `dispatch cancel <id>` or `cancel all` | `exec`, `d` | Run a system-level command as the server process. Its standard input is closed, so a child that prompts fails fast instead of hanging; its output is paged when it exits (`dispatch stream …` prints it live instead); `dispatch status` and `dispatch cancel` list and stop what is still running; every dispatched line is recorded in the audit trail |
| `statistics` | `stats` | Basic counts (accounts, files, uploaded bytes) — computed from row metadata only, never by fetching file content. One caveat: the first run against a corpus migrated to S3 before 2026-09-10 resolves each still-sizeless row's content once and backfills its size onto the row, so that run is slow and every later one fast |
| `cloudUser list` / `info <email>` / `reset <email> [confirm]` / `delete <email> [confirm]` / `limit <email> <bytes> <unit>` (unit: `B`/`KB`/`MB`/`GB`) | `cu`, `user` | Inspect/manage one or every account. `reset` and `delete` are armed, then confirmed within 15s; `reset` destroys every file and folder the account owns, bypassing the trash entirely |
| `recomputeStorage <email>` / `recomputeStorage all` | `recompute` | Recompute an account's uploaded-bytes total from its actual files — physical, dedup-aware: each distinct content is counted once no matter how many alias copies point at it, matching what upload/delete accounting would have produced |
| `admin grant <email> [confirm]` / `admin revoke <email>` | `isAdmin` | The only writer of the admin flag anywhere in this codebase. A grant is armed then confirmed within 15s — it unlocks every account's record, the whole audit trail and the server metrics; a revoke takes effect at once, so a privileged account can be shut out in one step. Both land in the audit trail |
| `auditLog` / `auditLog all` / `auditLog <email>` | `audit`, `log` | Browse the persisted security-audit trail |
| `migrateToS3` | `migrateS3` | Move every not-yet-S3-backed file's content onto the configured bucket (dedup aliases own no content and are skipped, reported in their own counter) |
| `hardReset --dry-run` / `hardReset` / `hardReset confirm` / `hardReset cancel` | *(none)* | Clear every entity section, every object-storage object a file row references, and every published extension's own data (versions, thumbnails, webhook subscriptions and delivery history, the keyword search index), then shut the process down — irreversible, no undo. `--dry-run` lists the exact scope and changes nothing; a bare `hardReset` only arms and prints what would go; `hardReset confirm` within 30 seconds performs it; `hardReset cancel` disarms. A `confirm` typed with nothing armed does nothing. It answers to no alias |

Diagnostics and operations. These reach optional facets through the service/factory containers and
null-check them, so all of them are registered unconditionally — on a deployment running none of
the extensions behind them, each simply reports that its subsystem is absent, which is itself the
useful answer:

| Command | Aliases | Purpose |
|---|---|---|
| `health` | `status`, `facets` | Which optional subsystems are published, and which of the external ones (clamd, the embedding service, Redis, S3) actually answer a live probe. "Published" only means an extension loaded — this draws the distinction |
| `config` | `cfg`, `configuration` | The configuration this process is *running with*, secrets redacted — not what the file on disk currently says |
| `reload` | `refresh` | Re-read one entity type's section from the database, discarding this process's cached mirror (a row written by another process is otherwise invisible indefinitely) |
| `file <id>` | `storedFile` | Everything known about one file in one place — the `StoredFile`, its ownership row, trash state, scan verdict, versions, shares |
| `session list <email>` / `revoke …` | `sessions` | List and revoke an account's refresh tokens. Only refresh tokens are revocable; the access JWT is stateless and lives out its 12 hours |
| `share list <email>` / `share links [email]` / `share revoke-link[s] …` | `shares` | What an account has shared — grants to other accounts, and public links (the one unauthenticated read path in the system); `revoke-link`/`revoke-links` kill a leaked link, armed then confirmed within 15s |
| `trash` | `recycleBin` | Deployment-wide recycle-bin view: trashed items still occupy storage and still count against quota until the retention window elapses |
| `backup now` / `backup status` | `db` | Take a backup on demand (deliberately bypassing the Redis scheduler lock, so an explicit operator backup is never a silent no-op) and check whether the scheduled one is actually running |
| `rateLimit` | `rl` | Inspect and clear the running REST layer's rate-limit windows — otherwise an exhausted window can only be waited out or restarted away |
| `searchIndex` | `si` | Keyword-index visibility, on-demand rebuild, and a query passthrough |
| `intelligence` | `ai`, `semantic` | Semantic-search health, on-demand backfill, and the query/duplicate/tag surfaces |
| `scan` | `contentScan` | Content-scan visibility and on-demand rescans — scanning fails open, so files marked clean unscanned need a way to be revisited |
| `mail` | `email` | Send a test message through whichever `EmailSender` the startup fallback actually selected (SES → SMTP → log-only) |
| `s3` / `s3 purge` / `s3 purge confirm` | `objectStorage` | Reconcile the bucket against the `StoredFile` rows that reference it. `s3` alone is read-only; `s3 purge` arms deleting the objects nothing references and `s3 purge confirm`, within 15 seconds, performs it and records what was removed |

### Interrupt and shutdown

`Ctrl+C` discards the line being typed and returns the prompt, the way it does in a shell. It never
stops the server and never ends the console. `exit` (`quit`, `q`) shuts the whole process down, and
`screen-leave` detaches the `screen` session while leaving the process running. `Ctrl+D`, or a
closed standard input, ends the reading loop while the process keeps serving — reattach or restart
to get a prompt back.

A command that is already running is not cancelled by the interrupt; a dispatched system command is
stopped with `dispatch cancel`.

### Running system commands

`dispatch <service> [args...]` runs a system-level command as the server process — no elevation, no
shell, the line you type is the line the child gets, flags and all. There are four invocations:

```
dispatch journalctl -n 300          # run it, page the output when it exits
dispatch stream tail -f /var/log/x  # print output as it arrives instead
dispatch status                     # what is still running, and for how long
dispatch cancel 3                   # or: dispatch cancel all
```

The child's **standard input is closed** before it is read from, so a command that prompts sees
end-of-file and fails immediately instead of blocking forever on input nobody can type — the
console's own reader owns the keyboard. Feed input deliberately when it is wanted:
`dispatch sh -c "echo y | some-command"`.

Output is capped for display only, never for reading: a buffered run keeps the first 500 lines and
pages them (`more` continues it), a streamed run prints the first 200 and then falls silent, and
either way the count of what was not shown is reported at the end. `dispatch cancel` terminates a
child and kills it if it has not stopped 5 seconds later, saying which of the two ended it.

Nothing is timed out and nothing needs a confirmation: a `pg_dump` or an `apt-get -y upgrade` has
to be able to run unwatched for as long as it takes.

The whole line is written to the audit trail and to the process log **before** the child starts, so
a command that never returns still leaves a trace. Redaction is pattern-based, not a guarantee —
do not type a secret inline (`dispatch mysql -pSECRET`); pass it through a file or an environment
variable instead.

### Flags

Any command's arguments can carry flags — `--skip-task`, `-s`, `--limit=25`. They are parsed out
of the line once, before `execute` runs, so a flag may be typed anywhere without shifting the
positions a command reads: `command(0)`, `hasCommand(…)`, `hasLength(…)`, `length()`, `isEmpty()`
and `join(…)` all address positional arguments only. `args()` still returns the raw line, flags
included. A token counts as a flag when it starts with a dash followed by a letter, so a negative
number (`-1`) stays an argument.

```java
// intelligence backfill jane@example.com --content
public void execute(CommandArguments arguments) {
    boolean includeContent = arguments.hasFlag("--content");   // dashes and case are ignored
    int limit = arguments.flagAsInt("--limit", 20);            // also AsLong/AsDouble/AsBoolean
    String target = arguments.hasLength(1) ? arguments.command(1) : "all";
}
```

`hasFlag`/`flag`/`flagAs*` answer for any flag, declared or not. Declaring them through
`Command#flags()` additionally gets them printed by `help`, suggested by tab completion (type a
`-` and press tab), and — for a `CommandFlag.valued(…)` flag — lets the value be written
space-separated (`--limit 25`) instead of only attached (`--limit=25`); undeclared flags only
support the attached form, since `--limit 25` is otherwise indistinguishable from a switch
followed by an argument. `arguments.unknownFlags()` lists the flags an operator typed that the
command never declared, so a mistyped `--skiptask` can be reported instead of silently ignored.

```java
@Override
public List<CommandFlag> flags() {
    return List.of(
            CommandFlag.of("--skip-task", "Skip the follow-up task").withAliases("-s"),
            CommandFlag.valued("--limit", "How many rows to print"));
}
```

### Usage

A command declares each way it can be invoked through `Command#usages()`. That one declaration is
both what the command prints back when it was called with arguments it does not understand
(`this.sendUsage()`, which every command in the catalog now uses instead of its own hand-written
syntax block) and what `help <command>` prints under `Usage:`.

```java
@Override
public List<CommandUsage> usages() {
    return List.of(
            CommandUsage.of("backup status", "How the most recent backup went"),
            CommandUsage.of("backup now", "Take a database backup right now"));
}
```

### Destructive commands: arm, confirm, cancel

A command that destroys data or grants privilege is never performed by the invocation that requests
it. The first invocation prints exactly what it would do and arms; a second invocation, appending
the explicit word `confirm` and naming the same target, performs it inside the window. Arming is
keyed on the exact target, so arming against one account can never confirm an action against
another, and anything else typed in between leaves it armed but unfired.

| Armed by | Confirmed by | Window |
|---|---|---|
| `cloudUser reset <email>` | `cloudUser reset <email> confirm` | 15s |
| `cloudUser delete <email>` | `cloudUser delete <email> confirm` | 15s |
| `admin grant <email>` | `admin grant <email> confirm` | 15s |
| `share revoke-link <email> <fileId> <token>` | the same line, plus `confirm` | 15s |
| `share revoke-links <email>` | `share revoke-links <email> confirm` | 15s |
| `s3 purge` | `s3 purge confirm` | 15s |
| `hardReset` | `hardReset confirm` | 30s |

Confirming with nothing armed is **refused**, not treated as arming — repeating a command that
appeared to do nothing is exactly what one does at a console that has stopped redrawing, and that
must never be the thing that destroys data. For the same reason there is no `--force` flag anywhere
in this catalog: a flag can be typed anywhere on the line, so a single self-contained destructive
line would survive in history and be one Enter away from running again. The confirmation is a
command rather than a keypress for the same reason paged output is — the reading thread is already
blocked inside `jline` while a command runs (see "Paged output" below).

`admin revoke <email>` is deliberately **one** step: shutting a compromised privileged account out
is the containment action, and it must never take two commands. `hardReset` additionally accepts
`hardReset --dry-run`, which lists its exact scope and changes nothing, and `hardReset cancel`,
which disarms; the other guards are not cancellable, they simply expire.

A hard reset does **not** reach the semantic index owned by `cloud-driver-intelligence`, Redis
coordination state (rate-limit windows, scheduler locks), the off-site database backup bucket, the
`kek` section holding key-encryption-key material, or the configuration files on disk.
Decommissioning a deployment means clearing those separately.

Each leaves a trace. `cloudUser reset`/`delete` and `admin grant`/`revoke` write entries the
`auditLog` command reads back, recorded in the service rather than the command so any other caller
is recorded too. `s3 purge` records how many objects it removed, and also writes a line to the
process log, since the audit service is an optional facet that may not be running. `hardReset`
writes armed / confirmed / completed-or-failed lines to the process log **only** — it clears the
audit-log section along with everything else, so no persisted entry could survive it. The console
session appends the process log to `SCREEN_LOG_FILE` (`/var/log/cloud-driver/cloud.log`; see
[deployment.md](deployment.md)).

### Paged output

The console is a `jline` terminal, usually inside a detached `screen` session with little or no
scrollback: output longer than the window is not scrolled back to, it is gone. Commands whose
output has no fixed length therefore hand it to `Terminal#displayPaged(label, lines)` instead of
printing it line by line. One screenful is printed, the rest waits, and a footer says how much:

```
- searchIndex query <email> <text...>          Run a keyword search as that account
&8-- 43 more line(s) of 'help --description' - type more for the next page, more all for the rest --
```

`more` prints the next page, `more all` the remainder, `more drop` throws it away. The
continuation is a command rather than a keypress on purpose: while a command runs, the terminal's
reading thread is already blocked inside `jline` waiting for the next line, and a second reader on
the same terminal would fight it for every keystroke. Queuing is per terminal — a second paged
command replaces whatever the first one had left over, which is why the footer names the command
the remainder belongs to. `help`, `help --description`, `cloudUser list`, `auditLog` and
`dispatch` use it today; any command that can print more lines than fit should.

Registering your own command from a new extension:

```java
public void onRunning(String[] args) {
    this.cloudDriver().getTerminal().getCommandService().register(new MyCommand());
}
```

## 7. Python client library (`cloud-driver-multiplatform-python`)

Distribution `cloud-driver-client` (`pip install -e .` from
`cloud-driver-multiplatform/cloud-driver-multiplatform-python`; requires Python 3.10+, depends only
on `httpx` and `pydantic`). One blocking `CloudDriverClient`, split into seven resource namespaces —
`auth`, `cloud_users`, `files`, `folders`, `trash`, `admin`, `activity`:

```python
from cloud_driver_client import CloudDriverClient, FileTokenStore

with CloudDriverClient(
    "https://api.cloud-driver.de",
    token_store=FileTokenStore("/var/lib/my-service/cloud-driver-session.json"),
) as client:
    client.auth.login("jane@example.com", "Str0ng!Pass9")

    client.files.upload("report.pdf", folder_id=None)
    for file in client.files.iter_all():          # pages behind the 500-item bare-array cap
        print(file.file_name, file.size_bytes)

    client.folders.create("Invoices")
    client.files.share("<fileId>", "colleague@example.com")   # permission_level defaults to VIEW

    updates = client.live_updates()               # needs the optional `live` extra (websockets)
    updates.start(lambda event: print(event.table, event.operation, event.id))
```

For an asyncio service, wrap it in `AsyncCloudDriverClient`, which proxies every blocking call
through `asyncio.to_thread` rather than reimplementing the routes. Two limits worth knowing before
choosing this SDK over §3: it raises `UnsupportedEncryptionError` on any presigned begin-ticket
carrying an `encryption` object (use the server-mediated `files.upload`/`files.download_to_path`
instead), and it has no webhook, resumable-session or chunk-manifest surface — those exist only in
the Java client library.

## Where to go next

- [architecture.md](architecture.md) — how these pieces run together as one process
- [api-reference.md](api-reference.md) — the complete REST route table
- [security.md](security.md) — what's actually encrypted, and how authentication/authorization work
- [configuration.md](configuration.md) — every config key referenced above (`jwt-signing-key`,
  SMTP, quotas, rate limiting)
- [contributing.md](contributing.md) — adding a new REST route or feature module
