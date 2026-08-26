# cloud-driver-api

This module defines `cloud-driver`'s public contract: interfaces, abstract classes, value objects/records, and exceptions. It has **no concrete implementations** - every implementation lives in `cloud-driver-plugin`, which depends on this module (never the other way around).

The cryptographic design follows `SECURITY_REQUIREMENTS.md` (bundled as a resource in this module, under `src/main/resources`) - envelope encryption with AES-256-GCM, KMS/HSM-backed key wrapping with rotation, authenticated-tag verification, Argon2id password hashing. Section references in Javadoc (e.g. "section 9") point back to that document.

## Coordinates

```xml
<dependency>
    <groupId>de.lino.cloud.api</groupId>
    <artifactId>cloud-driver-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

A consuming extension almost always also needs `cloud-driver-plugin` (every concrete implementation) and a `de.lino.database` `database-driver-plugin` `DatabaseProvider` (JSON file store, H2, MySQL, PostgreSQL, MongoDB, ...).

```xml
<dependency>
    <groupId>de.lino.cloud.plugin</groupId>
    <artifactId>cloud-driver-plugin</artifactId>
    <version>1.0.0</version>
</dependency>
```

This module itself depends only on `database-driver-api` (pinned to `1.3.11`) and `org.jetbrains:annotations` (`@NotNull`/`@Nullable` on the public API surface).

## `CloudAPI` - a facade over five factories and a connectivity facet

`CloudAPI` is deliberately thin: a shared-instance accessor (`getInstance()`) plus six abstract getters. It holds no persistence or lifecycle logic itself.

```java
public abstract class CloudAPI {
    public abstract DataFactory getDataFactory();
    public abstract FileFactory getFileFactory();
    public abstract ExtensionFactory getExtensionFactory();
    public abstract ConnectivityChecker getConnectivityChecker();
    public abstract EventFactory getEventFactory();
    public abstract RestFactory getRestFactory();
}
```

- **`getDataFactory()`** - encrypted entity persistence. See [`DataFactory`](#datafactory---entity-persistence).
- **`getFileFactory()`** - file upload/download, persisted through the very same mechanism as any other entity. See [`FileFactory`](#filefactory---file-uploaddownload).
- **`getExtensionFactory()`** - registers, starts, and stops `Extension` extensions. See [`ExtensionFactory`](#the-extension-framework).
- **`getConnectivityChecker()`** - reports whether outbound network connectivity is currently available. See [Offline-safe file uploads](#offline-safe-file-uploads).
- **`getEventFactory()`** - registers, looks up, unregisters, and dispatches `Event` events. See [`EventFactory`](#the-event-framework).
- **`getRestFactory()`** - mounts entities already reachable through `getDataFactory()` onto an HTTP API, **unauthenticated by default**. See [`RestFactory`](#restfactory---rest-exposure-over-datafactory).

Exactly one implementation is installed process-wide via a static factory method on that implementation - e.g. `DefaultCloudAPI.setInstance(DatabaseProvider, EnvelopeEncryptionService)` in `cloud-driver-plugin` (or the `setInstance(DatabaseProvider, EnvelopeEncryptionService, ConnectivityChecker)` overload, to supply a non-default `ConnectivityChecker`) - which assigns the shared instance and makes it retrievable through `CloudAPI.getInstance()`. Nothing may call `getInstance()` before that installation has happened; notably, an `Extension` subclass's constructor needs a registered `CloudAPI` and will fail if constructed too early.

```java
CloudAPI cloudAPI = DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);

cloudAPI.getDataFactory().register(customer);
CustomerRecord recovered = cloudAPI.getDataFactory().fetch("42", CustomerRecord.class);

