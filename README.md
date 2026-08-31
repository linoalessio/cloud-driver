# cloud-driver

A Maven multi-module Java 21 library that envelope-encrypts application entities (AES-256-GCM,
KMS/HSM-style key wrapping with rotation) before persisting them through the `de.lino.database`
driver stack (an external artifact group, pinned to `1.3.11`), plus a lightweight extension
framework, an interactive `jline` terminal, a JWT/API-key-authenticated REST API, and the
operator-facing tooling (backup, Postgres change notifications, deployment scripts) built on top
of it.

Cryptographic choices follow `architecture/SECURITY_REQUIREMENTS.md` (bundled as a resource in
`cloud-driver-api`) - section references in this codebase's Javadoc (e.g. "section 9") point back
to that document.

This file is the map. Each module's own `README.md` (linked below) is the actual reference for
that module's code, and goes into far more depth than is repeated here.

## What this actually does, in one flow

```
Serialized entity  →  SecureEntityChannel  →  EnvelopeEncryptionService  →  EncryptedEntityRecord
                       (binds type + key)      (DEK generate → AEAD        (base64 JSON,
                                                 encrypt → KEK wrap)         no plaintext)
                                                       │
                                                       ▼
                                           EntityDatabaseClient.store()
                                     (insert, falling back to update on collision)
                                                       │
                                                       ▼
                                DatabaseSection (per entity type, name = Class#getSimpleName())
```

Reading reverses every step, additionally verifying the AES-256-GCM authentication tag and (for
files) a plaintext checksum, before the caller ever sees decrypted data. Nothing plaintext is ever
written to the database. See `cloud-driver-plugin/README.md`'s "Module structure" section for the
full diagram with class names at each stage.

## Module map

| Module | What it is | README |
|---|---|---|
| `cloud-driver-api` | Contracts only - interfaces, abstract classes, value objects, exceptions. No concrete implementations except a handful of narrow entities with no `cloud-driver-plugin` dependency to place them in instead (`StoredFile`, `ApiKey`, `AuthUser`, the `security.*` value objects). Also hosts the self-contained `jline`-based terminal engine. | [`cloud-driver-api/README.md`](cloud-driver-api/README.md) |
| `cloud-driver-auth` | The framework-agnostic e-mail + password → JWT authentication engine (`AuthService`, `JjwtSigner`, `SmtpEmailSender`/`LoggingEmailSender`, `PendingRegistration`, `CloudUser`/`CloudUserService`, `StoredFileOwnership`). No Javalin dependency of its own. | [`cloud-driver-auth/README.md`](cloud-driver-auth/README.md) |
| `cloud-driver-plugin` | Every concrete implementation of `cloud-driver-api`'s contracts: `DefaultCloudDriver`, the five `Default*Factory` classes, `EntityDatabaseClient`, the AES-256-GCM/DEK-KEK stack, `Argon2idPasswordHasher`, the extension jar-loading classes, offline-safe upload machinery. | [`cloud-driver-plugin/README.md`](cloud-driver-plugin/README.md) |
| `cloud-driver-bootstrap` | The runnable entry point (shaded jar via `maven-shade-plugin`) that wires a real Postgres-backed `CloudDriver` together and starts every subsystem a deployment needs. New accounts are created exclusively through `POST /auth/register`/`POST /auth/register/confirm` (see `cloud-driver-extensions-rest`) - there is no operator-run account-creation CLI. | [`cloud-driver-bootstrap/README.md`](cloud-driver-bootstrap/README.md) |
| `cloud-driver-extensions` | Parent aggregator (no source of its own) for the concrete `Extension`s built on the `Extension`/`ExtensionFactory` framework. | [`cloud-driver-extensions/README.md`](cloud-driver-extensions/README.md) |
| ├─ `cloud-driver-extensions-watcher` | Postgres `LISTEN`/`NOTIFY` change notification - push, not poll, cross-process awareness of new `StoredFile` writes. | [README](cloud-driver-extensions/cloud-driver-extensions-watcher/README.md) |
| ├─ `cloud-driver-extensions-terminal` | Registers the real `Command` catalog (`exit`, `help`, `extensions`, `about`, `dispatch`, ...) on the terminal engine and starts its reading loop. | [README](cloud-driver-extensions/cloud-driver-extensions-terminal/README.md) |
| ├─ `cloud-driver-extensions-backup` | Keyset-paginated, streaming Postgres backup job, purpose-built for 150-200GB-class tables. | [README](cloud-driver-extensions/cloud-driver-extensions-backup/README.md) |
| └─ `cloud-driver-extensions-rest` | Stands up the JWT-authenticated `RestFactory` (login, e-mail-verified self-registration, per-user `CloudUser` data, per-user file upload/list/delete) over Javalin. | [README](cloud-driver-extensions/cloud-driver-extensions-rest/README.md) |
| `cloud-driver-platforms` | Parent aggregator (no source of its own) for the **client-side** modules - code that talks to a running server purely over its REST API. Sibling to `cloud-driver-extensions`, not a submodule of it; sits entirely outside the `api`/`auth`/`plugin`/`bootstrap` server-side dependency chain. | [`cloud-driver-platforms/README.md`](cloud-driver-platforms/README.md) |
| ├─ `cloud-driver-platforms-rest` | Dependency-free (of any other module in this repo) REST API client library: `ApiClient`, `SessionManager`, `Dtos`, OS-specific `TokenStore` implementations. | [README](cloud-driver-platforms/cloud-driver-platforms-rest/README.md) |
| └─ `cloud-driver-platforms-desktop` | JavaFX desktop client built on top of `cloud-driver-platforms-rest` - register, login, list/upload/delete files. | [README](cloud-driver-platforms/cloud-driver-platforms-desktop/README.md) |

