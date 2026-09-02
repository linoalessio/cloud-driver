# cloud-driver-extensions-watcher

Wraps Postgres change-notification (`LISTEN`/`NOTIFY`) as a `cloud-driver` `Extension` - push, not poll, notice of every write to `StoredFile`'s table, from any process, routed into `cloud-driver`'s own `event` framework as a `DatabaseWatchEvent` and, since item 10 of `architecture/SERVICES.md`, forwarded onward to any connected desktop/web client via `LiveUpdatePublisher`'s WebSocket transport. This logic used to live directly inside `cloud-driver-bootstrap` (`CloudBootstrap.startDatabaseChangeNotifier`); it was pulled out into its own extension so the host application doesn't have to hardcode a Postgres-specific subsystem as one of its bespoke `startX()` methods.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling of `cloud-driver-extensions-backup`/`-metrics`/`-rest`/`-terminal`. This module's own `pom.xml` sets `packaging=jar` and declares two dependencies: `cloud-driver-api` (`1.0.1`, for `Extension`/`DatabaseWatchEvent`) and, unusually among the extension modules, a **direct** dependency on `de.lino.database:database-driver-plugin:1.3.11` - needed because `PostgresDatabaseNotification` (the actual `LISTEN`/`NOTIFY` mechanics this module wires up) lives in that upstream artifact, not anywhere in this repo. It does **not** depend on `cloud-driver-plugin` at all.

`extension.json` (`src/main/resources`):

```json
{
  "name": "cloud-driver-watcher",
  "version": "1.0.0",
  "description": "CloudDriver watcher notifying the system if a new entry was uploaded",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Registering this extension requires `"cloud-driver-bootstrap"` (the host application's own placeholder extension) to already be registered and `RUNNING` - `ExtensionFactory#start`'s dependency check enforces this before `onLoading`/`onRunning` ever runs. `extension.json` declares only this one dependency - not any sibling extension in this tree.

Package layout: `de.lino.cloud.extensions.watcher` - one class, `CloudWatcherExtension`. The event it dispatches into, `DatabaseWatchEvent`, lives in `cloud-driver-api` (`de.lino.cloud.api.event.database`), not in this module - it has to, since this extension cannot depend on `cloud-driver-bootstrap` (extensions are meant to be host-agnostic) while still needing a class both this extension and the host application can reach.

## Performance

- **A dedicated daemon thread blocks on a real socket read**, not a poll loop: `PostgresDatabaseNotification#start` opens its own raw JDBC `Connection` (bypassing the pooled `SQLExecution` this same class also owns for trigger installation) and blocks on `PGConnection#getNotifications(0)` - true push, zero polling overhead, at the cost of one dedicated connection held open for the life of the listener.
- **`watch()` and `start()` use different connections for different reasons.** Trigger installation (`watch`) runs through a pooled `SQLExecution` connection as one transaction (notify function + trigger drop + trigger create together, so a failure can't leave a half-applied trigger) - safe to call before, after, or concurrently with a running listener. The listener itself needs to hold one specific connection open indefinitely, which no pooled API can offer, hence the separate raw connection.
- **The notification callback is deliberately try/caught inside `onRunning`.** `PostgresDatabaseNotification#listen` invokes the callback directly inside its blocking read loop with no try/catch of its own, and that class has no reconnect logic - an uncaught `RuntimeException` from the callback would silently and permanently kill the listener thread for the rest of the process's life. Wrapping the `dispatch` call here is defense-in-depth on top of `DatabaseWatchEvent#handle`'s own internal not-found handling.
- **`DatabaseWatchEvent#handle` tries the cheap, reload-free lookup first, and only reloads on an actual miss** (fixed 2026-09-02, a real OOM incident): an earlier revision called `dataFactory.reload(StoredFile.class)` unconditionally, on every single notification, before ever attempting `findById` - since `reload` re-reads `StoredFile`'s *entire* table (every row's encrypted content included) into this process's local section mirror, a burst of many uploads in quick succession (e.g. extracting a large archive in the desktop app) fired that full-table reload once per notification, several racing reloads exhausted the heap. Since this deployment's Postgres instance runs co-located with the same process that receives most notifications, that process's own section mirror is already up to date from its own write in the vast majority of cases - a reload is only genuinely needed for a row written by a *different* process. `handle` now calls `findById` first and only reloads (then retries once) on a miss.
- **One type only, today.** `onRunning` only calls `watch(StoredFile.class)`. A new entity type added to the system after this extension is already running is not picked up automatically - `watch` needs to be called again explicitly for it.

## Data handling