// From anywhere else in the process, once installed:
CloudAPI.getInstance().getDataFactory().fetch("42", CustomerRecord.class);
```

A complete, runnable worked example (`DataFactory` and `FileFactory`, in one `main` method, against the JSON file-based `DatabaseProvider` - needs no external database) lives at `cloud-driver-plugin/src/test/java/de/lino/cloud/plugin/sample/CloudAPIUsageSample.java`. `ExtensionFactory` has its own sample (see [below](#the-extension-framework)); `EventFactory` and `RestFactory` currently have none.

## `DataFactory` - entity persistence

The entity-persistence contract, reached via `CloudAPI.getInstance().getDataFactory()`. Only `register`/`update`/`fetch`/`findById`/`delete`/`getEntities`/`clear`/`deleteSection` (single + batch where applicable) are abstract; every `*Async` variant is implemented once, generically, directly on `DataFactory` itself in terms of those abstract sync methods (via `MultiTaskingFactory`'s shared virtual-thread executor, wrapping checked exceptions in `CompletionException`).

```java
DataFactory dataFactory = CloudAPI.getInstance().getDataFactory();

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
| `clear(Class<T>)` / `deleteSection(Class<T>)` | none |
| every `*Async` variant | never throws synchronously; failures surface via the returned `CompletableFuture`'s `CompletionException` |

## `FileFactory` - file upload/download

Uploads, downloads, and deletes `StoredFile`s of any content type, reached via `CloudAPI.getInstance().getFileFactory()` - the file-persistence counterpart of `DataFactory`, built the same "abstract primitives + generic concrete `*Async`" shape. `StoredFile` is itself a `Serialized` domain entity, so files go through the exact same persistence/encryption stack as any other entity - there is no separate storage path.

Every download verifies two independent things before handing content back: the AES-256-GCM authentication tag over the stored ciphertext (`AuthenticationFailedException` on failure), and the plaintext checksum recorded on `StoredFile#checksum()` against the actually-decrypted bytes (`FileIntegrityException` on failure) - so a file that round-trips successfully is guaranteed byte-for-byte identical to what was uploaded.

```java
FileFactory fileFactory = CloudAPI.getInstance().getFileFactory();

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

`StoredFile` constructors take `fileId`, `fileName`, `content` (`byte[]`) and, optionally, an explicit `FileChecksum`/`createdAt`/`updatedAt` (for re-hydrating a previously-downloaded file). There is no `contentType` parameter - `contentType()` is always inferred from `fileName`'s extension via `Constraints.CONTENT_TYPES`, falling back to `StoredFile.DEFAULT_CONTENT_TYPE` (`application/octet-stream`) if unrecognized or absent, so a file is never rejected merely for having an unrecognized type. The constructor also attempts DEFLATE compression, keeping the compressed bytes only if strictly smaller (`isCompressed()` reports which happened); `checksum()` is always computed over the original, uncompressed plaintext.

`FileChecksum`/`FileMetadata` live under `de.lino.cloud.api.file.meta`; `FileIntegrityException` lives under `de.lino.cloud.api.file.exception`.

### Offline-safe file uploads

`FileFactory.upload` ultimately reaches whatever `DatabaseProvider` was configured - in production, typically a database reached over the network (e.g. PostgreSQL) - so an upload attempted with no internet connection would otherwise just fail. Two interfaces in this module make that failure recoverable instead of fatal; every concrete implementation, same as everywhere else in this module, lives in `cloud-driver-plugin`:

- **`ConnectivityChecker`** (`de.lino.cloud.api.security.connectivity`) - `isAvailable()`. Deliberately independent of the database driver: a database call failing does not by itself distinguish "no internet connection" from any other persistence failure. `CloudAPI.getConnectivityChecker()` exposes the instance a `CloudAPI` was installed with (`InternetConnectivityChecker` by default - probes a couple of well-known public DNS resolvers via a short-lived socket connection).
- **`PendingUploadCache`** (`de.lino.cloud.api.file.pending`) - `enqueue`/`remove`/`isEmpty`/`size`/`snapshot`, keyed by `StoredFile#fileId()` (re-enqueuing an id overwrites the previously queued content, the same insert-or-update semantics `upload` itself has).

`DefaultFileFactory` (`cloud-driver-plugin`, behind every `CloudAPI.getFileFactory()`) builds this in directly - no separate decorator class: before delegating to `DataFactory`, `upload` checks a `ConnectivityChecker`, and if connectivity is down, enqueues the file(s) into a `PendingUploadCache` instead of failing outright (checked proactively before the call, and again if the call itself fails). `PendingUploadScheduler` (`cloud-driver-plugin`) periodically retries everything queued there - only once the cache is non-empty and connectivity has returned - via `DataFactory#registerAsync` (not `FileFactory#uploadAsync` - `upload`'s own offline-deferral would otherwise let it silently re-queue a file the scheduler's success handling would then immediately remove).

