# cloud-driver-plugin

This module supplies **every concrete implementation** behind `cloud-driver-api`'s contracts: `DefaultCloudDriver`, the five `Default*Factory` classes, `EntityDatabaseClient`, the AES-256-GCM/DEK-KEK envelope-encryption stack, `Hasher`, `Argon2idPasswordHasher`, `SecretRedactor`, the extension jar-loading classes, and the offline-safe file-upload machinery. `cloud-driver-api` defines *what* the system does (interfaces, abstract classes, value objects); this module defines *how* - nothing here is meant to be depended on directly by an extension author except through the `CloudDriver` facade those contracts expose.

The cryptographic design follows `../architecture/SECURITY_REQUIREMENTS.md` (bundled as a resource in `cloud-driver-api`). Section references in this module's own Javadoc (e.g. "section 4", "section 9") point back to that document.

## Coordinates

```xml
<dependency>
    <groupId>de.lino.cloud.plugin</groupId>
    <artifactId>cloud-driver-plugin</artifactId>
    <version>1.0.1</version>
</dependency>
```

Depends on `cloud-driver-api` and `cloud-driver-auth` (both `1.0.1`), `database-driver-api`/`database-driver-plugin` (`1.3.11`), `org.bouncycastle:bcprov-jdk18on` (Argon2id), `io.javalin:javalin` (`DefaultRestFactory`'s HTTP layer), `org.projectlombok:lombok` (`provided`), and `org.jetbrains:annotations`. See `pom.xml` for the full, commented list - most dependencies there carry a one-line note explaining *why* this module needs them, not just what they are.

## Module structure

```
de.lino.cloud.plugin
├── DefaultCloudDriver              the CloudDriver facade implementation - wires everything below together
├── connectivity
│   └── InternetConnectivityChecker probes public DNS resolvers to answer "is there a network connection right now?"
├── extension
│   ├── ExtensionFolderScanner      lists *.jar files in a folder, non-recursive
│   └── ExtensionJarLoader          gives each jar its own URLClassLoader, reflectively instantiates Extension subclasses
├── factory
│   ├── FactoryContainer            builds one mutually-consistent set of factories from one DatabaseProvider
│   ├── DefaultDataFactory          thin pass-through to EntityDatabaseClient
│   ├── DefaultFileFactory          thin pass-through to a DataFactory, plus checksum verification + offline deferral + metrics hooks
│   ├── DefaultExtensionFactory     stores registered Extensions in a LinkedHashMap (registration order)
│   ├── DefaultEventFactory         stores registered Events in a database-driver Cache (one singleton per class)
│   └── DefaultRestFactory          Javalin-backed RestFactory: API-key, JWT, or unauthenticated gating - by far this
│                                   module's largest class (~50 registered routes): the four generic (path,type)
│                                   verbs, plus the full hand-written surface for auth (login/register/reset-password/
│                                   change-email/2FA/refresh-tokens), admin (/admin/authUsers, /admin/audit-log),
│                                   /files and /folders (CRUD, trash, sharing, "empty trash bin"), /cloudUsers, and a
│                                   WebSocket live-update push route (this class also `implements LiveUpdatePublisher`)
├── file
│   ├── InMemoryPendingUploadCache  process-local queue of StoredFiles awaiting connectivity
│   ├── PendingUploadScheduler      periodically retries everything queued there
│   └── TrashPurgeScheduler         permanently purges trashed files/folders past a configured retention window -
│                                   built, but deliberately never started automatically anywhere in this repo (see
│                                   "Safety and security" below)
└── security
    ├── crypto.AesGcmEncryptionService     AES-256-GCM, fresh nonce per call
    ├── keys.AwsKmsKeyEncryptionService    the production KeyEncryptionService - wraps/unwraps DEKs via real AWS KMS,
    │                                      KEK material never leaves AWS's HSMs (top-level security.keys package)
    ├── keys.develop.*                     three non-production KeyEncryptionServices: InMemory/File/DatabaseKeyEncryptionService
    ├── keys.DataEncryptionKeyGenerator    generates fresh, random DEKs
    ├── envelope.EnvelopeEncryptionService  DEK-generate -> AEAD-encrypt -> KEK-wrap, in one call
    ├── hash.Hasher                         SHA-256/384/512 only (HashAlgorithm forbids MD5/SHA-1 by construction)
    ├── password.Argon2idPasswordHasher     OWASP-baseline Argon2id, PHC-encoded output
    ├── secrets.SecretRedactor              defense-in-depth text redaction before logging
    ├── entity.SecureEntityChannel          binds an entity's type+primary key into the AEAD associated data
    └── database.EntityDatabaseClient       the only class in this module that performs actual database I/O
```

Every package above except `factory`/`extension`/`file` is a **layer**, each one a thin wrapper around the one before it. An extension author normally never touches any of them directly - they reach persistence exclusively through `CloudDriver.getInstance().getDataFactory()` / `.getFileFactory()`. The layering exists so each concern (raw AEAD, key wrapping, envelope assembly, hashing, redaction, entity binding, actual I/O) can be tested, replaced, or reasoned about independently:

```
Serialized entity
   │  Serialized#toByteArray() (Gson, reflection)
   ▼
SecureEntityChannel.send(entity)
   │  binds "v1:<fully-qualified type name>:<primary key>" as AEAD associated data
   ▼
EnvelopeEncryptionService.encrypt(plaintext, associatedData)
   │  1. DataEncryptionKeyGenerator generates a fresh, random DEK
   │  2. AesGcmEncryptionService encrypts the plaintext under that DEK (fresh nonce)
   │  3. KeyEncryptionService.wrap(dek) wraps the DEK under the active KEK
   ▼
EnvelopeEncryptedPayload { schemaVersion, WrappedKey, EncryptedPayload }
   │  EncryptedEntityRecord.from(envelope) - every binary field base64-encoded
   ▼
JsonDocument { "data": EncryptedEntityRecord }  →  DatabaseEntry(primaryKey, document)
   │  EntityDatabaseClient.store() - insert(), falling back to update() on collision
   ▼
DatabaseSection (per entity type, name = Class#getSimpleName())
```

Reading reverses every step: `EntityDatabaseClient.retrieve()` (cache miss) pulls the `DatabaseEntry`, extracts the `EncryptedEntityRecord`, reconstructs the `EnvelopeEncryptedPayload`, and calls `SecureEntityChannel.receive()`, which unwraps the DEK, decrypts, verifies the AES-256-GCM authentication tag, checks the associated data still names the expected type + primary key (`IllegalArgumentException` if it doesn't - a payload can't be silently swapped for a different record), and finally deserializes the plaintext back into `T` via `Serialized.fromByteArray`.

## Data handling and storage shape

- **Section-per-type routing.** `EntityDatabaseClient` never talks to one big table - each entity `Class` gets its own `DatabaseSection`, named after `Class#getSimpleName()`, resolved lazily via `sections.computeIfAbsent` and cached for the process's lifetime. This is why every entity-scoped method (`retrieve`, `findById`, `delete`, `deleteAll`, `clear`, `deleteSection`, `reload`) takes an explicit `Class<T> type` argument - it's needed to route to the right section, not just to deserialize the JSON back into the right shape.
- **On-disk/on-the-wire shape.** Nothing plaintext ever reaches the database. What's actually stored under a `DatabaseEntry`'s document is `{"data": EncryptedEntityRecord}`, where `EncryptedEntityRecord` is `EnvelopeEncryptedPayload` with every binary field (nonce, ciphertext, wrapped-key material, associated data) base64-encoded into plain JSON strings.
- **Cross-process staleness and `reload(type)`.** Every `DatabaseSection` implementation `database-driver-plugin` ships mirrors its entries in process-local memory, populated once when that section object is first constructed and kept in sync only by writes made through that *same instance* - reads never touch the database. So a row written by a different process against the same underlying database (e.g. a second server instance, or a one-off script) is invisible to an already-running process's `findById`/`fetch`/`getEntities` **indefinitely**, not just until the next cache TTL expiry - until something calls `EntityDatabaseClient#reload(type)` (re-reads the section from the database and invalidates that type's decrypted-entity cache) or the process restarts. `DataFactory#reload`/`reloadAsync` expose this the same "abstract primitive + generic concrete `*Async`" way every other verb does. `cloud-driver-extensions-watcher`'s `CloudWatcherExtension` calls this before every `findById` triggered by a Postgres change notification, precisely because a notification is the signal that some process - this one or not - just wrote a row this process's own section mirror may not know about yet.
- **Files are entities, not a separate storage path.** `StoredFile` (`cloud-driver-api`) is itself a `Serialized` entity, so `DefaultFileFactory` is a thin pass-through to a `DataFactory` (`download`/`findById`/etc. are literally `dataFactory.fetch(fileId, StoredFile.class)` under the hood) plus a checksum check layered on top - see "Safety and integrity" below.

