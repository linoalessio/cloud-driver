# Architecture

This page covers how the system fits together. For a single module's implementation detail, see
that module's own `README.md` (linked from the [root README](../README.md)'s module map).

## Components

| Component | Kind | Deployable unit | README |
|---|---|---|---|
| `cloud-driver-api` | Backend contracts | Compiled into `cloud-driver-bootstrap` | [`cloud-driver-api/README.md`](../cloud-driver-api/README.md) |
| `cloud-driver-auth` | Backend auth engine | Compiled into `cloud-driver-bootstrap` | [`cloud-driver-auth/README.md`](../cloud-driver-auth/README.md) |
| `cloud-driver-plugin` | Backend implementations | Compiled into `cloud-driver-bootstrap` | [`cloud-driver-plugin/README.md`](../cloud-driver-plugin/README.md) |
| `cloud-driver-bootstrap` | Backend entry point | One shaded, runnable jar (`java -jar`) | [`cloud-driver-bootstrap/README.md`](../cloud-driver-bootstrap/README.md) |
| `cloud-driver-extensions-*` | Backend feature modules (REST API, Postgres change watcher, terminal, backup, metrics, thumbnails, versioning, search, webhooks, content scanning, semantic-search bridge) | Unshaded jars, loaded into the bootstrap process from a folder at startup | [`cloud-driver-extensions/README.md`](../cloud-driver-extensions/README.md) |
| `cloud-driver-intelligence` | Semantic-search service (Python) | **Its own process/container**, started and stopped independently | [README](../cloud-driver-intelligence/README.md) |
| `cloud-driver-platforms-desktop` | Desktop client app | Native installer (macOS/Windows/Linux) | [README](../cloud-driver-platforms/cloud-driver-platforms-desktop/README.md) |
| `cloud-driver-platforms-mobile` | Mobile client app (iOS) — GUI only | iOS app build | [README](../cloud-driver-platforms/cloud-driver-platforms-mobile/README.md) |
| `cloud-driver-multiplatform-java` | Client networking library (Java) | Consumed by the desktop app only | [README](../cloud-driver-multiplatform/cloud-driver-multiplatform-java/README.md) |
| `cloud-driver-multiplatform-swift` | Client networking library (Swift) | Consumed by the mobile app only | [README](../cloud-driver-multiplatform/cloud-driver-multiplatform-swift/README.md) |
| `cloud-driver-multiplatform-python` | Client SDK (Python) | Standalone `pip` package | [README](../cloud-driver-multiplatform/cloud-driver-multiplatform-python/README.md) |

## How the backend actually runs

The backend is **one JVM process** (`cloud-driver-bootstrap`), not a fleet of independently deployed
services. What look like separate "services" are `cloud-driver-extensions-*` modules: each one is a
plain, unshaded jar dropped into an `extensions/` folder next to the running bootstrap jar, scanned
and loaded into that same process at startup, and run on its own dedicated thread inside it. This
gives most of the benefit of modular services — a feature can be built, versioned, and reasoned
about independently — without the operational overhead of running and coordinating separate
processes.

| Extension | Responsibility |
|---|---|
| `cloud-driver-extensions-rest` | The JWT-authenticated REST API (login, registration, files, folders, sharing, trash, admin routes, live push) |
| `cloud-driver-extensions-watcher` | Postgres `LISTEN`/`NOTIFY` change notification; forwards live updates to connected clients over WebSocket |
| `cloud-driver-extensions-terminal` | The operator-facing interactive terminal and its command catalog |
| `cloud-driver-extensions-backup` | Streaming, keyset-paginated database backup job |
| `cloud-driver-extensions-metrics` | Prometheus-scrapeable `/metrics` endpoint on its own loopback-only port |
| `cloud-driver-extensions-thumbnails` | Generates preview thumbnails for images and PDF first pages |
| `cloud-driver-extensions-versioning` | Keeps prior versions of a file's content on overwrite, with a restore path |
| `cloud-driver-extensions-search` | Keyword search index over file names and extracted text |
| `cloud-driver-extensions-webhooks` | Delivers signed, retried HTTP callbacks for file events |
| `cloud-driver-extensions-scan` | Malware-scans uploaded content via an external `clamd` daemon |
| `cloud-driver-extensions-intelligence` | Bridges uploads to the `cloud-driver-intelligence` Python service, and backs semantic search |

### The exception: external processes

Three pieces of the backend are genuinely separate processes rather than in-process extensions,
because each owns something a JVM extension cannot: `clamd` (virus definitions), Redis (state that
survives a restart), and `cloud-driver-intelligence` (an embedding model and vector store, in
Python). None of the three is required for `cloud-driver` to boot, and each has an in-process
counterpart that degrades rather than fails when it is absent.

`cloud-driver-intelligence` additionally never touches Postgres — its only data store is its own
vector store — and it is never permitted to decide who may see what. See
[its README](../cloud-driver-intelligence/README.md) for that two-stage security model.