```java
FileFactory fileFactory = cloudAPI.getFileFactory(); // already offline-safe - no wrapping needed
DataFactory dataFactory = cloudAPI.getDataFactory();
PendingUploadCache pendingUploadCache = ((DefaultFileFactory) fileFactory).getPendingUploadCache();

PendingUploadScheduler scheduler = new PendingUploadScheduler(dataFactory, pendingUploadCache, cloudAPI.getConnectivityChecker());
scheduler.start(Duration.ofSeconds(30)); // check the pending cache every 30 seconds

fileFactory.upload(report); // deferred into pendingUploadCache instead of thrown if offline right now
```

`getPendingUploadCache()`/`getConnectivityChecker()` on `DefaultFileFactory` are extra public methods beyond the `FileFactory` contract, exposing the instances `upload` checks against so a scheduler can be wired to the very same ones. See `cloud-driver-bootstrap/src/test/java/de/lino/cloud/bootstrap/CloudBootstrapSample.java` for the full wiring.

## The `security` package

Six sub-packages, each a thin layer around the previous one. Extension code normally only touches `CloudAPI` directly - this section is for understanding what happens underneath, or for using a piece standalone. This module supplies the interfaces/value objects below; every concrete implementation lives in `cloud-driver-plugin`.

### `security.crypto` - authenticated encryption

`AeadEncryptionService` is the interface (`AesGcmEncryptionService` is its AES-256-GCM implementation in `cloud-driver-plugin`). A fresh, random nonce is drawn for every call and never reused with the same key.

```java
EncryptedPayload encrypt(byte[] plaintext, SecretKey key, byte[] associatedData); // associatedData may be null
byte[] decrypt(EncryptedPayload payload, SecretKey key) throws AuthenticationFailedException;
```

`EncryptedPayload` (`algorithmId`, `nonce`, `ciphertext`, `associatedData`) defensively copies every array field on construction and on every accessor call, so neither the caller nor the record can mutate shared state after the fact.

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

## The extension framework

A separate concern from the encryption/persistence stack above: a lightweight framework for extensions, under `de.lino.cloud.api.extension` plus `ExtensionFactory`.

- **`Extension`** - abstract base class an extension subclasses. Its constructor only loads `ExtensionProperties` from an `extension.json` classpath resource and detects the build tool via `ProjectBuildDetection` - it does **not** register the instance. A subclass implements the lifecycle hooks (`onLoading`/`onRunning`/`onEnding`/`onException`) and never assembles its own properties.

```java
public final class DemoExtension extends Extension {
    @Override public void onLoading() { /* prepare resources */ }
    @Override public void onRunning(String[] args) { /* do work */ }
    @Override public void onEnding() { /* release resources */ }
    @Override public void onException(RuntimeException reason) { /* report/recover */ }
}

CloudAPI.getInstance().getExtensionFactory().register(new DemoExtension()); // registration is manual
```

- **`extension.json`** - required in every extension's `resources` folder:

```json
{
  "name": "my-extension",
  "version": "1.0.0",
  "authors": ["Jane Doe"],
  "dependencies": ["some-other-extension"]
}
```

`name`/`version` are required; `authors`/`dependencies` are optional and default to an empty list. `dependencies` names other extensions by their own `extension.json` `name`.

- **`ExtensionFactory`** (reached via `CloudAPI.getInstance().getExtensionFactory()`) - only `register`/`findByName`/`getExtensions` are abstract; every lifecycle-driving method is concrete on the abstract class itself, built generically on those three primitives:

```java
ExtensionFactory extensionFactory = CloudAPI.getInstance().getExtensionFactory();

extensionFactory.startAll(args); // topological order over getDependencies(); throws IllegalStateException on a cycle
extensionFactory.start(extension, args); // additionally requires every declared dependency registered + already RUNNING
extensionFactory.stopAll();
extensionFactory.stop(extension);

// *Async counterparts run on MultiTaskingFactory's shared virtual-thread executor
extensionFactory.startAllAsync(args);
```

A `RuntimeException` from `onLoading`/`onRunning` is caught, the extension's status is set to `ExtensionStatus.ERROR`, and it's routed to that extension's own `onException` rather than aborting the rest of `startAll`. `ExtensionProperties` tracks a `volatile` lifecycle `ExtensionStatus` (`LOADING`/`RUNNING`/`ENDING`/`ERROR`) that `ExtensionFactory` updates as it drives an extension through its lifecycle.