Dependency direction is one-way: `api` ← `auth` ← `plugin` ← `bootstrap`/`extensions-*`
(`bootstrap` and `plugin` both also depend on `auth` directly, not just transitively). Never add a
dependency from `cloud-driver-api` back onto `cloud-driver-auth`/`cloud-driver-plugin`, or from
`cloud-driver-auth` onto `cloud-driver-plugin`. `cloud-driver-platforms-rest`/`cloud-driver-platforms-desktop`
sit outside this chain entirely - neither depends on any server-side module, only on their own sibling.

`cloud-driver-extensions-web` is **not** a current module - the directory still exists on disk
with empty `src/` trees but no `pom.xml` and no entry in the aggregator's `<modules>`, so Maven
never touches it. See the extensions README for the full detail.

## Build

```
mvn clean install                              # build every module, in dependency order
mvn -pl cloud-driver-plugin -am compile         # build one module + its dependencies
mvn -pl cloud-driver-bootstrap -am package      # produce the runnable, shaded jar
java -jar cloud-driver-bootstrap/target/cloud-driver-bootstrap-1.0.1.jar
```

No Maven wrapper - use a locally installed Maven. Every child `pom.xml` inherits
`maven.compiler.source`/`target` (21) and `project.build.sourceEncoding` (UTF-8) from the root
`pom.xml`; each module still declares its own `<packaging>` explicitly (`pom` for the two
aggregators, `jar` for the rest).

No test framework (JUnit, etc.) is wired into any `pom.xml` anywhere in this repo. Java files
under `src/test` in several modules are runnable worked examples with a `main` method - a
repo-wide convention, not `mvn test` targets.

## Local secrets

Two gitignored files under a `cloud-driver/` directory (`Constraints.CONFIGURATION_PATH`, a
subdirectory of the JVM's working directory - not to be confused with this repository's own root
directory, which happens to share the name) hold everything environment-specific:
`postgres-database.json` (live database `Credentials`) and `configuration.json`
(`"jwt-signing-key"`, `"rest-server-bind-host"`, `"rest-server-port"`, `"smtp-host"`/`"smtp-port"`/
`"smtp-username"`/`"smtp-password"`/`"smtp-from-address"`, ...). Never commit real values found
there. `cloud-driver-bootstrap`'s README documents exactly what reads from each file.

## Project structure and layering

The codebase follows one rule almost everywhere: **`cloud-driver-api` defines the contract,
`cloud-driver-plugin`/`cloud-driver-auth` supply the implementation.** Concretely:

- **Facade over facets, not a monolith.** `CloudDriver` is a thin facade exposing an
  `IFactoryContainer` (`getDataFactory()`/`getFileFactory()`/`getExtensionFactory()`/
  `getEventFactory()`/`getRestFactory()`), an `IServiceContainer` (`getCloudUserService()`/
  `getAuthService()`), `getConnectivityChecker()`, `getTerminal()`, and `getConfiguration()`. It
  holds no persistence or lifecycle logic of its own.
