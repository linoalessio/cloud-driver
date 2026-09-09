# Architecture

This page covers how the system fits together. For the module-by-module map (and the diagrams
that visualize what this page describes), see the [root README](../README.md); for
implementation detail, the source (and its Javadoc) of the module in question is the reference.

## Components

| Component | Kind | Deployable unit |
|---|---|---|
| `cloud-driver-api` | Backend contracts | Compiled into `cloud-driver-bootstrap` |
| `cloud-driver-auth` | Backend auth engine | Compiled into `cloud-driver-bootstrap` |
| `cloud-driver-plugin` | Backend implementations | Compiled into `cloud-driver-bootstrap` |
| `cloud-driver-bootstrap` | Backend entry point | One shaded, runnable jar (`java -jar`) |
| `cloud-driver-extensions-*` | Backend feature modules (REST API, Postgres change watcher, terminal, backup, metrics, thumbnails, versioning, search, webhooks, content scanning, semantic-search bridge) | Unshaded jars, loaded into the bootstrap process from a folder at startup |
| `cloud-driver-intelligence` | Semantic-search service (Python) | **Its own process**, started and stopped independently |
| `cloud-driver-platforms-desktop` | Desktop client app | Native installer (macOS/Windows/Linux) |
| `cloud-driver-platforms-mobile` | Mobile client app (iOS) — GUI only | iOS app build |
| `cloud-driver-multiplatform-java` | Client networking library (Java) | Consumed by the desktop app only |
| `cloud-driver-multiplatform-swift` | Client networking library (Swift) | Consumed by the mobile app only |
| `cloud-driver-multiplatform-python` | Client SDK (Python) | Standalone `pip` package |

## How the backend actually runs

The backend is **one JVM process** (`cloud-driver-bootstrap`), not a fleet of independently deployed
services. What look like separate "services" are `cloud-driver-extensions-*` modules: each one is a
plain, unshaded jar dropped into an `extensions/` folder next to the running bootstrap jar, scanned
and loaded into that same process at startup, and run on its own dedicated thread inside it. This
gives most of the benefit of modular services — a feature can be built, versioned, and reasoned
about independently — without the operational overhead of running and coordinating separate
processes.

```mermaid
flowchart LR
    JAR["extension jar dropped into<br/>extensions/ next to the bootstrap jar"] --> SCAN["Folder scanned<br/>at startup"]
    SCAN --> MAN["extension.json read<br/>(name, version, dependencies)"]
    MAN --> ORDER["Started in dependency order"]
    ORDER --> THREAD["Runs on its own named thread<br/>inside the bootstrap process"]
    THREAD --> PUB["Publishes its services into the<br/>shared service container"]
```

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
vector store — and it is never permitted to decide who may see what. Every semantic search is
two-staged on the Java side: a **pre-filter** offers the service only the file ids the caller
currently has access to (resolved from authoritative ownership/sharing data), and a **post-check**
re-validates every returned id against that same access check, treating the service's answer as
untrusted input. A compromised or stale instance can therefore at worst return nothing — never
another account's files.

## Request flow, end to end

```mermaid
flowchart TD
    CLIENT["Desktop / Mobile client"] -->|"HTTPS + bearer JWT"| REST["cloud-driver-extensions-rest<br/>(REST API surface)"]
    REST --> AUTH["cloud-driver-auth<br/>(CloudUserService, AuthService)"]
    AUTH --> PLUGIN["cloud-driver-plugin<br/>(DefaultDataFactory / DefaultFileFactory /<br/>EntityDatabaseClient)"]
    PLUGIN --> ENC["Envelope encryption<br/>fresh DEK → AES-256-GCM encrypt →<br/>DEK wrapped under the active KEK"]
    ENC --> PG[("Postgres<br/>(via the external database-driver<br/>artifact group)")]
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

```mermaid
flowchart LR
    API["cloud-driver-api"] --> AUTH["cloud-driver-auth"]
    AUTH --> PLUGIN["cloud-driver-plugin"]
    PLUGIN --> BOOT["cloud-driver-bootstrap"]
    PLUGIN --> EXT["cloud-driver-extensions-*"]
```

An arrow `A --> B` reads "B builds on A" — i.e. `B` is allowed to depend on `A`'s types, never
the reverse.

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