- The `NOTIFY` payload is a small JSON object - `{"table", "operation", "id"}` - **never** the row's own (already encrypted) data. The actual content is always re-fetched through the normal `FileFactory`/`DataFactory` path, so this extension never needs to reason about encrypted bytes directly.
- **Live push via WebSocket (item 10).** `DatabaseWatchEvent#handle` unconditionally (regardless of whether its own `findById` re-fetch hit or missed) also resolves which account owns the changed file id, via `ICloudUserService#resolveOwnerAuthUserId`, and forwards the same `table`/`operation`/`id` triple to `LiveUpdatePublisher#publish` - reaching every WebSocket session that account currently has open (`DefaultRestFactory`'s `GET /ws/updates`, `cloud-driver-plugin`) so a connected desktop/web client refreshes without polling. This only runs once both `ICloudUserService` and a `LiveUpdatePublisher` have actually been published into `IServiceContainer` (i.e. once `CloudRestExtension` has started) - a no-op otherwise, and the whole push is wrapped in its own try/catch so a broken client connection or a resolution failure can never propagate back into the notification listener thread.
- `watch()` hardcodes the exact Postgres schema `database-driver-plugin 1.3.11`'s `SQLDatabaseSection` happens to create (`id TEXT, data BYTEA`) - this is an implementation detail of that upstream artifact, not a published cross-version contract, and needs re-verifying whenever its pinned version changes.
- `watch()` must be called only after the target type's table already exists (i.e. after at least one instance has been persisted via `DataFactory`) - `CREATE TRIGGER` fails otherwise, and that failure is swallowed (logged to stderr, not thrown) by `SQLExecution#executeTransaction`'s own convention. In practice this is guaranteed for `StoredFile` by `CloudBootstrap.main` unconditionally uploading a fixed-id smoke-test file before any extension's `onRunning` ever runs.

## Safety & security

- This extension - and `DatabaseWatchEvent`, the class it dispatches into - only ever reacts to already-encrypted row ids and generic operation/table metadata; it never sees or handles plaintext content at any point. The real, decrypted `StoredFile` is only ever reached afterward, through the normal `FileFactory`/`DataFactory` path, which applies the usual envelope-decryption/checksum checks.
- `CloudWatcherExtension.onLoading()` resolves `Credentials` independently from `postgres-database.json` (a gitignored local secret, per the repo root `CLAUDE.md`'s "Local dev secrets" section) - the same trust boundary `CloudBootstrap`'s own read of that file has, and independent of it (an `Extension` only ever gets a no-arg constructor, so it can't receive an already-loaded `Credentials` from its host).
- `DatabaseWatchEvent#pushLiveUpdate` never lets a failure escape into the notification listener thread - wrapped in its own try/catch, on top of `LiveUpdatePublisher#publish`'s own contractual "must never throw" guarantee.
- The `watch()` call itself is not wrapped in a try/catch by `CloudWatcherExtension` - it doesn't need to be, since `PostgresDatabaseNotification#watch` (per `database-driver-plugin`'s own sources) catches `SQLException` internally and only logs it, never throwing a checked exception out.

## Scalability

Push-based notification scales far better than any poll-based alternative would: no wasted round-trips when nothing has changed, and latency is bounded only by network/Postgres notification delivery rather than a poll interval. The one structural limit is the dedicated, always-open listener connection this class holds for as long as it runs - a single connection regardless of how many rows are written, so this does not compete with normal query traffic for pool capacity the way a poll-based approach hammering the same pool would. `pushLiveUpdate`'s owner resolution (`ICloudUserService#resolveOwnerAuthUserId`) is a full `StoredFileOwnership`-section scan, paid once per notification on the listener thread - the same accepted full-scan trade-off other owner-lookup paths in this codebase make, not a bottleneck at today's scale but worth knowing if notification volume grows substantially.

## API surface

- **`CloudWatcherExtension`** (`de.lino.cloud.extensions.watcher`, this module) - the extension itself: `onLoading()` resolves `Credentials` from `postgres-database.json` and constructs a `PostgresDatabaseNotification` on channel `"cloud_driver_watcher"`; `onRunning(String[])` calls `watch(StoredFile.class)` then `start(...)`, routing every notification through `cloudDriver().getFactoryContainer().getEventFactory().dispatch(DatabaseWatchEvent.class, payload)`; `onEnding()`/`onException(RuntimeException)` both shut the listener down (null-guarded).
- **`DatabaseWatchEvent`** (`de.lino.cloud.api.event.database`, `cloud-driver-api` - not in this module, but the class this extension's dispatch target) - re-fetches the notified `StoredFile` (reload-on-miss only) and forwards the change to `LiveUpdatePublisher` for connected clients; see "Data handling" above.
- **`PostgresDatabaseNotification`/`DatabaseNotification`** (upstream, `database-driver-plugin`/`database-driver-api`, pinned `1.3.11`) - the actual `LISTEN`/`NOTIFY` mechanics, not part of this repo at all. This module only wires that vendor class up as an `Extension` and supplies the callback.

## API usage

This module exposes no library API meant for external callers - `CloudWatcherExtension` is loaded as a jar dropped into `Constraints.EXTENSIONS_PATH` (or picked up the same way by `shell/test-bootstrap.sh`), not called directly from Java. In place of a caller-facing usage example, here is the build command and how another extension reacts to the notifications this one dispatches:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-watcher -am package
```

To react to the change notifications it dispatches, register a handler for `DatabaseWatchEvent` the same way `CloudBootstrap`'s own `startEventScheduler` does:

```java
CloudDriver.getInstance().getFactoryContainer().getEventFactory()
        .registerEventAsync(DatabaseWatchEvent.class);
```