A worked example lives at `cloud-driver-plugin/src/test/java/de/lino/cloud/plugin/sample/ExtensionUsageSample.java` (with its `extension.json` under `cloud-driver-plugin/src/test/resources/`) - it lives in `cloud-driver-plugin`, not here, because `Extension`'s constructor needs a real `CloudAPI` implementation to exist.

## The event framework

A separate concern from persistence/encryption and from the extension framework above, under `de.lino.cloud.api.event` plus `EventFactory` - though it follows the same "abstract primitives + generic concrete `*Async` methods" shape. Unlike `Extension` (where the caller constructs the instance and only registration is deferred to a factory), an `Event` subclass is constructed *by* `EventFactory` itself, reflectively, via its no-arg constructor - so exactly one instance ever exists per registered class, a singleton reused for every future dispatch of that type.

- **`Event`** - abstract base class an event subclasses. One subclass models both an event type *and* its handling logic together:

```java
public final class OrderPlacedEvent extends Event {
    @Override
    public void handle(JsonDocument properties) {
        String orderId = properties.get("orderId", String.class);
        // ... react to the order ...
    }
}
```

- **`EventFactory`** (reached via `CloudAPI.getInstance().getEventFactory()`) - `registerEvent`/`unregisterEvent`/`callEvent`/`findEventByClass`/`getEvents` are abstract; every `*Async` variant, plus a batch `callEvent(Class<T>, JsonDocument[])` overload, is concrete on the abstract class itself:

```java
EventFactory eventFactory = CloudAPI.getInstance().getEventFactory();

eventFactory.registerEvent(OrderPlacedEvent.class); // constructs and stores the one instance for this type

JsonDocument payload = new JsonDocument().append("orderId", "42");
eventFactory.callEvent(OrderPlacedEvent.class, payload); // dispatches to the registered instance's handle()

Optional<OrderPlacedEvent> maybe = eventFactory.findEventByClass(OrderPlacedEvent.class); // empty() if not registered
eventFactory.unregisterEvent(OrderPlacedEvent.class); // throws IllegalStateException if not registered

// Batch: dispatch many payloads through the same event type concurrently
JsonDocument[] batch = { payload1, payload2, payload3 };
List<OrderPlacedEvent> results = eventFactory.callEvent(OrderPlacedEvent.class, batch);

// *Async counterparts run on MultiTaskingFactory's shared virtual-thread executor
eventFactory.callEventAsync(OrderPlacedEvent.class, payload);
```

`registerEvent` throws `IllegalStateException` if `OrderPlacedEvent` is already registered, or has no accessible no-arg constructor. `callEvent`/`unregisterEvent` throw `IllegalStateException` if nothing is registered under that class yet ("this must exist") - use `findEventByClass` first if that isn't guaranteed ("does this exist?"). `getEvents()` returns every currently registered event as a plain `Collection<Event>`.

## `RestFactory` - REST exposure over `DataFactory`