- **Abstract primitives + generic concrete `*Async`.** `DataFactory`, `FileFactory`,
  `ExtensionFactory`, `EventFactory`, and `RestFactory` all share one shape: a handful of abstract
  synchronous primitives a concrete class must implement, plus every `*Async` variant implemented
  once, generically, on the abstract class itself in terms of those primitives (dispatched via
  `MultiTaskingFactory`'s shared virtual-thread executor). Adding a new facade means implementing
  the primitives; the async surface comes for free.
- **Layered security stack.** `security.crypto` (raw AEAD) → `security.keys` (DEK/KEK split,
  wrap/unwrap/rotate) → `security.envelope` (ties both together) → `security.hash`/`.password` →
  `security.secrets` (redaction) → `security.entity` (binds entity identity into the AEAD
  associated data) → `security.database` (`EntityDatabaseClient`, the only class that actually
  touches the database). Each layer is independently testable and replaceable.
- **Extensions are host-agnostic plugins, not compiled-in features.** A jar dropped into
  `Constraints.EXTENSIONS_PATH` is picked up purely by declaring a concrete `Extension` subclass
  and shipping an `extension.json` - no compile-time dependency on `cloud-driver-bootstrap`
  required. See `cloud-driver-extensions/README.md` for the full mechanism.

## Performance

- **Virtual threads everywhere async.** Every `*Async` method, and every batch operation
  (`EntityDatabaseClient#storeAll`/`retrieveAll`/`deleteAll`, `DefaultFileFactory#verifyAll`,
  batch `EventFactory#dispatch`), is dispatched on `MultiTaskingFactory`'s shared
  `Executors.newVirtualThreadPerTaskExecutor()` rather than looping sequentially - a batch of 1,000
  operations pays for roughly one round-trip's worth of wall-clock latency, not 1,000.
- **Per-type isolation.** `EntityDatabaseClient` resolves a `DatabaseSection` and a decrypted-entity
  `Cache` per entity type, both stored in `ConcurrentHashMap`s and resolved lazily - two entity
  types never contend on the same lock, and a hot or misbehaving type can't starve another's
  throughput.
- **Bounded, TTL'd decryption cache.** Each type's `Cache` defaults to 30s TTL / 1,000 entries
  since it holds *decrypted plaintext* in memory - tunable via `EntityDatabaseClient`'s second
  constructor.
- **REST handlers never block a request thread.** `DefaultRestFactory` wires every route through
  Javalin's `Context#future`, so the encryption/database I/O (or Argon2id's deliberately slow
  hashing, for `/auth/login`) always runs on a virtual thread, never a Jetty worker thread.
- **A full findings pass on this exact topic exists separately** - a companion
  performance/concurrency/async audit was produced alongside this documentation and delivered to
  the repository owner directly (not committed here, since none of it has been applied yet); each
  module's own README also calls out that module's specific findings inline where relevant.

## Data handling

- **Nothing plaintext ever reaches the database.** Every `Serialized` entity is routed through
  `SecureEntityChannel` → `EnvelopeEncryptionService` before `EntityDatabaseClient` ever calls
  `DatabaseProvider`; what's actually stored is `EncryptedEntityRecord` - every binary field
  (nonce, ciphertext, wrapped-key material, associated data) base64-encoded into plain JSON.
- **Files are entities, not a separate storage path.** `StoredFile` is itself a `Serialized`
  entity - `FileFactory` is a thin pass-through to `DataFactory` plus a double integrity check on
  read (AES-256-GCM auth tag, then a plaintext checksum recorded at upload time).
- **Cross-process staleness is explicit, not silent.** `DatabaseSection` implementations mirror
  their entries in process-local memory once resolved and never re-read the database on their own
  - a row written by a different process is invisible to an already-running process indefinitely,
  not just past a cache TTL, until `DataFactory#reload(type)` is called or the process restarts.
  `cloud-driver-extensions-watcher` exists specifically to call `reload` automatically in reaction
  to a Postgres `NOTIFY`.
- **Offline uploads are deferred, not dropped.** `DefaultFileFactory#upload` checks a
  `ConnectivityChecker` before attempting a database call and enqueues into a `PendingUploadCache`
  instead of failing outright when offline; `PendingUploadScheduler` drains that queue once
  connectivity returns.

## Safety and security

- **AES-256-GCM, fresh nonce per call, authentication tag always verified.** A failed tag check
  throws `AuthenticationFailedException` rather than returning corrupted/tampered plaintext.
- **DEK/KEK envelope encryption with rotation.** A fresh, random data-encryption key is generated
  per payload and wrapped under the currently active key-encryption key; rotation activates a new
  KEK for future wraps without breaking the ability to unwrap data wrapped under an earlier
  version. All three `KeyEncryptionService` implementations shipped in this repo
  (`InMemory`/`File`/`Database`) are explicitly documented as **not for production** - swap in a
  real KMS/HSM client for anything that leaves a developer machine.
- **Type/identity binding closes a substitution attack.** `SecureEntityChannel` binds an entity's
  fully-qualified type name and primary key into the AEAD's associated data, and rejects any
  envelope whose associated data doesn't match the expected type before ever attempting to
  decrypt - so a swapped ciphertext of the same size can't silently decrypt as the wrong record.