## Performance characteristics

- **Per-type caching, bounded by default.** `EntityDatabaseClient` gives each entity type its own read-through, write-through `Cache<String, T>` (`database-driver-api`'s own `Cache`, the same stampede-protected-loader contract `DefaultEventFactory` and `InMemoryPendingUploadCache` build on), default-bounded to 30 seconds TTL / 1,000 entries per type since it holds **decrypted plaintext** in memory - tune via `EntityDatabaseClient`'s second constructor if that default is wrong for a deployment. `store()`/`update()` write through directly (the plaintext is already in hand at that point, so there's no reason to wait for the next read to decrypt it back out of what was just written).
- **Concurrent, per-type resolution.** Both `sections` (`Map<Class<?>, DatabaseSection>`) and `caches` (`Map<Class<?>, Cache<String, ?>>`) are `ConcurrentHashMap`s, resolved lazily via `computeIfAbsent` - two different entity types never contend on the same lock, and creating `TypeA`'s section/cache never blocks a concurrent read of `TypeB`'s.
- **Virtual-thread dispatch for everything batch or async.** Every `*Async` method across `DataFactory`/`FileFactory`/`ExtensionFactory`/`EventFactory`/`RestFactory` (defined once, generically, on the abstract class in `cloud-driver-api`) and every batch operation in this module (`EntityDatabaseClient#storeAll`/`retrieveAll`/`updateAll`/`deleteAll`, `DefaultFileFactory#verifyAll`) is dispatched on `MultiTaskingFactory`'s shared virtual-thread executor rather than looping sequentially - a batch of 1,000 uploads pays for one round-trip's worth of wall-clock latency, not 1,000. Batch operations throw the *first* failure encountered once every item has already been attempted, not on the first failure, since every item is already in flight concurrently by the time any one of them fails.
- **REST handlers never block a Jetty worker thread.** `DefaultRestFactory`'s route handlers all call into `DataFactory`/`CloudUserService` through `Context#future`, wrapping the corresponding `*Async` call - the actual encryption/database I/O always runs on a virtual thread, never the Jetty request thread. `handleLogin`/`handleRegister`/`handleConfirmRegistration`/`handleUploadFile`/`handleListFiles`/`handleDeleteFile` follow the same pattern for the fixed `/auth/login`/`/auth/register`/`/auth/register/confirm`/`/files` routes. Registration is two calls, not one: `handleRegister` starts it (`AuthService#register` e-mails a verification code, 202 response, no JWT yet), `handleConfirmRegistration` completes it (`AuthService#confirmRegistration`, 201 response with a JWT).
- **Route registration order is load-bearing, not just organizational.** Javalin's router (`io.javalin.router.matcher.PathMatcher`) does a plain linear first-match scan over routes of a given HTTP method/segment-count, in registration order - there is **no** built-in "a static path segment beats a `{param}` one" precedence. A static-segment route (e.g. `GET /files/trash`, `GET /files/shared-with-me`) sharing a method and segment count with a `{param}` route (`GET /files/{id}`) **must** be registered before it in `start()`, or the param route silently swallows every request to it. This was the root cause of a real, confirmed production bug (`GET /files/shared-with-me` 404ing for months because it was registered after `GET /files/{id}`) - see `FILES_SHARED_WITH_ME_PATH`'s own Javadoc in `DefaultRestFactory` for the full incident writeup before adding a new static route alongside an existing `{id}` one.
- **`DefaultFileFactory#upload` pushes event-style metrics** (`recordUploadSuccess`/`recordUploadFailure`/`recordUploadQueued`) through `CloudDriver.getInstance().getServiceContainer().getMetricsRecorder()` if `cloud-driver-extensions-metrics` has published one - resolved lazily per call, wrapped in a try/catch so a missing or broken metrics sink can never affect a real upload.
- **`InternetConnectivityChecker` is a short, bounded probe, not a database round-trip.** It opens a plain TCP socket to a public DNS resolver (`1.1.1.1:53` / `8.8.8.8:53`, 2-second timeout each) rather than asking the configured database whether it's reachable - a database failure and a network outage are different failure modes, and conflating them would make `DefaultFileFactory#upload`'s offline-deferral logic fire (or not fire) for the wrong reason.

## Safety and security

- **AES-256-GCM everywhere.** `AesGcmEncryptionService` draws a fresh, cryptographically random nonce for every single `encrypt` call (`SecureRandom`, never reused with the same key) and verifies the GCM authentication tag on every `decrypt`, throwing `AuthenticationFailedException` (wrapping `AEADBadTagException`) rather than returning corrupted/tampered plaintext.
- **DEK/KEK envelope encryption with rotation.** `EnvelopeEncryptionService` generates a fresh, random data-encryption key (DEK) per payload via `DataEncryptionKeyGenerator`, encrypts with it, then wraps the DEK under whichever key-encryption key (KEK) `KeyEncryptionService#activeKeyEncryptionKeyId()` currently reports active. `KeyEncryptionService#rotate()` activates a new KEK for *future* wraps without invalidating the ability to unwrap data wrapped under an earlier KEK version - each `WrappedKey` carries the id of the specific KEK version that wrapped it. Four implementations exist: `security.keys.develop.InMemoryKeyEncryptionService`/`FileKeyEncryptionService`/`DatabaseKeyEncryptionService`, all explicitly documented as **not for production** (KEK material never leaves this process/one file/one database row), and `security.keys.AwsKmsKeyEncryptionService` - the **production** implementation, delegating `wrap`/`unwrap` directly to AWS KMS's own `Encrypt`/`Decrypt` API so KEK material never leaves AWS's HSMs at all; `rotate()` provisions a genuinely new symmetric CMK via a real `CreateKey` call. `CloudBootstrap.initiateCloudDriver()` wires this one in by default as of 2026-09-02.
- **DEK material is zeroed immediately after use.** `DataEncryptionKey#destroy()` is called in a `finally` block by `EnvelopeEncryptionService` as soon as an `encrypt`/`decrypt` call completes, so the raw key material sits in memory for the shortest possible window.
- **Type/identity binding via associated data.** `SecureEntityChannel` binds `"v1:<fully-qualified type name>:<primary key>"` into the AEAD's authenticated-but-not-encrypted associated data on every `send`, and `receive` rejects (`IllegalArgumentException`) any envelope whose associated data doesn't start with the *expected* type's own prefix, before ever attempting to decrypt. This closes a real substitution attack: without it, an attacker (or a bug) that swapped one entity's ciphertext for another's of the same size would decrypt "successfully" under the same key, just as the wrong record.
- **Argon2id for passwords, never a plain hash.** `Argon2idPasswordHasher` follows the OWASP baseline (19 MiB memory, 2 iterations, 1 degree of parallelism) and PHC-encodes its output (`$argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>`) so the cost parameters travel with the hash itself - a future deployment can raise the defaults without invalidating hashes issued under the old ones. `verify` uses `MessageDigest.isEqual` for a constant-time comparison, avoiding a timing side-channel.
- **Deliberately restricted general-purpose hashing.** `Hasher` only exposes `HashAlgorithm.SHA_256`/`SHA_384`/`SHA_512` - there is no code path for MD5 or SHA-1 anywhere in this class, by construction of the enum it accepts, not by convention.
- **Defense-in-depth secret redaction.** `SecretRedactor#redact` strips recognizable secret material (bearer tokens, `Authorization` headers, common secret query-string parameters) from arbitrary text before it's logged; `redactValue` additionally strips a *known* literal secret value. Both are a safety net for accidental logging, never a substitute for not logging a secret in the first place.
- **Double integrity verification on every file read.** `DefaultFileFactory#download`/`findById`/`getEntities` each check two independent things after fetching a `StoredFile`: the AES-256-GCM authentication tag (already checked by the time `DataFactory#fetch` returns, via `AuthenticationFailedException`) and, on top of that, `StoredFile#verifyChecksum()` - the plaintext checksum recorded at upload time, checked against what was actually decrypted, throwing `FileIntegrityException` on any mismatch. Batch reads (`download(String[])`, `getEntities()`) run this check concurrently via `MultiTaskingFactory`, throwing the first `FileIntegrityException` encountered once every file has been checked.
- **`TrashPurgeScheduler` is built but deliberately never started automatically anywhere in this repo.** It permanently purges a trashed file/folder (content, ownership row, and outstanding share grants - see `cloud-driver-auth`'s README) past a configured retention window (`configuration.json`'s `"trash-retention-days"`, default 30). Getting that window wrong (too short) causes real, permanent, silent data loss the moment it starts ticking - unlike almost every other bug in this codebase, this one can't be caught and fixed after the fact. An operator wires it in explicitly (e.g. from `CloudBootstrap`) only once a retention window has been deliberately chosen.
- **API-key and JWT gating are mutually exclusive per `DefaultRestFactory` instance.** The unauthenticated single-argument constructor is documented as local-development-only; the `ApiKey`-gated constructor checks `X-API-Key` via constant-time digest comparison (`ApiKey#isValid`, `cloud-driver-api`); the `AuthService`-gated constructor checks a per-user `Authorization: Bearer <jwt>` header and additionally scopes any `Owned`-implementing entity type to its authenticated caller (spoofed `ownerId` in a request body is overwritten server-side before persisting; a record belonging to someone else 404s rather than 403s, so a caller can't even confirm it exists).

## Scalability

- **Batch operations are concurrent, not sequential loops.** See "Performance characteristics" above - every batch verb across `EntityDatabaseClient`, `DefaultFileFactory`, and the generic `*Async` methods on every factory contract fan out onto `MultiTaskingFactory`'s virtual-thread executor.
- **Per-type isolation limits blast radius.** Because sections and caches are resolved and stored per entity `Class`, a hot or misbehaving entity type (e.g. one under heavy write load, or one whose cache TTL is wrong for its access pattern) can't starve another type's throughput - they don't share a lock, a section, or a cache instance. `deleteSection(type)`/`clear(type)` are similarly scoped to one type at a time.
- **Offline-safe uploads decouple client availability from server availability.** `DefaultFileFactory#upload` checks `ConnectivityChecker#isAvailable()` before attempting a database call, and defers into `PendingUploadCache` instead of failing outright when connectivity is down (or drops mid-call - a caught `DatabaseClientException` re-checks connectivity before deciding whether to rethrow or defer). `PendingUploadScheduler` then drains that queue on its own daemon-thread ticker once connectivity returns, retrying every queued file *concurrently* via `DataFactory#registerAsync` (deliberately not `FileFactory#uploadAsync`, which would just re-defer a still-failing file into the very cache being drained). An `AtomicBoolean` guard (`PendingUploadScheduler#flushing`) ensures a slow flush is never joined by a second, concurrent one on the next tick.
- **Extension jar loading scales to "drop a jar in a folder."** `ExtensionFolderScanner`/`ExtensionJarLoader` let third-party extensions be added to a running deployment without a compile-time dependency on this module - each jar gets its own `URLClassLoader` (parent-first class loading so shared types like `Extension` resolve identically on both sides of the boundary; child-first *resource* loading so the host's own `extension.json` never shadows a scanned jar's own).

## Layered architecture reference

| Package | Responsibility | Key type(s) |
|---|---|---|
| `security.crypto` | Raw authenticated encryption | `AesGcmEncryptionService` |
| `security.keys` | DEK generation, KEK wrap/unwrap/rotate | `DataEncryptionKeyGenerator`, `AwsKmsKeyEncryptionService` (production), `develop.InMemory`/`File`/`DatabaseKeyEncryptionService` (not for production) |
| `security.envelope` | Ties crypto + keys into one envelope | `EnvelopeEncryptionService` |
| `security.hash` | General-purpose hashing (never passwords) | `Hasher` |
| `security.password` | Password hashing | `Argon2idPasswordHasher` |
| `security.secrets` | Text redaction before logging | `SecretRedactor` |
| `security.entity` | Binds entity type/primary key into AEAD associated data | `SecureEntityChannel` |
| `security.database` | The one class that actually does database I/O | `EntityDatabaseClient` |
| `extension` | Loads third-party `Extension`s from jars on disk | `ExtensionFolderScanner`, `ExtensionJarLoader` |
| `file` | Offline-safe pending-upload queue + drain scheduler | `InMemoryPendingUploadCache`, `PendingUploadScheduler` |
| `connectivity` | "Is there a network connection right now?" | `InternetConnectivityChecker` |
| `factory` | `CloudDriver` facet implementations | `FactoryContainer`, `Default*Factory` |

Extension code normally only ever touches the top of this stack (`CloudDriver.getInstance().getDataFactory()`/`.getFileFactory()`); everything else here exists so that call can be made safely and efficiently.

## API usage

This module's own entry point is `DefaultCloudDriver.setInstance(...)` - everything else
(`DataFactory`, `FileFactory`, ...) is then reached back through `cloud-driver-api`'s `CloudDriver`
facade, never through a `de.lino.cloud.plugin` type directly:

```java
import de.lino.cloud.plugin.DefaultCloudDriver;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.Credentials;

// A throwaway local JSON-file-based DatabaseProvider - no external database needed for this sample.
DatabaseProvider databaseProvider = DatabaseProvider.create(DatabaseType.JSON, credentials);
        EnvelopeEncryptionService envelopeEncryptionService =
                new EnvelopeEncryptionService(new InMemoryKeyEncryptionService()); // KEK lost on restart - dev only

        CloudDriver cloudDriver = DefaultCloudDriver.setInstance(databaseProvider, envelopeEncryptionService);

cloudDriver.

        getFactoryContainer().

        getDataFactory().

        register(new CustomerRecord(42, "DE00..."));
        CustomerRecord fetched = cloudDriver.getFactoryContainer().getDataFactory()
                .fetch("42", CustomerRecord.class); // decrypted transparently on the way back out

cloudDriver.

        shutdown(); // idempotent, tears down every facet this instance owns
```

## Worked examples (`src/test`)

Runnable `main`-method samples, not `mvn test` targets (see the root `CLAUDE.md`'s "Build" section for this repo-wide convention):

- `sample/RestFactorySample.java` - an unauthenticated `DefaultRestFactory` mounting a dummy `Note` entity on all four verbs, against a throwaway local JSON-file `DatabaseProvider`.
- `sample/RestFactoryCloudUserSample.java` - the JWT-gated `DefaultRestFactory(DataFactory, AuthService, CloudUserService)` constructor: a demo account is registered at startup, and every `/files` request after login is scoped to that one user.
- `sample/Note.java` - the dummy `Serialized` entity `RestFactorySample` mounts; has no meaning outside that sample.

Run any of them directly from an IDE, or via `mvn -pl cloud-driver-plugin -am test-compile` followed by running the class on the built classpath.

## Javadoc conventions

This module follows the Google Java Style Guide's Javadoc shape: a short summary fragment ending in a period, a blank line, then `@param` (one per parameter, describing what the value *means*, not restating its name), `@return` (if non-void), and `@throws` (one per checked or otherwise noteworthy exception, explaining *when* it's thrown) - in that order. A few patterns recur throughout this module worth knowing before adding to it:

- A thin pass-through method (e.g. every method on `DefaultDataFactory`/`DefaultFileFactory`) is documented with a single `{@code /** Delegates to {@link Target#method}. */}` line rather than repeating the full contract already documented on the abstract method it implements - repeating every `@param`/`@throws` on every trivial override would be pure duplication and a drift risk.
- Concrete methods in `factory`/`extension` use Lombok's `@NonNull` (runtime-checked, generates the null check) rather than `@NotNull` + a manual `Asserts.requireNonNull` call; abstract method parameters elsewhere still use `@NotNull` (`org.jetbrains.annotations`, documentation-only) since Lombok's `@NonNull` has no effect without a method body to inject the check into.
- Every hand-written null check goes through `Asserts.requireNonNull(value, "@ClassName.methodName: message")` (`cloud-driver-api`) rather than a bare `Objects.requireNonNull`, so a `NullPointerException` message always names both the failing class/method and the failing argument.
- Instance fields are always accessed as `this.field`, never a bare `field`, throughout this module's source.
