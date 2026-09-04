# cloud-driver-api

This module defines `cloud-driver`'s public contract: interfaces, abstract classes, value objects/records, and exceptions. It has **no concrete implementations** of its own persistence/crypto stack - every implementation lives in `cloud-driver-plugin` (and, for the JWT auth engine specifically, `cloud-driver-auth`), both of which depend on this module, never the other way around. The only concrete classes this module does carry are a handful of narrow domain entities/value objects that have no `cloud-driver-plugin` dependency to place them in instead: `StoredFile`, `Folder`, `ApiKey`, `AuthUser`, `AuditEvent`, and the small value objects under `security.*`.

The cryptographic design follows `../architecture/SECURITY_REQUIREMENTS.md` (bundled as a resource in this module, under `src/main/resources`) - envelope encryption with AES-256-GCM, KMS/HSM-backed key wrapping with rotation, authenticated-tag verification, Argon2id password hashing. Section references in Javadoc (e.g. "section 9") point back to that document.

## Coordinates

```xml
<dependency>
    <groupId>de.lino.cloud.api</groupId>
    <artifactId>cloud-driver-api</artifactId>
    <version>1.0.1</version>
</dependency>
```

A consuming extension almost always also needs `cloud-driver-plugin` (every concrete implementation of the interfaces below) and a `de.lino.database` `database-driver-plugin` `DatabaseProvider` (JSON file store, H2, MySQL, PostgreSQL, MongoDB, ...).

```xml
<dependency>
    <groupId>de.lino.cloud.plugin</groupId>
    <artifactId>cloud-driver-plugin</artifactId>
    <version>1.0.1</version>
</dependency>
```

This module itself depends on `database-driver-api` (pinned to `1.3.11`), `org.jetbrains:annotations` (`@NotNull`/`@Nullable` on the public API surface), `org.projectlombok:lombok` (provided scope, `@NonNull`/`@Getter`/`@ToString`/...), and `org.jline:jline:4.3.1` (the only third-party UI-facing library this module pulls in, exclusively for the `terminal` package). It has no Javalin, no JDBC driver, no JWT library of its own - every such dependency is confined to whichever module actually implements the corresponding interface.

## Project structure - packages and what lives in each

```
de.lino.cloud.api
├── CloudDriver.java              facade: getFactoryContainer(), getConnectivityChecker(),
│                                  getTerminal(), getConfiguration(), getLogger(), shutdown()
├── audit/                        the persisted security-audit trail (AuditEvent, AuditAction,
│                                  AuditLogService - see "Audit log service" below)
├── event/                        one-singleton-per-class event registry (Event, plus built-in
│   ├── database/                 DatabaseWatchEvent/PendingUploadEvent, dispatched by cloud-driver-
│   └── extension/                extensions-watcher) and extension lifecycle events
├── extension/                    the plugin/extension framework (Extension, ExtensionFactory's
│   ├── detection/                model classes, ProjectBuildDetection/ProjectType)
│   └── info/                     ExtensionProperties/ExtensionPropertiesLoader/ExtensionStatus
├── factory/                      the five abstract "facade" classes + their containers
│   ├── container/                IFactoryContainer - bundles Data/File/Extension/Event/RestFactory
│   └── service/                  IServiceContainer - bundles higher-level cross-cutting services
├── file/                         file/folder domain model - StoredFile, Folder, StoredFileSummary,
│   │                              FileWithFolder, plus the sharing/trash summary pairings below
│   ├── exception/                FileIntegrityException, UploadQuotaExceededException
│   ├── meta/                     FileChecksum, FileMetadata
│   └── pending/                  PendingUploadCache (offline-safe upload queue contract)
│   (SharedFileSummary/SharedFolderSummary/SharedFolderContents - item 9 sharing;
│    TrashedFileSummary/TrashedFolderSummary - trash-with-purge-timestamp pairings; all directly
│    under file/, alongside StoredFile/Folder/StoredFileSummary/FileWithFolder)
├── jwt/                          end-user (JWT) authentication contracts: JwtSigner plus every
│   │                              exception IAuthService's methods throw (InvalidCredentialsException,
│   │                              InvalidJwtException, InvalidPasswordFormatException,
│   │                              InvalidRefreshTokenException, InvalidVerificationCodeException,
│   │                              EmailAlreadyRegisteredException)
│   ├── auth/                     IAuthService, AuthTokens
│   ├── rest/                     Owned (per-user REST scoping marker)
│   └── user/                     AuthUser (the persisted account entity)
├── mail/                         EmailSender/EmailDeliveryException - the verification-code
│                                  delivery contract (see "JWT authentication" below)
├── metrics/                      MetricsRecorder - vendor-agnostic event-count sink (item 13)
├── push/                         LiveUpdatePublisher - vendor-agnostic live-update push contract (item 10)
├── security/                     the layered crypto/key/hash/password contracts (see below)
│   ├── connectivity/             ConnectivityChecker
│   ├── crypto/                   AeadEncryptionService, EncryptedPayload, CryptoAlgorithm
│   ├── database/                 DatabaseClientException, EncryptedEntityRecord
│   ├── envelope/                 EnvelopeEncryptedPayload
│   ├── hash/                     HashAlgorithm
│   ├── keys/                     DataEncryptionKey, KeyEncryptionService, WrappedKey, KeyWrapException
│   ├── password/                 PasswordHasher
│   └── rest/                     ApiKey (static X-API-Key REST guard)
├── terminal/                     a self-contained jline-based interactive console engine
│   ├── ansi/                     AnsiColors (legacy &x code -> ANSI SGR translation)
│   ├── logging/                  TerminalLogHandler/TerminalLogFormatter
│   ├── prompt/                   PromptProvider/DefaultPromptProvider
│   ├── service/                  Command/CommandService (dispatch registry, no concrete commands)
│   └── thread/                   ReadingThread (the blocking read loop)
├── user/                         ICloudUser/ICloudUserService - end-user <-> owned-file contracts,
│                                  plus GranteeAccountNotFoundException (item 9 sharing)
└── utility/                      Asserts, Constraints, CursorPage, UnitParser, and (task/) MultiTaskingFactory
```

Every package above follows the same rule: **this module defines the contract, `cloud-driver-plugin`/`cloud-driver-auth` supply the implementation.** The only exceptions - concrete classes that live here because they have nothing but core-Java/Lombok/Gson dependencies - are `StoredFile`, `Folder`, `StoredFileSummary`, `FileWithFolder`, `SharedFileSummary`/`SharedFolderSummary`/`SharedFolderContents`, `TrashedFileSummary`/`TrashedFolderSummary`, `FileChecksum`, `FileMetadata`, `ApiKey`, `AuthUser`, `AuditEvent`, `EncryptedPayload`, `EnvelopeEncryptedPayload`, `EncryptedEntityRecord`, `WrappedKey`, `DataEncryptionKey`, `ExtensionProperties`/`ExtensionPropertiesLoader`, `AnsiColors`, `Terminal` and its supporting terminal classes, `CursorPage`/`UnitParser`, and the small static utility classes.

## `CloudDriver` - a facade over one factory container, connectivity, terminal, and configuration

`CloudDriver` is deliberately thin. It does **not** expose `getDataFactory()`/`getFileFactory()`/etc. directly (an earlier revision did - see the note at the end of this section); instead every persistence/extension/event/REST facet is reached through one `IFactoryContainer`:

```java
public abstract class CloudDriver {
    public static CloudDriver getInstance();          // the process-wide singleton
    public final Logger getLogger();                  // lazily-created, process-wide logger
    public abstract ConnectivityChecker getConnectivityChecker();
    public abstract IFactoryContainer getFactoryContainer();
    public abstract IServiceContainer getServiceContainer();
    public abstract Terminal getTerminal();
    public abstract void shutdown();
    public JsonDocument getConfiguration();            // re-reads configuration.json fresh every call
}
```

