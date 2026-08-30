# cloud-driver-extensions-watcher

Wraps Postgres change-notification (`LISTEN`/`NOTIFY`) as an `Extension` - push, not poll, notice of every write to `StoredFile`'s table, from any process, routed into `cloud-driver`'s own `event` framework. This logic used to live directly inside `cloud-driver-bootstrap` (`CloudBootstrap.startDatabaseChangeNotifier`); it was pulled out into its own extension so the host application doesn't have to hardcode a Postgres-specific subsystem as one of its bespoke `startX()` methods.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling
of `cloud-driver-extensions-backup`/`-rest`/`-terminal`. Its `pom.xml` declares two in-repo/vendor
dependencies: `cloud-driver-api` (for `Extension`/`DatabaseWatchEvent`) and, unusually among the
extension modules, a **direct** dependency on `database-driver-plugin` (not just `cloud-driver-api`/
`-plugin`) - needed because `PostgresDatabaseNotification` (the actual `LISTEN`/`NOTIFY` mechanics
this module wires up) lives in that upstream artifact, not anywhere in this repo. It does **not**
depend on `cloud-driver-plugin` at all. Package: `de.lino.cloud.extensions.watcher` (one class,
`CloudWatcherExtension`).

## Why this exists

`EntityDatabaseClient` (`cloud-driver-plugin`) caches each entity type's `DatabaseSection` in process-local memory once it's first resolved - reads never go back to the database on their own. That means a row written by a *different* process (a laptop uploading a file straight to the same shared Postgres instance a deployed server is also connected to) is invisible to the server's own `findById`/`getEntities` indefinitely, not just for a cache TTL, until something calls `DataFactory#reload(type)` or the process restarts. This extension is that "something": it listens for Postgres's own `NOTIFY` on every `INSERT`/`UPDATE` against `StoredFile`'s table and reacts to each one by reloading that section before re-fetching the row.

## `extension.json`

```json
{
  "name": "cloud-driver-watcher",
  "version": "1.0.0",
  "description": "CloudDriver watcher notifying the system if a new entry was uploaded",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Registering this extension therefore requires `"cloud-driver-bootstrap"` (the host application's own placeholder extension) to already be registered and `RUNNING` - `ExtensionFactory#start`'s dependency check enforces this before `onLoading`/`onRunning` ever runs. Note: as of this writing, `extension.json` declares **only** this one dependency - it does not additionally depend on any sibling extension in this tree.

## Code structure