Mounts `Serialized` domain entities already reachable through `getDataFactory()` onto an HTTP API, via [Javalin](https://javalin.io) - reached via `CloudAPI.getInstance().getRestFactory()`. It carries no Javalin dependency of its own - purely `DataFactory`/`Serialized`/`MultiTaskingFactory` - which is exactly why it can live in `cloud-driver-api` (Javalin only ever appears as a dependency in `cloud-driver-plugin`, which supplies the one concrete implementation, `DefaultRestFactory`).

Only `register`/`fetch`/`update`/`delete`/`findByPath`/`getRegisteredPaths`/`start`/`stop` are abstract; every `*Async` variant is implemented once, generically, directly on `RestFactory` itself - the same "abstract primitives + generic concrete `*Async`" shape `DataFactory`/`FileFactory`/`ExtensionFactory`/`EventFactory` use, and deliberately the same primitive names `DataFactory` itself uses, since each one just wires the HTTP verb that carries out that operation: `register` -> `POST`, `fetch` -> `GET`, `update` -> `PUT`, `delete` -> `DELETE`.

```java
RestFactory restFactory = CloudAPI.getInstance().getRestFactory(); // unauthenticated by default - see below

restFactory.register("/notes", NoteRecord.class); // POST /notes
restFactory.fetch("/notes", NoteRecord.class);     // GET /notes/{id}, GET /notes
restFactory.update("/notes", NoteRecord.class);    // PUT /notes/{id}
restFactory.delete("/notes", NoteRecord.class);    // DELETE /notes/{id}

restFactory.start(8080); // assembles every registered route and starts listening - no further register/fetch/update/delete calls accepted after this

Optional<Class<? extends Serialized>> registeredAt = restFactory.findByPath("/notes");
Collection<String> paths = restFactory.getRegisteredPaths();

restFactory.stop();
```

Each verb is independent - calling only `fetch(path, type)` and `delete(path, type)` for a path, without `register`/`update`, exposes a read-and-remove-only resource; there is deliberately no "wire everything at once" convenience. **All four must be called before `start(port)`** - Javalin requires every route to be registered up front in the config block passed to `Javalin.create`, so a resource registered after the server is already listening would never get routes; implementations reject that with `IllegalStateException` instead of silently ignoring it, and reject a duplicate verb/path pair the same way.

`CloudAPI.getInstance().getRestFactory()` is **always unauthenticated** - `DefaultCloudAPI#setInstance` wires it to `DefaultRestFactory`'s single-argument constructor. A caller that needs the API-key gate constructs its own `DefaultRestFactory(dataFactory, apiKey)` directly instead:

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

- **`ApiKey`** (`de.lino.cloud.api.security.rest`, Lombok `@Getter`) - the `Serialized` entity backing that check: `id` (its primary key, so a deployment can keep more than one named key), `apiKeyRaw` (the plaintext key, exposed via `getApiKeyRaw()`), and `apiKeyHashHex` (its SHA-256 digest, used for verification). Both fields are persisted - like every `Serialized` entity, the whole record is envelope-encrypted (AES-256-GCM) before it ever reaches the database, so keeping the raw key is still safe at rest; it exists specifically so a caller can read it back once via `getApiKeyRaw()` right after construction (e.g. to hand it to whoever needs it) - `isValid` itself never reads `apiKeyRaw`, only `apiKeyHashHex`. `@ToString(exclude = "apiKeyRaw")` keeps the raw key out of `toString()`/logs; use `getApiKeyRaw()` explicitly when the value itself is needed. `isValid(String)` hashes a candidate once (a fast digest is appropriate here, unlike `PasswordHasher`'s deliberately slow KDF, since this key is high-entropy and machine-generated rather than user-chosen) and compares via `MessageDigest.isEqual` to avoid a timing side-channel. Persist it the same "look it up, create on a miss" way any other entity's lazy-initialization would, through `DataFactory` - it has no `DataFactory` dependency of its own.

No worked example currently exercises `RestFactory`.

## Utilities

- **`MultiTaskingFactory`** (`de.lino.cloud.api.utility.task`) - singleton wrapping one process-wide `ExecutorService` backed by virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`). Every `*Async` method across `DataFactory`, `FileFactory`, `ExtensionFactory`, `EventFactory`, and `RestFactory` is built on this. `runTaskInMainSafety(Runnable)` runs a task and then shuts the executor down, blocking until every submitted task finishes - call only from an extension's `main(String[])`, as its final action.
- **`Asserts`** (`de.lino.cloud.api.utility`) - shared null-validation helpers (`requireNonNull`, with a dedicated `CloudAPI` overload that fails with a message pointing at `DefaultCloudAPI.setInstance` instead of a bare `NullPointerException`) plus `runWallTimeTest(Runnable)` - runs a `Runnable` once and prints CPU time, memory delta, and wall-clock time to standard out; a quick spot-check, not a substitute for a real benchmarking harness.
- **`Constraints`** (`de.lino.cloud.api.utility`) - shared constants: `CONFIGURATION_PATH` (a `cloud-driver` subdirectory of the JVM's working directory), `EXTENSIONS_PATH` (a sibling `extensions` subdirectory `cloud-driver-plugin`'s jar scanner loads third-party `Extension`s from), `USER_DOWNLOADS_PATH`, and `CONTENT_TYPES`, the file-extension-to-MIME-type lookup table `StoredFile` infers `contentType()` from. Not exhaustive - extend it here if a new extension needs recognizing.