- **Argon2id for passwords, restricted general-purpose hashing.** `Argon2idPasswordHasher` follows
  the OWASP baseline and PHC-encodes its cost parameters into the hash itself. `HashAlgorithm` only
  offers SHA-256/384/512 - MD5 and SHA-1 are not representable by the type system, by design.
  `login` never distinguishes "no such account" from "wrong password" in message or exception
  type, to prevent account enumeration.
- **Two independent, mutually exclusive REST auth mechanisms.** A static `ApiKey`/`X-API-Key`
  check (constant-time comparison against a SHA-256 digest, never the raw key) for
  machine-to-machine use, or a per-user JWT (`cloud-driver-auth`, HMAC-SHA256, 12-hour expiry, no
  refresh token) for end-user clients - never combined on one `DefaultRestFactory` instance. A JWT-
  authenticated instance additionally scopes any `Owned`-implementing entity type to its caller:
  writes have their `ownerId` overwritten server-side (spoofing is a no-op), and reads 404 rather
  than 403 on a record the caller doesn't own, to avoid confirming its existence at all.
- **Self-registration is opt-in and e-mail-verified, not silently open.** `POST /auth/register`
  (mounted by `cloud-driver-extensions-rest`'s JWT-gated `DefaultRestFactory`) only e-mails a
  time-limited (10-minute) numeric verification code via `AuthService#register`; the `AuthUser`
  account itself is created only once `POST /auth/register/confirm` supplies the matching code.
  There is no operator-run account-creation CLI anymore - the earlier `CreateUserCli`/`LoginSample`
  tools were deleted once this two-step HTTP flow covered the same job.

## Scalability

- **Batch and async operations fan out concurrently**, not sequentially, throughout the stack -
  see "Performance" above.
- **Extension isolation limits blast radius.** Each extension runs its lifecycle on its own
  dedicated, non-daemon thread; a `RuntimeException` from one extension's `onLoading`/`onRunning`
  is caught, routed to that extension's own `onException`, and never aborts starting/stopping any
  other registered extension.
- **Push, not poll, for cross-process change awareness.** `cloud-driver-extensions-watcher` uses
  Postgres `LISTEN`/`NOTIFY` over one dedicated, always-open connection rather than a polling loop
  - latency bounded by notification delivery, not a poll interval, and no wasted round-trips when
  nothing has changed.
- **A dedicated, purpose-built backup path for very large tables.** `cloud-driver-extensions-backup`
  uses keyset pagination (`WHERE id > ? ORDER BY id LIMIT ?`, cost independent of how much has
  already been read) specifically because `StoredFile` content can put individual tables in the
  150-200GB range, where a naive unbounded query would exhaust client memory.
- **Known, documented scaling limits exist and are called out, not hidden.** For example,
  `AuthService#login` and `CloudUserService#listFiles` both currently do an in-memory scan over an
  entire entity type because neither `DataFactory` nor the underlying database-driver expose a
  non-primary-key indexed lookup - each module's own README documents the trade-off and the fix
  direction (a secondary index entity, mirroring `StoredFileOwnership`'s composite-key pattern).

## Javadoc conventions

Every public and protected class, method, and field across every module now carries Javadoc
following the Google Java Style Guide shape: a short summary fragment ending in a period, a blank
line, then `@param` (one per parameter, describing what the value means, not restating its name),
`@return` (if non-void), and `@throws` (one per checked or otherwise noteworthy exception,
explaining when it's thrown) - always in English. A few conventions recur throughout and are worth
knowing before adding to any module:

- **`@NonNull` vs. `@NotNull`.** Concrete method parameters use Lombok's `@NonNull` (runtime-
  checked - it actually generates the null check). Abstract/interface method parameters use
  `@NotNull` (`org.jetbrains.annotations`, documentation-only, since there's no method body to
  inject a check into).
- **`this.field`, never a bare `field`.** Instance-field access is qualified with `this.`
  throughout this codebase's source, without exception.
- **Thin pass-through methods point at the contract they implement**, rather than repeating a full
  `@param`/`@throws` block that's already documented on the interface method - e.g. `{@code /**
  Delegates to {@link Target#method}. */}` - to avoid duplication and drift.
- **Hand-written null checks go through `Asserts.requireNonNull(value, "@ClassName.methodName:
  message")`** rather than a bare `Objects.requireNonNull`, so a `NullPointerException` message
  always names both the failing class/method and the failing argument.

## A note on documentation currency

This root README and every module README it links to were written by reading current source
directly, not by trusting any single prior document uncritically - including this repository's own
`CLAUDE.md`, which at various points flags its own sections (particularly around `RestFactory`,
JWT auth, and the `FactoryContainer`/`IServiceContainer` split) as written against an earlier
revision and "not yet re-verified." Where this README and `CLAUDE.md` disagree on a specific
implementation detail, prefer whichever one was more recently verified against source - and when
in doubt, the source itself is always authoritative over either document.