## Request flow, end to end

```
Desktop / Mobile client
        │  HTTPS + bearer JWT
        ▼
cloud-driver-extensions-rest  (REST API surface)
        │
        ▼
cloud-driver-auth  (CloudUserService, AuthService)
        │
        ▼
cloud-driver-plugin  (DefaultDataFactory / DefaultFileFactory / EntityDatabaseClient)
        │
        ▼
Envelope encryption  (fresh DEK generated → AES-256-GCM encrypt → DEK wrapped under the active KEK)
        │
        ▼
Postgres  (via the external database-driver artifact group)
```

Reading reverses every step, additionally verifying the AES-256-GCM authentication tag and (for
files) a plaintext checksum before the caller ever sees decrypted data. Nothing plaintext is ever
written to the database.

## Layering rule

The codebase follows one rule almost everywhere: **`cloud-driver-api` defines the contract,
`cloud-driver-plugin`/`cloud-driver-auth` supply the implementation.**

- **`CloudDriver` is a facade, not a monolith.** It exposes an `IFactoryContainer`
  (data/file/extension/event/REST factories) and an `IServiceContainer` (auth service, cloud-user
  service, live-update publisher, metrics, audit log), plus a connectivity checker and a terminal.
  It holds no persistence or lifecycle logic of its own.
- **Abstract primitives + generic concrete async methods.** Every factory-shaped contract
  (`DataFactory`, `FileFactory`, `ExtensionFactory`, `EventFactory`, `RestFactory`) shares one
  shape: a handful of abstract synchronous primitives a concrete class must implement, plus every
  async variant implemented once, generically, on the abstract class itself. Adding a new facade
  means implementing the primitives; the async surface comes for free.
- **A layered security stack.** Raw AEAD → DEK/KEK envelope encryption → hashing/password →
  secret redaction → entity binding (ties an entity's type and primary key into the encryption's
  authenticated data) → the one class that actually touches the database. Each layer is
  independently replaceable.
- **Extensions are host-agnostic plugins, not compiled-in features.** A jar dropped into the
  configured extensions folder is picked up purely by declaring a concrete extension class and
  shipping a small manifest file — no compile-time dependency on `cloud-driver-bootstrap` required.

## Dependency direction

```
cloud-driver-api  ←  cloud-driver-auth  ←  cloud-driver-plugin  ←  cloud-driver-bootstrap / extensions-*
```

Never add a dependency the other way — `cloud-driver-api` must never depend on `cloud-driver-auth`
or `cloud-driver-plugin`, and `cloud-driver-auth` must never depend on `cloud-driver-plugin`.
`cloud-driver-multiplatform-java`/`cloud-driver-multiplatform-swift`/`cloud-driver-multiplatform-python`/`cloud-driver-platforms-desktop`/
`cloud-driver-platforms-mobile` sit entirely outside this chain — none of them depend on any
server-side module, only on each other (desktop depends on `cloud-driver-multiplatform-java`; mobile depends on
`cloud-driver-multiplatform-swift`; the Python SDK depends on neither, being a separate-ecosystem client of the
same HTTP/WebSocket API).

## Data handling

- **Files are entities, not a separate storage path.** A stored file goes through the exact same
  encryption/persistence pipeline as any other record, plus a double integrity check on read (the
  encryption's own authentication tag, then a plaintext checksum recorded at upload time).
- **Cross-process staleness is explicit, not silent.** Once a running process has read an entity
  type's data once, a row written by a *different* process is invisible to it indefinitely — not
  just past a cache expiry — until an explicit reload happens. The Postgres change-notification
  feature module exists specifically to trigger that reload automatically in reaction to a
  database-level change notification.
- **Offline uploads are deferred, not dropped.** An upload attempted with no network connectivity
  is queued locally and retried automatically once connectivity returns, rather than failing
  outright.

## Performance and scalability notes

- Every async method and batch operation is dispatched onto a shared virtual-thread executor
  rather than looping sequentially — a batch of many operations pays for roughly one round trip's
  worth of wall-clock latency, not one per item.
- Each entity type gets its own isolated database section and decryption cache, so one hot or
  misbehaving type can't starve another's throughput.
- The decryption cache is bounded and time-limited (default 30 seconds / 1,000 entries) since it
  holds decrypted plaintext in memory.
- REST handlers never block a request-handling thread — the underlying database/encryption work
  always runs on a virtual thread.
- Change notification uses push (Postgres `LISTEN`/`NOTIFY`), not a polling loop, so latency is
  bounded by notification delivery rather than a poll interval.
- The backup job uses keyset pagination rather than a single unbounded query, since file-content
  tables can reach sizes that would otherwise exhaust client memory.
- Some read paths (login lookup, an account's own file listing) still do a full in-memory scan of
  an entity type, because the underlying storage layer has no secondary index. This is a known,
  accepted limitation at the current data scale, not an oversight.