- **`CloudWatcherExtension`** (`de.lino.cloud.extensions.watcher`) - the extension itself, three lifecycle hooks worth knowing:
  - `onLoading()` - resolves `Credentials` from `Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")` (independently of whatever `CloudBootstrap.initiateCloudDriver()` itself read from the same file - an `Extension` only ever gets a no-arg constructor, so it can't receive an already-loaded `Credentials` from its host) and constructs a `PostgresDatabaseNotification` on channel `"cloud_driver_watcher"`.
  - `onRunning(String[])` - calls `watch(StoredFile.class)` (installs the trigger, idempotently, over a pooled connection) then `start(...)` (opens a dedicated, non-pooled `Connection` and blocks a background thread on `LISTEN`), routing every notification through `this.cloudDriver().getFactoryContainer().getEventFactory().dispatch(DatabaseWatchEvent.class, payload)`.
  - `onEnding()`/`onException(RuntimeException)` - both shut the listener down (null-guarded, since `onLoading()` may not have reached its assignment if `postgres-database.json` was missing/malformed).
- **`DatabaseWatchEvent`** (`de.lino.cloud.api.event.database`, `cloud-driver-api` - not in this module) - the actual handling logic this extension dispatches into. It has to live in `cloud-driver-api` rather than here or in `cloud-driver-bootstrap`, because this extension cannot depend on `cloud-driver-bootstrap` (extensions are meant to be host-agnostic) while still needing a class both sides can reach.
- The actual `LISTEN`/`NOTIFY` mechanics (`PostgresDatabaseNotification`, `DatabaseNotification`) are **not** part of this repo at all - they live upstream in `database-driver-plugin`/`database-driver-api` (pinned to `1.3.11`). This module only wires that vendor class up as an `Extension` and supplies the callback.

## Concurrency / async characteristics

- **A dedicated daemon thread blocks on a real socket read**, not a poll loop: `PostgresDatabaseNotification#start` opens its own raw JDBC `Connection` (bypassing the pooled `SQLExecution` this same class also owns for trigger installation) and blocks on `PGConnection#getNotifications(0)` - true push, zero polling overhead, at the cost of one dedicated connection held open for the life of the listener.
- **`watch()` and `start()` use different connections for different reasons.** Trigger installation (`watch`) runs through a pooled `SQLExecution` connection as one transaction (notify function + trigger drop + trigger create together, so a failure can't leave a half-applied trigger) - safe to call before, after, or concurrently with a running listener. The listener itself needs to hold one specific connection open indefinitely, which no pooled API can offer, hence the separate raw connection.
- **The notification callback is deliberately try/caught inside `onRunning`.** `PostgresDatabaseNotification#listen` invokes the callback directly inside its blocking read loop with no try/catch of its own, and that class has no reconnect logic - an uncaught `RuntimeException` from the callback would silently and permanently kill the listener thread for the rest of the process's life (`isRunning()` would still report `true`; the loop just returns). Wrapping the `dispatch` call here is defense-in-depth on top of `DatabaseWatchEvent#handle`'s own internal not-found handling.
- **Reload-before-fetch, not fetch-then-maybe-reload.** `DatabaseWatchEvent#handle` calls `dataFactory.reload(StoredFile.class)` unconditionally before `fileFactory.findById(id)` - a notification is precisely the signal that *some* process (possibly not this one) just wrote a row this process's own section mirror may not know about yet, so skipping the reload would make the whole mechanism a no-op for exactly the cross-process case it exists to solve. If `findById` still comes up empty after the reload (e.g. the row was deleted again before the notification was processed), that's logged as a warning and the method returns - never `.orElseThrow()`, since an uncaught exception here would hit the same "kill the listener thread forever" failure mode described above.
- **One type only, today.** `onRunning` only calls `watch(StoredFile.class)`. A new entity type added to the system after this extension is already running is not picked up automatically - `watch` needs to be called again explicitly for it.

## Data handling and safety

- The `NOTIFY` payload is a small JSON object - `{"table", "operation", "id"}` - **never** the row's own (already encrypted) data. The actual content is always re-fetched through the normal `FileFactory`/`DataFactory` path, so this extension never needs to reason about encrypted bytes directly.
- `watch()` hardcodes the exact Postgres schema `database-driver-plugin 1.3.11`'s `SQLDatabaseSection` happens to create (`id TEXT, data BYTEA`) - this is an implementation detail of that upstream artifact, not a published cross-version contract, and needs re-verifying whenever its pinned version changes.
- `watch()` must be called only after the target type's table already exists (i.e. after at least one instance has been persisted via `DataFactory`) - `CREATE TRIGGER` fails otherwise, and that failure is swallowed (logged to stderr, not thrown) by `SQLExecution#executeTransaction`'s own convention. In practice this is guaranteed for `StoredFile` by `CloudBootstrap.main` unconditionally uploading a fixed-id smoke-test file before any extension's `onRunning` ever runs.

## Scalability

Push-based notification scales far better than any poll-based alternative would: no wasted round-trips when nothing has changed, and latency is bounded only by network/Postgres notification delivery rather than a poll interval. The one structural limit is the dedicated, always-open listener connection this class holds for as long as it runs - a single connection regardless of how many rows are written, so this does not compete with normal query traffic for pool capacity the way a poll-based approach hammering the same pool would.

## API usage

This module exposes no library API - `CloudWatcherExtension` is loaded as a jar dropped into
`Constraints.EXTENSIONS_PATH` (or picked up the same way by `shell/test-bootstrap.sh`), not
called directly from Java. Build it alongside a bootstrap jar built from the same commit:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-watcher -am package
```

To react to the change notifications it dispatches from another extension, register a handler
for `DatabaseWatchEvent` the same way `CloudBootstrap`'s own `startEventScheduler` does:

```java
CloudDriver.getInstance().getFactoryContainer().getEventFactory()
        .registerEventAsync(DatabaseWatchEvent.class);
```

## Javadoc conventions

`CloudWatcherExtension` and `DatabaseWatchEvent` follow the same Google Java Style Guide conventions as the rest of this tree - summary fragment, blank line, `@param`/`@throws` as applicable - and were already in good shape as of this documentation pass; no Javadoc changes were needed here.