- **`getFactoryContainer()`** - returns the `IFactoryContainer` (`de.lino.cloud.api.factory.container`) this instance was constructed with: `getDataFactory()`, `getFileFactory()`, `getExtensionFactory()`, `getEventFactory()`, `getRestFactory()`. Every one of the five facets described below is reached through this container, not directly off `CloudDriver`.
- **`getServiceContainer()`** - returns the `IServiceContainer` (`de.lino.cloud.api.factory.service`) bundling `getCloudUserService()`/`getAuthService()` (see [The `user` package](#the-user-package-end-user--owned-file-bookkeeping)). Unlike `IFactoryContainer`, this one may start out holding `null` accessors - the concrete `AuthService`/`ICloudUserService` instances are only published into it once the JWT-gated REST extension actually runs (see that constructor's own bullet below); a caller reached before/without it must null-check.
- **`getConnectivityChecker()`** - reports whether outbound network connectivity is currently available. See [Offline-safe file uploads](#offline-safe-file-uploads).
- **`getTerminal()`** - the interactive `jline`-based console (see [The `terminal` package](#the-terminal-package)), independent of whether `CloudDriver.getInstance()` is even set up for anything else.
- **`getConfiguration()`** - loads (fresh, not cached) `Constraints.CONFIGURATION_PATH.resolve("configuration.json")` - local deployment settings such as `"rest-api-port"`, `"rest-api-bind-host"`, `"jwt-signing-key"`.
- **`shutdown()`** - tears down every facet this instance owns; the concrete implementation (`DefaultCloudDriver`, `cloud-driver-plugin`) makes this idempotent and per-step failure-isolated (one facet failing to shut down doesn't block the rest).

Exactly one implementation is installed process-wide via a static factory method on that implementation - e.g. `DefaultCloudDriver.setInstance(...)` in `cloud-driver-plugin`. Nothing may call `getInstance()` before that installation has happened; notably, an `Extension`/`Event` subclass's convenience `cloudDriver()`/`terminal()` helpers will throw `NullPointerException` (via `Asserts.requireNonNull`) if reached too early.

```java
CloudDriver cloudDriver = DefaultCloudDriver.setInstance(databaseProvider, envelopeEncryptionService);

cloudDriver.getFactoryContainer().getDataFactory().register(customer);
CustomerRecord recovered = cloudDriver.getFactoryContainer().getDataFactory().fetch("42", CustomerRecord.class);

// From anywhere else in the process, once installed:
CloudDriver.getInstance().getFactoryContainer().getDataFactory().fetch("42", CustomerRecord.class);
```

**As of this writing, no dedicated `DataFactory`/`FileFactory` worked example exists in this repo** - a `CloudDriverUsageSample.java` this section previously pointed to is not currently on disk (re-verify with `find . -name CloudDriverUsageSample.java` before pointing anyone at that path). `RestFactory` has two real samples (`cloud-driver-plugin/src/test/java/de/lino/cloud/plugin/sample/RestFactorySample.java`/`RestFactoryCloudUserSample.java`, see [below](#restfactory---rest-exposure-over-datafactory)), both of which exercise `DataFactory`/`FileFactory` indirectly through `RestFactory`'s route handlers; `ExtensionFactory` has its own sample (see [below](#the-extension-framework)); `EventFactory` currently has none.

## `IFactoryContainer` / `IServiceContainer` - the two facet bundles

- **`IFactoryContainer`** (`de.lino.cloud.api.factory.container`) - the five persistence-and-beyond facets described in the sections below: `getDataFactory()`, `getFileFactory()`, `getExtensionFactory()`, `getEventFactory()`, `getRestFactory()`. Reached via `CloudDriver.getInstance().getFactoryContainer()`.
- **`IServiceContainer`** (`de.lino.cloud.api.factory.service`) - a second, smaller bundle for higher-level, cross-cutting services built *on top of* the raw facets above, rather than being one themselves. Five accessor pairs: `getCloudUserService()`/`setCloudUserService(...)` (`ICloudUserService` - see [The `user` package](#the-user-package-end-user--owned-file-bookkeeping)), `getAuthService()`/`setAuthService(...)` (`IAuthService`), `getLiveUpdatePublisher()`/`setLiveUpdatePublisher(...)` (`de.lino.cloud.api.push.LiveUpdatePublisher`, item 10), `getAuditLogService()`/`setAuditLogService(...)` (`de.lino.cloud.api.audit.AuditLogService`, item 11), and `getMetricsRecorder()`/`setMetricsRecorder(...)` (`de.lino.cloud.api.metrics.MetricsRecorder`, item 13). `getCloudUserService()`/`getAuthService()` are also exposed directly by `RestFactory` (`RestFactory#getCloudUserService()`/`#getAuthService()`) so its own route handlers and any external caller agree on the same instances. Unlike `IFactoryContainer`, every accessor here may legitimately return `null` until the corresponding extension/subsystem has actually published a real instance - a caller reached before/without that subsystem must null-check rather than assume non-null (see each interface's own Javadoc for exactly when it's published).

Both are plain bundling interfaces with no lifecycle logic of their own - all the interesting behavior lives on the individual facets they group together.

## `DataFactory` - entity persistence

The entity-persistence contract, reached via `CloudDriver.getInstance().getFactoryContainer().getDataFactory()`. Only `register`/`update`/`fetch`/`findById`/`delete`/`getEntities`/`clear`/`deleteSection`/`reload`/`shutdown` (single + batch where applicable) are abstract; every `*Async` variant is implemented once, generically, directly on `DataFactory` itself in terms of those abstract sync methods (via `MultiTaskingFactory`'s shared virtual-thread executor, wrapping checked exceptions in `CompletionException`).

```java
DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();

dataFactory.register(customer);                          // insert-or-update, encrypted before storage
dataFactory.register(customerA, customerB, customerC);   // batch, dispatched concurrently

dataFactory.update(movedCustomer);                        // fails if no record exists yet under this id

CustomerRecord one = dataFactory.fetch("42", CustomerRecord.class);            // throws if absent
List<CustomerRecord> many = dataFactory.fetch(new String[]{"1","2","3"}, CustomerRecord.class);
Optional<CustomerRecord> maybe = dataFactory.findById("42", CustomerRecord.class); // empty() if absent, still throws on real failures
List<CustomerRecord> all = dataFactory.getEntities(CustomerRecord.class);

dataFactory.delete("42", CustomerRecord.class);
dataFactory.delete(new String[]{"1","2","3"}, CustomerRecord.class);

dataFactory.clear(CustomerRecord.class);          // empties the section, keeps it
dataFactory.deleteSection(CustomerRecord.class);  // removes the section itself, lazily recreated on next register()
dataFactory.reload(CustomerRecord.class);         // re-reads the section from the database, evicting cached entities -
                                                   // needed to see a write made by another process/instance, see below

// Async counterparts (CompletableFuture, backed by MultiTaskingFactory's virtual-thread executor)
dataFactory.registerAsync(customer).get();
CompletableFuture<CustomerRecord> future = dataFactory.fetchAsync("42", CustomerRecord.class);
```

Entities are any subclass of `de.lino.database.database.entity.Serialized`:

```java
final class CustomerRecord extends Serialized {
    private final int id;
    private final String iban;

    CustomerRecord(int id, String iban) { this.id = id; this.iban = iban; }

    @Override
    public List<String> keysOf() {
        return List.of(String.valueOf(id)); // first element = primary key
    }
    // equals()/hashCode()/toString() as usual
}
```

| Method | Throws |
|---|---|
| `register(T)` / `register(T...)` | `DatabaseClientException`, `KeyWrapException` |
| `update(T)` / `update(T...)` | `DatabaseClientException`, `KeyWrapException` |
| `fetch(String, Class<T>)` / `fetch(String[], Class<T>)` | `DatabaseClientException`, `KeyWrapException`, `AuthenticationFailedException` |
| `findById(String, Class<T>)` | same, but `DatabaseClientException` only on a corrupted record, not a merely-absent id |
| `getEntities(Class<T>)` | `DatabaseClientException`, `KeyWrapException`, `AuthenticationFailedException` |
| `delete(String, Class<T>)` / `delete(String[], Class<T>)` | `DatabaseClientException` |
| `clear(Class<T>)` / `deleteSection(Class<T>)` / `reload(Class<T>)` | none |
| every `*Async` variant | never throws synchronously; failures surface via the returned `CompletableFuture`'s `CompletionException` |

`reload(Class<T>)` exists because `cloud-driver-plugin`'s underlying `DatabaseSection` implementations mirror their entries in process-local memory once loaded and never fall back to the database on read - a row written by a different process (or even a different `DataFactory` instance in this same process) is otherwise invisible until this is called or the process restarts.

## `FileFactory` - file upload/download

Uploads, downloads, and deletes `StoredFile`s of any content type, reached via `CloudDriver.getInstance().getFactoryContainer().getFileFactory()` - the file-persistence counterpart of `DataFactory`, built the same "abstract primitives + generic concrete `*Async`" shape. `StoredFile` is itself a `Serialized` domain entity, so files go through the exact same persistence/encryption stack as any other entity - there is no separate storage path.

Every download verifies two independent things before handing content back: the AES-256-GCM authentication tag over the stored ciphertext (`AuthenticationFailedException` on failure), and the plaintext checksum recorded on `StoredFile#checksum()` against the actually-decrypted bytes (`FileIntegrityException` on failure) - so a file that round-trips successfully is guaranteed byte-for-byte identical to what was uploaded.

```java
FileFactory fileFactory = CloudDriver.getInstance().getFactoryContainer().getFileFactory();

StoredFile report = new StoredFile("report-1", "quarterly-report.pdf", pdfBytes);
fileFactory.upload(report);                                   // insert-or-update
fileFactory.upload(fileA, fileB, fileC);                      // batch, dispatched concurrently

StoredFile downloaded = fileFactory.download("report-1");     // throws if absent
List<StoredFile> many = fileFactory.download(new String[]{"report-1","report-2"});
Optional<StoredFile> maybe = fileFactory.findById("report-1"); // empty() if absent
List<StoredFile> all = fileFactory.getEntities();
Optional<FileMetadata> metadata = fileFactory.metadata("report-1"); // descriptive attributes, no content held in memory

fileFactory.delete("report-1");
fileFactory.clear();          // empties the section, keeps it
fileFactory.deleteSection();  // removes the section itself, lazily recreated on next upload()

report.downloadToDevice(Path.of("/tmp/downloads")); // re-creates the file on the local filesystem under its own name
```

`StoredFile` constructors take `fileId`, `fileName`, `content` (`byte[]`) and, optionally, an explicit `FileChecksum`/`createdAt`/`updatedAt` (for re-hydrating a previously-downloaded file). There is no `contentType` parameter - `contentType()` is always inferred from `fileName`'s extension via `Constraints.CONTENT_TYPES`, falling back to `StoredFile.DEFAULT_CONTENT_TYPE` (`application/octet-stream`) if unrecognized or absent, so a file is never rejected merely for having an unrecognized type. The constructor also attempts DEFLATE compression, keeping the compressed bytes only if strictly smaller (`isCompressed()` reports which happened); `checksum()` is always computed over the original, uncompressed plaintext; content itself is held base64-encoded (`String`) rather than a raw `byte[]` field so Gson serializes it cheaply instead of exploding it into a JSON array of numbers, with a `transient volatile byte[]` lazily caching the decoded form.

`FileChecksum`/`FileMetadata` live under `de.lino.cloud.api.file.meta`; `FileIntegrityException` lives under `de.lino.cloud.api.file.exception`.

### Offline-safe file uploads

`FileFactory.upload` ultimately reaches whatever `DatabaseProvider` was configured - in production, typically a database reached over the network (e.g. PostgreSQL) - so an upload attempted with no internet connection would otherwise just fail. Two interfaces in this module make that failure recoverable instead of fatal; every concrete implementation, same as everywhere else in this module, lives in `cloud-driver-plugin`:

- **`ConnectivityChecker`** (`de.lino.cloud.api.security.connectivity`) - `isAvailable()`. Deliberately independent of the database driver: a database call failing does not by itself distinguish "no internet connection" from any other persistence failure. `CloudDriver.getConnectivityChecker()` exposes the instance a `CloudDriver` was installed with (`InternetConnectivityChecker` by default - probes a couple of well-known public DNS resolvers via a short-lived socket connection).
- **`PendingUploadCache`** (`de.lino.cloud.api.file.pending`) - `enqueue`/`remove`/`isEmpty`/`size`/`snapshot`, keyed by `StoredFile#fileId()` (re-enqueuing an id overwrites the previously queued content, the same insert-or-update semantics `upload` itself has).

`DefaultFileFactory` (`cloud-driver-plugin`, behind every `getFileFactory()`) builds this in directly - no separate decorator class: before delegating to `DataFactory`, `upload` checks a `ConnectivityChecker`, and if connectivity is down, enqueues the file(s) into a `PendingUploadCache` instead of failing outright (checked proactively before the call, and again if the call itself fails). `PendingUploadScheduler` (`cloud-driver-plugin`) periodically retries everything queued there - only once the cache is non-empty and connectivity has returned - via `DataFactory#registerAsync` (not `FileFactory#uploadAsync` - `upload`'s own offline-deferral would otherwise let it silently re-queue a file the scheduler's success handling would then immediately remove). A successful retry can be observed via `PendingUploadEvent` (`de.lino.cloud.api.event.database`), dispatched once a queued file's upload succeeds.

```java
FileFactory fileFactory = cloudDriver.getFactoryContainer().getFileFactory(); // already offline-safe - no wrapping needed
DataFactory dataFactory = cloudDriver.getFactoryContainer().getDataFactory();
PendingUploadCache pendingUploadCache = ((DefaultFileFactory) fileFactory).getPendingUploadCache();

PendingUploadScheduler scheduler = new PendingUploadScheduler(dataFactory, pendingUploadCache, cloudDriver.getConnectivityChecker());
scheduler.start(Duration.ofSeconds(30)); // check the pending cache every 30 seconds

fileFactory.upload(report); // deferred into pendingUploadCache instead of thrown if offline right now
```

## The extension framework

A separate concern from the encryption/persistence stack above: a lightweight framework for extensions, under `de.lino.cloud.api.extension` plus `ExtensionFactory`.

- **`Extension`** - abstract base class an extension subclasses. Its constructor only loads `ExtensionProperties` from an `extension.json` classpath resource and detects the build tool via `ProjectBuildDetection` - it does **not** register the instance. A subclass implements the lifecycle hooks (`onLoading`/`onRunning`/`onEnding`/`onException`) and never assembles its own properties. `cloudDriver()`/`getLogger()`/`getWorkingDirectory()` are convenience helpers reaching the host `CloudDriver` singleton, its logger, and `Constraints.EXTENSIONS_PATH` respectively.

```java
public final class DemoExtension extends Extension {
    @Override public void onLoading() { /* prepare resources */ }
    @Override public void onRunning(String[] args) { /* do work */ }
    @Override public void onEnding() { /* release resources */ }
    @Override public void onException(RuntimeException reason) { /* report/recover */ }
}

CloudDriver.getInstance().getFactoryContainer().getExtensionFactory().register(new DemoExtension()); // registration is manual
```

- **`extension.json`** - required in every extension's `resources` folder:

```json
{
  "name": "my-extension",
  "version": "1.0.0",
  "description": "what this extension does",
  "authors": ["Jane Doe"],
  "dependencies": ["some-other-extension"]
}
```

`name`/`version`/`description` are required; `authors`/`dependencies` are optional and default to an empty list. `dependencies` names other extensions by their own `extension.json` `name`.

- **`ExtensionFactory`** (reached via `getFactoryContainer().getExtensionFactory()`) - only `register`/`findByName`/`getExtensions` are abstract; every lifecycle-driving method is concrete on the abstract class itself, built generically on those three primitives:

```java
ExtensionFactory extensionFactory = CloudDriver.getInstance().getFactoryContainer().getExtensionFactory();

extensionFactory.startAll(args); // topological order over getDependencies(); throws IllegalStateException on a cycle
extensionFactory.start(extension, args); // additionally requires every declared dependency registered + already RUNNING
extensionFactory.stopAll();
extensionFactory.stop(extension);

// *Async counterparts run on MultiTaskingFactory's shared virtual-thread executor
extensionFactory.startAllAsync(args);
```

`start` runs each extension on its own dedicated, named (`"extension-" + name`), daemon `Thread` - never on the shared virtual-thread pool, since `onRunning` may block indefinitely - and tracks it in a `ConcurrentHashMap` keyed by extension name so `stop` can later `interrupt()` it. A `RuntimeException` from `onLoading`/`onRunning` is caught, the extension's status is set to `ExtensionStatus.ERROR`, and it's routed to that extension's own `onException` rather than aborting the rest of `startAll`; `stopAll` isolates per-extension `onEnding()` failures the same way. `ExtensionProperties` tracks a `volatile` lifecycle `ExtensionStatus` (`LOADING`/`RUNNING`/`ENDING`/`ERROR`) that `ExtensionFactory` updates as it drives an extension through its lifecycle.

**As of this writing, no dedicated `ExtensionFactory` worked example exists in this repo** - an `ExtensionUsageSample.java` this section previously pointed to (`cloud-driver-plugin/src/test/java/de/lino/cloud/plugin/sample/`) is not currently on disk; that module's `src/test` today holds only `RestFactorySample.java`/`RestFactoryCloudUserSample.java`/`Note.java` (re-verify with `find . -path '*/sample/*' -name '*.java'` before pointing anyone at a specific path). Such a sample would need to live in `cloud-driver-plugin`, not here, since `Extension`'s constructor needs a real `CloudDriver` implementation to exist.

## The event framework

A separate concern from persistence/encryption and from the extension framework above, under `de.lino.cloud.api.event` plus `EventFactory` - though it follows the same "abstract primitives + generic concrete `*Async` methods" shape. Unlike `Extension` (where the caller constructs the instance and only registration is deferred to a factory), an `Event` subclass is constructed *by* `EventFactory` itself, reflectively, via its no-arg constructor - so exactly one instance ever exists per registered class, a singleton reused for every future dispatch of that type.

- **`Event`** - abstract base class an event subclasses, exposing `cloudDriver()`/`terminal()` convenience helpers alongside the single abstract `handle(JsonDocument)`:

```java
public final class OrderPlacedEvent extends Event {
    @Override
    public void handle(JsonDocument properties) {
        String orderId = properties.get("orderId", String.class);
        // ... react to the order ...
    }
}
```

- Two built-in event types ship in `de.lino.cloud.api.event.database`, both dispatched by `cloud-driver-extensions-watcher`'s Postgres change-notification listener: `DatabaseWatchEvent` (reloads `StoredFile`'s section then re-fetches the notified file, logging - not throwing - on a miss, since an uncaught exception here would permanently kill the underlying `LISTEN` thread) and `PendingUploadEvent` (the same reload-then-refetch shape, fired once a previously-queued offline upload succeeds). `de.lino.cloud.api.event.extension` similarly ships `ExtensionRegisterEvent`/`ExtensionUnregisterEvent`, both purely logging a confirmation line to the terminal.
- **`EventFactory`** (reached via `getFactoryContainer().getEventFactory()`) - `registerEvent`/`unregisterEvent`/`dispatch`/`findEventByClass`/`getEvents` are abstract; every `*Async` variant, plus a batch `dispatch(Class<T>, JsonDocument[])` overload, is concrete on the abstract class itself:

```java
EventFactory eventFactory = CloudDriver.getInstance().getFactoryContainer().getEventFactory();

eventFactory.registerEvent(OrderPlacedEvent.class); // constructs and stores the one instance for this type

JsonDocument payload = new JsonDocument().append("orderId", "42");
eventFactory.dispatch(OrderPlacedEvent.class, payload); // dispatches to the registered instance's handle()

Optional<OrderPlacedEvent> maybe = eventFactory.findEventByClass(OrderPlacedEvent.class); // empty() if not registered
eventFactory.unregisterEvent(OrderPlacedEvent.class); // throws IllegalStateException if not registered

// Batch: dispatch many payloads through the same event type concurrently
JsonDocument[] batch = { payload1, payload2, payload3 };
List<OrderPlacedEvent> results = eventFactory.dispatch(OrderPlacedEvent.class, batch);

// *Async counterparts run on MultiTaskingFactory's shared virtual-thread executor
eventFactory.dispatchAsync(OrderPlacedEvent.class, payload);
```

`registerEvent` throws `IllegalStateException` if `OrderPlacedEvent` is already registered, or has no accessible no-arg constructor. `dispatch`/`unregisterEvent` throw `IllegalStateException` if nothing is registered under that class yet ("this must exist") - use `findEventByClass` first if that isn't guaranteed ("does this exist?"). `getEvents()` returns every currently registered event as a plain `Collection<Event>`.

## `RestFactory` - REST exposure over `DataFactory`

Mounts `Serialized` domain entities already reachable through `getDataFactory()` onto an HTTP API, via [Javalin](https://javalin.io) - reached via `CloudDriver.getInstance().getFactoryContainer().getRestFactory()`. It carries no Javalin dependency of its own - purely `DataFactory`/`Serialized`/`MultiTaskingFactory`/`ICloudUserService` - which is exactly why it can live in `cloud-driver-api` (Javalin only ever appears as a dependency in `cloud-driver-plugin`, which supplies the one concrete implementation, `DefaultRestFactory`).

Only `register`/`fetch`/`update`/`delete`/`findByPath`/`getRegisteredPaths`/`getCloudUserService`/`start`/`stop` are abstract; every `*Async` variant is implemented once, generically, directly on `RestFactory` itself - the same "abstract primitives + generic concrete `*Async`" shape `DataFactory`/`FileFactory`/`ExtensionFactory`/`EventFactory` use, and deliberately the same primitive names `DataFactory` itself uses, since each one just wires the HTTP verb that carries out that operation: `register` -> `POST`, `fetch` -> `GET`, `update` -> `PUT`, `delete` -> `DELETE`.

```java
RestFactory restFactory = CloudDriver.getInstance().getFactoryContainer().getRestFactory(); // unauthenticated by default - see below

restFactory.register("/notes", NoteRecord.class); // POST /notes
restFactory.fetch("/notes", NoteRecord.class);     // GET /notes/{id}, GET /notes
restFactory.update("/notes", NoteRecord.class);    // PUT /notes/{id}
restFactory.delete("/notes", NoteRecord.class);    // DELETE /notes/{id}

restFactory.start(8080); // binds "0.0.0.0" - local dev/testing only, see start(host, port)'s Javadoc
restFactory.start("127.0.0.1", 8080); // the production shape, behind a TLS-terminating reverse proxy

Optional<Class<? extends Serialized>> registeredAt = restFactory.findByPath("/notes");
Collection<String> paths = restFactory.getRegisteredPaths();

restFactory.stop();
```

Each verb is independent - calling only `fetch(path, type)` and `delete(path, type)` for a path, without `register`/`update`, exposes a read-and-remove-only resource; there is deliberately no "wire everything at once" convenience. **All four must be called before `start(port)`** - Javalin requires every route to be registered up front in the config block passed to `Javalin.create`, so a resource registered after the server is already listening would never get routes; implementations reject that with `IllegalStateException` instead of silently ignoring it, and reject a duplicate verb/path pair the same way.

`CloudDriver.getInstance().getFactoryContainer().getRestFactory()` is **always unauthenticated** - `DefaultCloudDriver#setInstance` wires it to `DefaultRestFactory`'s single-argument constructor. Two other constructors gate every route: `DefaultRestFactory(DataFactory, ApiKey)` behind a static `X-API-Key` header, and `DefaultRestFactory(DataFactory, AuthService, ICloudUserService)` behind a per-user JWT (see below) - a caller that needs either constructs its own instance directly instead of going through the `CloudDriver` facet.

```java
ApiKey apiKey = dataFactory.findById("primary", ApiKey.class)
        .orElseGet(() -> {
            ApiKey freshKey = new ApiKey("primary"); // generates the raw key + its SHA-256 digest
            dataFactory.register(freshKey);
            System.out.println("New API key: " + freshKey.getApiKeyRaw()); // the only chance to read the plaintext value
            return freshKey;
        });

RestFactory guarded = new DefaultRestFactory(dataFactory, apiKey); // cloud-driver-plugin
// ... register()/fetch()/update()/delete()/start() as above -
// every request now requires a valid "X-API-Key" header, checked via apiKey.isValid(candidate)
```

- **`ApiKey`** (`de.lino.cloud.api.security.rest`, Lombok `@Getter`) - the `Serialized` entity backing that check: `id` (its primary key, so a deployment can keep more than one named key), `apiKeyRaw` (the plaintext key, exposed via `getApiKeyRaw()`), and `apiKeyHashHex` (its SHA-256 digest, used for verification). Both fields are persisted - like every `Serialized` entity, the whole record is envelope-encrypted (AES-256-GCM) before it ever reaches the database, so keeping the raw key is still safe at rest; it exists specifically so a caller can read it back once via `getApiKeyRaw()` right after construction (e.g. to hand it to whoever needs it) - `isValid` itself never reads `apiKeyRaw`, only `apiKeyHashHex`. `@ToString(exclude = "apiKeyRaw")` keeps the raw key out of `toString()`/logs; use `getApiKeyRaw()` explicitly when the value itself is needed. `isValid(String)` hashes a candidate once (a fast digest is appropriate here, unlike `PasswordHasher`'s deliberately slow KDF, since this key is high-entropy and machine-generated rather than user-chosen) and compares via `MessageDigest.isEqual` to avoid a timing side-channel.

No worked example currently exercises the unauthenticated/`ApiKey` paths of `RestFactory`.

### JWT authentication for end-user clients

A second, independent auth mechanism alongside the static `ApiKey` check above - meant for actual end-user clients logging in with a username/email + password rather than holding a static key. This module defines every contract (interface/entity), while `cloud-driver-auth` provides the concrete implementations (`AuthService`, `JjwtSigner`, `CloudUser`, `CloudUserService`) and `cloud-driver-plugin` wires them into `DefaultRestFactory`'s third constructor.

Registration is a two-step, e-mail-verified flow, not a single call: `register` only validates the address and e-mails a short-lived verification code (persisting the hashed password behind the scenes, in `cloud-driver-auth`'s `PendingRegistration`); `confirmRegistration`, given that code back, is what actually creates the `AuthUser` and returns a JWT.

- **`AuthUser`** (`de.lino.cloud.api.jwt.user`) - the persisted account entity: `id` (primary key), `emailAddress`, `passwordHash` (a PHC-style Argon2id string; the raw password is never retained anywhere on this class), and `isAdmin` (a single boolean admin flag - deliberately not a roles/permissions system; its only writer anywhere in the codebase is `IAuthService#setAdmin`, never reachable via REST, only from a terminal operator command, to avoid a privilege-escalation hole). Named `AuthUser` rather than plain `User` deliberately, since `USER` is a reserved SQL keyword and an entity literally named `User` produces an unquoted `CREATE TABLE User (...)` Postgres rejects with a syntax error.
- **`IAuthService`** (`de.lino.cloud.api.jwt.auth`) - the full account lifecycle, grouped by concern:
  - **Registration (two-step, e-mail-verified):** `register(emailAddress, rawPassword)` (validates the address, e-mails a verification code - does *not* create the account yet) then `confirmRegistration(emailAddress, code)` (verifies that code and only then creates the account, returning an `AuthTokens` pair).
  - **Login:** `login(emailAddress, rawPassword)` returns a real `AuthTokens` pair. `validate(jwt)` returns the embedded user id.
  - **Refresh tokens:** `refresh(refreshToken)` exchanges a still-valid, longer-lived opaque refresh token for a fresh `AuthTokens` pair (rotated on every use), so a long-running client can stay signed in past the 12h access-token lifetime without a password re-login; `revokeRefreshToken(refreshToken)` is the logout-time counterpart (idempotent).
  - **Password reset / e-mail change:** `requestPasswordReset(emailAddress)`/`confirmPasswordReset(emailAddress, code, newPassword)` and `requestEmailChange(authUserId, newEmailAddress)`/`confirmEmailChange(authUserId, code)` - both the same two-step, e-mail-verified shape as registration.
  - **Admin/lookup:** `getAuthUsers()`/`getAuthUser(authUserId)` (list/lookup, admin-gated over REST), `setAdmin(authUserId, isAdmin)` (terminal-only, see `AuthUser` above).

  Framework-agnostic throughout: every method throws a plain exception (`InvalidCredentialsException`/`InvalidJwtException`/`InvalidVerificationCodeException`/`InvalidPasswordFormatException`/`InvalidRefreshTokenException`/`EmailAlreadyRegisteredException`, all `de.lino.cloud.api.jwt`) rather than an HTTP-specific type, leaving translation into an actual HTTP response to whatever wires this into Javalin. `register`/`confirmRegistration` are exposed as `POST /auth/register`/`POST /auth/register/confirm` by `cloud-driver-plugin`'s `DefaultRestFactory` whenever it's constructed with an `AuthService` - this deployment's open, e-mail-verified self-registration flow; there is no separate operator-run account-creation tool.
- **`JwtSigner`** (`de.lino.cloud.api.jwt`) - `sign(subject, ttlSeconds)`/`verify(token)`, the stateless-JWT contract `IAuthService`'s implementation builds on.
- **`EmailSender`/`EmailDeliveryException`** (`de.lino.cloud.api.mail`) - `send(toAddress, subject, htmlBody, plainTextBody)`, the contract `AuthService#register`/`#requestPasswordReset`/`#requestEmailChange` deliver their verification codes through, as a `multipart/alternative` HTML/plain-text pair. `cloud-driver-auth` supplies the concrete implementations: `SmtpEmailSender` (SMTP+STARTTLS via Jakarta Mail/Angus Mail, the only one meant for production - also attaches the Cloud Driver logo inline for the HTML part) and `LoggingEmailSender` (logs the plain-text body instead of sending, used as a fallback when no SMTP server is configured).
- **`Owned`** (`de.lino.cloud.api.jwt.rest`) - one method, `ownerId()`. An entity implementing this and (de)serializing its owner id under the JSON field `"ownerId"` lets a JWT-authenticated `DefaultRestFactory` scope reads/writes to the caller's own data instead of exposing every record of that type to every logged-in user; only takes effect on the JWT-gated constructor.

### The `user` package - end-user <-> owned-file bookkeeping

- **`ICloudUser`** (`de.lino.cloud.api.user`) - the behavioral contract a persisted per-user record (`CloudUser`, in `cloud-driver-auth`'s `de.lino.cloud.auth.entity` package) implements: `getAuthUserId()` (ties the record back to its owning `AuthUser`), `getStoredFiles()` (an unmodifiable, on-demand-resolved view of every `StoredFile` this user currently owns, delegating to `ICloudUserService#listFiles` rather than holding the list as state on the record itself), plus `getMaxBytesToUpload()`/`setMaxBytesToUpload(long)`, `getCurrentUploadedBytes()`/`setCurrentUploadedBytes(long)`, and `isUploadLimitReached(long)` (per-account upload quota tracking - `currentUploadedBytes + bytesToUpload >= maxBytesToUpload`). File ownership is deliberately *not* modeled as a field on this contract - it is tracked through dedicated per-(user, file) records instead (`StoredFileOwnership`, alongside `CloudUser` in `de.lino.cloud.auth.entity`), so adding/removing one owned file never requires decrypting and re-encrypting an entire user record; `getStoredFiles()` only resolves those records into a snapshot view when called.
- **`ICloudUserService`** (`de.lino.cloud.api.user`) - has grown well beyond upload/list/delete since this doc was first written; every method still takes the authenticated caller's plain `authUserId` rather than a full `AuthUser` (the only thing available once a JWT has been validated), grouped here by concern:
  - **Account lifecycle:** `getOrCreate(authUserId)`, `getCloudUser(authUserId)`/`getCloudUserByEmail(emailAddress)` (read-only lookups, `Optional`), `getCloudUsers()` (every registered account), `resetCloudUser(authUserId)` (wipes every owned file/folder, keeps the account record), `deleteCloudUser(authUserId)` (`resetCloudUser` plus removing the account record itself), `resolveOwnerAuthUserId(storedFileId)` (reverse lookup for `DatabaseWatchEvent`), `updateCloudUserBytesUsage`/`updateCloudUserBytesLimit`/`recomputeUploadedBytes` (quota bookkeeping).
  - **Files:** `uploadFile(authUserId, fileName, content[, folderId])`, `listFiles`/`listFilesWithFolder`/`listFileSummaries[Page]` (content-free listings, preferred for rendering), `getFile(authUserId, storedFileId)` (full content, share-aware - see below), `moveFile(authUserId, storedFileId, folderId)`.
  - **Folders:** `createFolder`, `listFolders[Page]`, `updateFolder` (rename+move in one call), `deleteFolder` (refuses a non-empty folder).
  - **Recycle bin / soft delete:** `deleteFile`/`deleteFolder` (soft - flips a `deletedAtEpochMillis` flag rather than removing anything) paired with `restoreFile`/`restoreFolder`, `listDeletedFiles`/`listDeletedFolders` (returning `TrashedFileSummary`/`TrashedFolderSummary` - the trashed item paired with when it becomes eligible for permanent removal under the configured retention window), and `emptyTrash(authUserId)` (permanently removes everything currently trashed, bypassing that window entirely - irreversible).
  - **Sharing (read-only, item 9):** `shareFile`/`shareFolder(ownerAuthUserId, id, granteeEmail)`, `revokeFileShare`/`revokeFolderShare`, `listSharedWithMe`/`listSharedFoldersWithMe(authUserId)` (returning `SharedFileSummary`/`SharedFolderSummary` - the item paired with the sharing account's email), `listFileShares`/`listFolderShares(ownerAuthUserId, id)` (the owner-side "who is this shared with" listing, by email), and `listSharedFolderContents(authUserId, folderId)` (browsing a shared folder's contents - returns `SharedFolderContents`, honoring both a direct grant and one inherited from any ancestor folder). A folder share covers everything nested inside it, at any depth; sharing is always owner-only to mutate (a grantee can never re-share, move, delete, or upload into what was shared with them).

  Reached either through `RestFactory#getCloudUserService()` or `IServiceContainer#getCloudUserService()`.

## Cross-cutting service contracts: `audit`, `metrics`, `push`

Three small, single-purpose interfaces, each following the identical shape: defined here so the `cloud-driver-api`/`cloud-driver-auth` call sites that need to use them never depend on the extension/module that actually implements them (or on any third-party library, e.g. Micrometer for metrics); each is `null` on `IServiceContainer` until the owning extension has actually started and published a real instance, so every caller must null-check rather than assume non-null; and each is documented as **must never throw** - a broken audit/metrics/push sink must never fail the real action it's only observing.

- **`AuditLogService`** (`de.lino.cloud.api.audit`) - one method, `record(AuditEvent event)`. `AuditEvent` (`id`, `actorAuthUserId` nullable, `action` an `AuditAction` enum - `LOGIN_SUCCESS`/`LOGIN_FAILURE`/`REGISTER`/`PASSWORD_RESET`/`EMAIL_CHANGE`/`FILE_DELETE`/`ACCOUNT_DELETE`/`TWO_FACTOR_ENABLED`/`TWO_FACTOR_DISABLED`, `targetId` nullable, `timestampEpochMillis`, `metadata` nullable) is envelope-encrypted at rest like any other `Serialized` entity. Published into `IServiceContainer#setAuditLogService` once the JWT-authenticated REST extension starts; reachable from a terminal `Command` as read-only history (`auditLog`/`audit`), never mounted over REST itself.
- **`MetricsRecorder`** (`de.lino.cloud.api.metrics`) - four event-style counters: `recordUploadSuccess()`, `recordUploadFailure()`, `recordUploadQueued()` (deferred to the offline pending-upload queue), `recordUploadQuotaRejected()`. Deliberately counters only, not gauges - poll-based numbers (pending-queue depth, per-`ExtensionStatus` counts) are read directly off `CloudDriver`/`ExtensionFactory` by the metrics extension itself on every scrape, with no need for anything upstream to know it's being measured.
- **`LiveUpdatePublisher`** (`de.lino.cloud.api.push`) - one method, `publish(authUserId, table, operation, id)`, pushing a small change notification (never the changed row's own, still-encrypted data) to every session currently connected under `authUserId`. Called from `DatabaseWatchEvent#handle` inside a Postgres `LISTEN`/`NOTIFY` listener thread with zero tolerance for an uncaught exception, which is exactly why "must never throw" is load-bearing here, not just documentation.

## The `security` package

Six sub-packages, each a thin layer around the previous one. Extension code normally only touches `CloudDriver`/`DataFactory` directly - this section is for understanding what happens underneath, or for using a piece standalone. This module supplies the interfaces/value objects below; every concrete implementation lives in `cloud-driver-plugin`.

### `security.crypto` - authenticated encryption

`AeadEncryptionService` is the interface (`AesGcmEncryptionService` is its AES-256-GCM implementation in `cloud-driver-plugin`). A fresh, random nonce is drawn for every call and never reused with the same key.

```java
EncryptedPayload encrypt(byte[] plaintext, SecretKey key, byte[] associatedData); // associatedData may be null
byte[] decrypt(EncryptedPayload payload, SecretKey key) throws AuthenticationFailedException;
```

`EncryptedPayload` (`algorithmId`, `nonce`, `ciphertext`, `associatedData`) defensively copies every array field on construction and on every accessor call, so neither the caller nor the record can mutate shared state after the fact. `CryptoAlgorithm` restricts the algorithm space to `AES_256_GCM` (mandated default) and `AES_128_GCM` (kept as an alternative), each carrying its own key/nonce/tag lengths.

### `security.keys` - data-encryption and key-encryption keys

- **`DataEncryptionKey`** (DEK) - a short-lived, random AES key protecting a single payload. Call `destroy()` once done with it to zero the raw key material in place.
- **`KeyEncryptionService`** (KEK/KMS abstraction) - `wrap`/`unwrap` a DEK under the currently active key-encryption key, plus `activeKeyEncryptionKeyId()`/`rotate()`. `rotate()` activates a new KEK version for future wraps without invalidating the ability to unwrap data wrapped under earlier versions.
- **`WrappedKey`** - a wrapped DEK: `keyEncryptionKeyId` (which KEK version wrapped it, so the right one is found again even after rotation), `wrappedKeyMaterial`, `wrapAlgorithm`, `dataEncryptionKeyAlgorithmId`.

Three `KeyEncryptionService` implementations exist in `cloud-driver-plugin`, all explicitly documented as **not for production** (swap in a real KMS/HSM client instead): `InMemoryKeyEncryptionService` (KEK in process memory only), `FileKeyEncryptionService` (KEK persisted to a local JSON file), `DatabaseKeyEncryptionService` (KEK persisted as a `DatabaseEntry`, shared across processes/nodes).

```java
WrappedKey wrapped = kms.wrap(dek);
DataEncryptionKey unwrapped = kms.unwrap(wrapped);
String newKeyId = kms.rotate();
```

### `security.envelope` - tying crypto + keys together

`EnvelopeEncryptionService` (`cloud-driver-plugin`) is the facade most other code builds on: generate a DEK -> encrypt with it -> wrap the DEK under the active KEK -> return both together as an `EnvelopeEncryptedPayload` (`schemaVersion`, `wrappedDataEncryptionKey`, `payload`). `schemaVersion` lets the on-the-wire envelope format itself evolve later without breaking the ability to read older envelopes.

### `security.hash` - general-purpose hashing

`HashAlgorithm` offers `SHA_256`/`SHA_384`/`SHA_512` only - MD5 and SHA-1 are not representable, by design. (`Hasher`, the static digest utility, lives in `cloud-driver-plugin`.)

### `security.password` - Argon2id

`PasswordHasher` (`hash(char[])`/`verify(char[], String)`) is only relevant if the extension itself must store a password - prefer OAuth 2.0 client credentials or mTLS for service-to-service auth instead. `Argon2idPasswordHasher` (`cloud-driver-plugin`) is the OWASP-baseline implementation.

### `security.database` - the on-disk envelope shape

`EncryptedEntityRecord` is the JSON storage format of an `EnvelopeEncryptedPayload`: every binary field base64-encoded, so it can be stored as a plain document under a `database-driver-api` `DatabaseEntry` - the database never sees anything else. `DatabaseClientException` (checked) signals a failed persistence operation (not found, id collision, corrupted record).

### `security.secrets` and `security.entity` (plugin only)

Not represented by an interface in this module - both `SecretRedactor` (defense-in-depth text redaction before logging) and `SecureEntityChannel` (bridges `EnvelopeEncryptionService` and a `Serialized` entity, binding the entity's type name + primary key into the authenticated associated data) are concrete classes in `cloud-driver-plugin`.

## The `terminal` package

A separate, self-contained concern from everything else in this module: an interactive terminal/console engine, under `de.lino.cloud.api.terminal`. Unlike every other facet, a `Terminal` does **not** go through `CloudDriver` at all - it is constructed directly (`new Terminal()` or `new Terminal(PromptProvider)`), independently of whether `CloudDriver.getInstance()` is set up.

This module implements the terminal **engine** only, deliberately not any concrete command - `Command`/`CommandService` exist so the reading loop has something to dispatch into, not as a catalog of real commands (those live in `cloud-driver-extensions-terminal`).

```java
Terminal terminal = new Terminal();                  // requires a real terminal (a real pty) - throws in an IDE console
terminal.getCommandService().register(myCommand);    // register commands before starting the reading loop
terminal.readingThread().start();                    // begins the blocking interactive loop - not started automatically

terminal.display("&aHello&7, world!");                // printed above the prompt, then redraws it
terminal.displayApproved("&7Safe to call while reading a line");
boolean confirmed = terminal.confirm("&eProceed? (y/n)");

terminal.attachLogging(someLogger);                   // routes that logger's output through this terminal instead of stdout
terminal.shutdown();                                  // idempotent; closes the underlying jline terminal
```

- **`Terminal`** wraps a `jline` `Terminal`/`LineReaderImpl` pair and owns one `CommandService` and one `ReadingThread`, both constructed internally. Requires a real terminal by design (`.dumb(false)`) - construction fails in an IDE's Run/Console tool window, which pipes stdout/stdin rather than allocating a real pty; this is expected, not a bug.
- **`ReadingThread`** blocks on `LineReader#readLine`, splits each line on whitespace, and dispatches the first token through `CommandService#dispatchAsync` so a slow command never delays the next line being read. Deliberately not a daemon thread, since starting it is typically what keeps the process alive.
- **`Command`/`CommandService`** - a `Command` is a name, optional aliases, a description, and an `execute(CommandArguments)` action; no typed-argument/syntax DSL. `CommandService` indexes by lowercased name/alias for O(1) lookup and caches an immutable snapshot, rebuilt only on register/unregister.
- **`TabCompleter`** - a `jline` `Completer` suggesting registered command names/aliases while the first word of the input line is being typed, reusing `CommandService#registeredCommands()`'s own cached snapshot rather than maintaining a second cache.
- **`AnsiColors`** - legacy Minecraft-style `&x` codes translated to ANSI SGR escape sequences, precomputed once per enum constant and looked up via a map built once at class-initialization time, since this sits on the hot path of every colored log line and prompt redraw.
- **`TerminalLogHandler`/`TerminalLogFormatter`** - a `java.util.logging.Handler`/`Formatter` pair routing log records through a `Terminal` (colored by level, with a colored recursive stack trace on a thrown exception) instead of a plain console handler. Install via `Terminal#attachLogging(Logger)`.
- **`PromptProvider`/`DefaultPromptProvider`** - builds the `&x`-coded prompt string a `Terminal` starts with; the default returns a `cloud-driver@<random>` prompt with a fresh suffix every call.

`cloud-driver-extensions-terminal` is the first real (non-placeholder) consumer: it registers a handful of `Command`s and is the only place in the codebase that actually calls `readingThread().start()`.

## Utilities

- **`MultiTaskingFactory`** (`de.lino.cloud.api.utility.task`) - singleton wrapping one process-wide `ExecutorService` backed by virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`). Every `*Async` method across `DataFactory`, `FileFactory`, `ExtensionFactory`, `EventFactory`, `RestFactory`, and `CommandService` is built on this. `runTaskInMainSafety(Runnable)` runs a task on the calling thread, then shuts the executor down and blocks until every submitted task finishes - call only from an extension host's real `main(String[])`, as its final action, since dispatching it via `runAsync`/`supplyAsync` instead would run it on a daemon virtual thread and let the JVM exit before any background work gets a real chance to run.
- **`Asserts`** (`de.lino.cloud.api.utility`) - shared null-validation helpers (`requireNonNull`, with a dedicated `CloudDriver` overload that fails with a message pointing at `DefaultCloudDriver.setInstance` instead of a bare `NullPointerException`) plus `runWallTimeTest(Runnable)` - runs a `Runnable` once and prints CPU time, memory delta, and wall-clock time to standard out; a quick spot-check, not a substitute for a real benchmarking harness.
- **`Constraints`** (`de.lino.cloud.api.utility`) - shared constants: `WORKING_DIRECTORY` (`user.dir`), `CONFIGURATION_PATH` (a `cloud-driver` subdirectory of it), `EXTENSIONS_PATH` (a sibling `extensions` subdirectory `cloud-driver-plugin`'s jar scanner loads third-party `Extension`s from), `USER_DOWNLOADS_PATH`, `REQUIREMENTS_UUID` (the fixed id `CloudBootstrap` uploads `SECURITY_REQUIREMENTS.md` under on every startup, for idempotency across restarts), and `CONTENT_TYPES`, the file-extension-to-MIME-type lookup table `StoredFile` infers `contentType()` from. Not exhaustive - extend it here if a new extension needs recognizing.
- **`CursorPage<T>`** (`de.lino.cloud.api.utility`) - `record CursorPage<T>(List<T> items, String nextCursor)`, one page of a cursor-paginated listing (`nextCursor` `null` once nothing more remains). Backs `ICloudUserService#listFileSummariesPage`/`#listFoldersPage` and `cloud-driver-plugin`'s `GET /files`/`GET /folders` `?limit=` opt-in response envelope; only bounds the size of one response, not the underlying scan cost, since the listings it paginates have no SQL-level cursor available (see its own Javadoc for the full trade-off).
- **`UnitParser`** (`de.lino.cloud.api.utility`) - `parsePercentage(long)`, formatting a fractional value as a locale-independent `"NN.NN %"` string (always a `.` decimal separator, regardless of the JVM's default locale).

## Performance characteristics

What's synchronous, what's asynchronous, what's cached, and what's concurrent-safe, purely as this module's contracts define it (the actual work happens in `cloud-driver-plugin`'s implementations, but the shape below is fixed by the abstract classes here):

- **Every facade class (`DataFactory`, `FileFactory`, `ExtensionFactory`, `EventFactory`, `RestFactory`) is synchronous by default, with a mechanically generated async counterpart for every operation.** Each abstract class declares a small set of abstract sync primitives; every `*Async` method is a *concrete* method on that same abstract class, implemented once in terms of the sync primitives via `MultiTaskingFactory.getInstance().runAsync(...)`/`supplyAsync(...)`. A new implementation only ever has to write the synchronous half - the async half is free and automatically consistent with it.
- **All asynchronous work runs on one process-wide virtual-thread-per-task executor** (`MultiTaskingFactory`, `Executors.newVirtualThreadPerTaskExecutor()`), not a bounded thread pool. Virtual threads make this cheap to over-subscribe (thousands of concurrent `*Async` calls each just park on I/O rather than pinning an OS thread), which is why batch operations (`register(T...)`, `delete(String[], Class)`, `dispatch(Class, JsonDocument[])`, `RestFactory`'s route handlers under Javalin) dispatch one task per item rather than looping sequentially - see the individual sections above.
- **`ExtensionFactory#start` is the one deliberate exception** - it spawns a dedicated, named, daemon `Thread` per extension rather than using the shared executor, specifically because an extension's `onRunning` may block indefinitely (e.g. a CLI reading `System.in`). Keeping that off the shared virtual-thread pool means one long-blocked extension can never exhaust or pin every carrier thread backing it.
- **Caching is a `cloud-driver-plugin` implementation detail this module's contracts anticipate but don't perform themselves** - `DataFactory#reload`/`FileFactory` exist precisely because the underlying `EntityDatabaseClient` (plugin-side) keeps a read-through, write-through, TTL-bounded cache of decrypted entities per type. This module's job is to expose the escape hatch (`reload`), not to implement the cache.
- **`ExtensionFactory`, `EventFactory` (via `DefaultEventFactory`'s use of `database-driver-api`'s `Cache`), and `CommandService` are all designed around concurrent-safe registries** - `ExtensionFactory#runningThreads` is documented as backed by a `ConcurrentHashMap`, and `CommandService` documents its lookup map as safe under concurrent `register`/`dispatch` calls with an immutable, only-rebuilt-on-mutation `snapshot()`.
- **Terminal I/O is intentionally kept off the shared executor's synchronous path** - `ReadingThread` dispatches every parsed line via `CommandService#dispatchAsync` specifically so one slow command can never delay the next line being read from the terminal.

## Data handling

What this module models, and how data flows through it end to end:

- **`Serialized` entities are the universal unit of persistence.** Anything persisted through `DataFactory` - and, since `StoredFile` is itself one, anything persisted through `FileFactory` too - is a `de.lino.database.database.entity.Serialized` subclass. This module never touches raw database rows directly; it only ever hands a `Serialized` instance to `DataFactory` and gets one back.
- **The envelope-encryption value objects model one round trip through the crypto stack, not general-purpose crypto types.** `EncryptedPayload` (one AEAD operation's output) is wrapped by an `EnvelopeEncryptedPayload` (that payload plus its wrapped DEK) which is in turn flattened into an `EncryptedEntityRecord` (the base64-everything JSON shape actually persisted). Each of the three is a distinct, purpose-built record - there is no single generic "ciphertext blob" type, because each layer needs different fields visible to the layer above it (e.g. `WrappedKey#keyEncryptionKeyId` must survive KEK rotation; `EncryptedPayload#associatedData` must survive being base64-encoded for storage).
- **`StoredFile` is a domain entity with derived, cached, and defensively-copied state layered on top of a small amount of stored truth.** The only things actually persisted are `fileId`, `fileName`, `contentBase64`, `contentCompressed`, `checksum`, and the two epoch-millis timestamps; `contentType()`, `content()`, `sizeBytes()`, `createdAt()`/`updatedAt()` (as `Instant`), and `metadata()` are all derived on demand from that stored state, with `content()`'s decode-and-decompress step memoized in a `transient volatile byte[]` rather than repeated on every call.
- **Sensitive material is either never retained, retained-but-hashed, or retained-and-defensively-copied - never retained as a mutable, shareable reference.** `AuthUser` never has a field for the raw password at all. `ApiKey` is the deliberate exception (a machine-generated key must be handed back once), but even then `@ToString(exclude = "apiKeyRaw")` keeps it out of logs. Every byte-array-carrying record here (`EncryptedPayload`, `WrappedKey`) clones its array fields both on construction and on every accessor call, so neither the caller nor the record's own internals can mutate shared state through an aliased array reference.
- **Ownership metadata is modeled as a marker interface plus a JSON field-name convention, not a base class.** `Owned#ownerId()` is the entire contract; a type opts in by implementing it and serializing that value under the JSON key `"ownerId"` - there is no `OwnedEntity` base class to extend, so an entity can be `Owned` while still extending whatever else it needs to (in practice, `Serialized`).
- **`Folder` tracks no membership list of its own - the same "don't force an O(n) rewrite" reasoning `StoredFileOwnership` (`cloud-driver-auth`) exists for.** A `Folder` (`folderId`, `ownerId`, `name`, `parentFolderId` nullable, two timestamps, `deletedAtEpochMillis` nullable) only ever points at its own parent; which files/subfolders sit inside it is derived by querying *their* placement, never stored as a list on the folder itself - moving a file in/out of a large folder never means rewriting that folder's own encrypted record.
- **A recurring "pair the item with one extra piece of context" record shape backs both sharing and trash.** `SharedFileSummary`/`SharedFolderSummary` pair a `StoredFileSummary`/`Folder` with the sharing account's email; `TrashedFileSummary`/`TrashedFolderSummary` pair the same base types with a computed `purgeAtEpochMillis`. Each is a thin wrapper record rather than an extra field bolted onto `StoredFileSummary`/`Folder` themselves, so a live (non-shared, non-trashed) listing never carries an always-`null` field it has no use for.

## Safety/security

This module defines the crypto/security *contracts*; the actual algorithms run in `cloud-driver-plugin`, but every guarantee below is fixed at this layer so no implementation can quietly weaken it:

- **AEAD only, with a closed algorithm set.** `AeadEncryptionService` never exposes an unauthenticated encryption mode, and `CryptoAlgorithm` only offers `AES_256_GCM`/`AES_128_GCM` - there is no way to construct, say, AES-CBC through this contract.
- **DEK/KEK split, with mandatory rotation support baked into the interface.** `KeyEncryptionService#rotate()` is not optional - every implementation must support activating a new KEK version without losing the ability to `unwrap` data wrapped under an older one, and `WrappedKey#keyEncryptionKeyId` exists specifically so the right KEK version can always be located again after rotation.
- **A closed hashing algorithm set with the weak ones structurally unrepresentable.** `HashAlgorithm` is `SHA_256`/`SHA_384`/`SHA_512` only - MD5 and SHA-1 cannot be requested through this enum at all, not merely discouraged by convention.
- **Passwords go through a deliberately slow, salted KDF contract (`PasswordHasher`), machine-generated secrets through a fast, constant-time one (`ApiKey#isValid`).** The two are never interchangeable in this codebase: `ApiKey` explicitly does not use `PasswordHasher`-style hashing (a slow KDF would be pointless overhead for a 256-bit random value nobody has to memorize), while `AuthUser#passwordHash` is only ever produced by a real `PasswordHasher` implementation (Argon2id in practice).
- **Type + primary-key binding defends against payload substitution.** `SecureEntityChannel` (plugin-side, but a direct consumer of this module's `AeadEncryptionService`/`EnvelopeEncryptionService` contracts) binds an entity's type name and primary key into the AEAD associated data, so a stored ciphertext can never be silently swapped for a different entity's or a different record's payload and still decrypt successfully.
- **Per-request ownership scoping is opt-in and additive, never assumed.** `Owned` only takes effect on a JWT-authenticated `RestFactory`; an entity exposed through the unauthenticated or `ApiKey`-gated constructors is never filtered by ownership, since neither has a per-request user identity to scope by in the first place - this module makes that limitation explicit in `Owned`'s own Javadoc rather than leaving it to be discovered at runtime.
- **A persisted, encrypted-at-rest audit trail exists specifically so security-relevant actions are reconstructible after the fact.** `AuditEvent` (`de.lino.cloud.api.audit`) is envelope-encrypted like any other entity, and `AuditLogService#record` is documented to never throw - a failure to *record* an action must never be allowed to block or fail the real action being audited, but it also must never be silently skipped by a call site forgetting its own try/catch, so that guarantee is enforced once, in the implementation, not trusted to every caller.
- **Revealing account existence is a deliberate, narrow exception to this codebase's usual "don't leak" default, not an oversight.** `GranteeAccountNotFoundException` (`de.lino.cloud.api.user`) exists specifically so a share attempt against an unregistered grantee email gets a clear, honest error - safe here (unlike, say, login) because the caller is already an authenticated account holder sharing their own file, not an anonymous visitor probing for valid addresses.
- **Constant-time comparison wherever a candidate secret is checked against a stored digest.** `ApiKey#isValid` compares via `MessageDigest.isEqual`, not `String#equals`, specifically to avoid a timing side-channel on the digest comparison.
- **Defense-in-depth secret redaction is explicitly not a substitute for not logging secrets in the first place** - `SecretRedactor` (plugin-side) is documented that way, and this module's own `TerminalLogFormatter`/`CloudDriver#getLogger()` route every log line through one exclusive handler rather than leaving a chance for a second, unfiltered handler to also see raw log records.

## Scalability

How the "abstract primitive + generic `*Async` on a shared virtual-thread executor" pattern used throughout this module scales, and where the seams are:

- **Adding a new bulk/async operation to any facade never requires touching an implementation.** Because every `*Async` method is implemented once, generically, on the abstract class itself (not duplicated per-implementation), a new concrete `DataFactory`/`FileFactory`/`ExtensionFactory`/`EventFactory`/`RestFactory` only has to implement the small sync primitive set - it inherits every batch/async variant for free, and inherits it *correctly*, since there is exactly one implementation of e.g. `registerAsync` to get right, not one per backend.
- **Virtual threads mean the executor itself doesn't need to be sized for load.** `MultiTaskingFactory`'s single, process-wide `Executors.newVirtualThreadPerTaskExecutor()` creates a new virtual thread per submitted task rather than queuing behind a fixed pool size - so a spike in concurrent `*Async` calls (e.g. `RestFactory`'s Javalin handlers, or `DataFactory.register(T...)` on a large batch) doesn't need a capacity-planning decision the way a bounded `ThreadPoolExecutor` would; the practical ceiling becomes the downstream resource each task actually blocks on (database connection pool size, KMS/HSM rate limits), not thread count.
- **The one place that deliberately opts *out* of the shared executor (`ExtensionFactory#start`) is a scalability boundary, not an oversight.** An extension's `onRunning` can run indefinitely; giving each one its own dedicated thread means the number of *concurrently running extensions* has no bearing on the shared executor's ability to serve every other facade's `*Async` calls - the two workloads (short-lived async tasks vs. long-lived extension lifecycles) are kept on structurally separate thread pools for exactly this reason.
- **`Owned`'s field-name convention over a base class is what lets ownership scoping compose with pre-existing types.** Since opting into per-user REST scoping is "implement one interface method and use one JSON field name" rather than "extend a specific base class," it scales to entities that already have their own inheritance needs elsewhere (e.g. `Serialized`) without this module needing to anticipate every combination up front.
- **The identified non-scaling seam is intentionally left to `cloud-driver-plugin`, not hidden in this module's contracts.** `DataFactory#reload` exists because per-type, process-local section mirrors don't scale to multi-process writers without an explicit signal to refresh - this module surfaces that signal as a first-class operation instead of pretending the underlying storage layer is always consistent. Similarly, `ICloudUserService#listFiles`' current implementation (a full scan filtered in memory, documented on the `cloud-driver-auth` side) is a known, deliberately-accepted scaling limit this module's interface does not itself preclude fixing later (e.g. via a real indexed query) without a contract change.

## Javadoc conventions

Every public and protected class, interface, enum, method, and field in this module carries Javadoc following the Google Java Style Guide shape - a one-sentence summary fragment, a blank line, then `@param` (one per parameter, describing what the value means, not just echoing its name), `@return` where applicable, and `@throws` for every checked or behaviorally significant exception. A handful of conventions are worth calling out explicitly, since they're easy to miss skimming individual files:

- **`@NonNull` (Lombok) on concrete methods, `@NotNull` (`org.jetbrains.annotations`, doc-only) on abstract ones.** An abstract method has no method body for Lombok to inject a runtime null-check into, so `@NotNull` there is purely documentation of intent; a concrete method's `@NonNull` actually generates the check. This split is consistent across `extension`, `factory`, `event`, and `terminal.service` - e.g. `ExtensionFactory#start`/`#stop` (concrete, `@NonNull`) versus `ExtensionFactory#register`/`#findByName` (abstract, `@NotNull`).
- **`Asserts.requireNonNull` is this module's own null-check primitive** where Lombok's `@NonNull` isn't in play (e.g. constructors of concrete value objects like `EncryptedPayload`, `WrappedKey`, `StoredFile`) - each call site's own message names the failing field and the enclosing class, e.g. `"@StoredFile: fileId cannot be null"`, so a `NullPointerException` thrown from deep inside a record's compact constructor is still immediately traceable to which field failed without needing a stack trace lookup.
- **Every abstract "facade" class (`DataFactory`, `FileFactory`, `ExtensionFactory`, `EventFactory`, `RestFactory`) documents its abstract/concrete split in its own class-level Javadoc** ("`X`, `Y`, and `Z` are abstract; every `*Async` variant below is implemented here generically in terms of those") - so a reader never has to infer the pattern by diffing method bodies.
- **`@throws` entries name the condition, not just the exception type** - e.g. `DataFactory#findById`'s Javadoc distinguishes "returns `Optional.empty()`" from "throws `DatabaseClientException`" by explicitly stating the record must exist-but-be-corrupted for the latter, since both a missing and a corrupted record could otherwise plausibly map to either outcome.
- **This documentation pass brought every public/protected member in this module up to that same bar** - including the small number of gaps found while writing it (missing `@param`/`@return` tags on a few `jwt`/`user`/`terminal` methods, undocumented enum constants on `AnsiColors`, and two newly-added interfaces still under active development, `IFactoryContainer`'s new `factory.container` home and `factory.service.IServiceContainer`). Anything added to this module going forward is expected to match it from the start, not be brought up to standard later.
