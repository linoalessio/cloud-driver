# cloud-driver-extensions-backup

Wraps `DatabaseBackupScheduler` - a keyset-paginated, streaming Postgres backup job that never loads more than one bounded page of a table into memory at once - as a `cloud-driver` `Extension`, so a periodic, whole-database export starts and stops the same way any other extension does. Purpose-built for tables in the 150-200GB range (`StoredFile` content, stored as `BYTEA`, is exactly the kind of data that gets there); a naive backup built on `SQLExecution#executeQuery` with no JDBC fetch-size/cursor set would have the Postgres driver buffer an entire `SELECT * FROM table` client-side before processing even the first row - a guaranteed `OutOfMemoryError` at this scale.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), itself a top-level sibling of `cloud-driver-api`/`cloud-driver-auth`/`cloud-driver-plugin`/`cloud-driver-bootstrap` in the root reactor. This module's own `pom.xml` sets `packaging=jar` and declares exactly one in-repo Maven dependency, `cloud-driver-plugin` (`1.0.1`) - it does **not** depend on `cloud-driver-bootstrap`/`cloud-driver-auth` directly at the Maven level.

`extension.json` (`src/main/resources`):

```json
{
  "name": "cloud-driver-backup",
  "version": "1.0.0",
  "description": "CloudDriver backup extension backing up the database periodically",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

The `"cloud-driver-bootstrap"` entry is an `ExtensionFactory` startup-ordering constraint (this extension's `onLoading`/`onRunning` won't run until the host's own placeholder extension is registered and `RUNNING`), not a Maven dependency - it has no counterpart in `<dependencies>` above.

Package layout: `de.lino.cloud.extensions` - both classes (`CloudBackupExtension`, `DatabaseBackupScheduler`) live directly in it, no sub-packages.

## Performance

- **Its own daemon thread, on its own schedule.** `DatabaseBackupScheduler` runs on a single-thread `ScheduledExecutorService` (thread name `postgres-backup-scheduler`), ticking every `DEFAULT_PERIOD` (3 days) by default via the no-arg `start()`, or a custom `Duration` via `start(Duration)`.
- **Keyset pagination, not `LIMIT`/`OFFSET`.** Each page is fetched via `WHERE id > ? ORDER BY id LIMIT ?`, whose cost does not grow with how much has already been read - unlike `OFFSET`, which the database must still scan past. At most `batchSize` (default 500) rows are held in memory per table at any point, regardless of table size.
- **Its own, dedicated `SQLExecution` connection pool**, deliberately not the running application's own pool - an hours-long backup run should never compete with production traffic for connections.
- **Bounded parallel table export.** Tables are exported concurrently over a fixed-size thread pool (`postgres-backup-table-worker`) sized to `tableParallelism` (default `clamp(availableProcessors(), 1, 4)`), so multiple tables progress at once without unbounded concurrency against the database.
- **Compact binary format, not JSON/Base64.** Per-row output is a simple length-prefixed binary layout (`int idLength, id bytes, int dataLength, data bytes`), GZIP-compressed directly as it's written at `Deflater.BEST_SPEED` - Base64 would have inflated the already-binary `BYTEA` content by roughly a third for no benefit, and `BEST_SPEED` trades a small amount of size for a real reduction in CPU cost at this data volume.
- **Archiving without re-compressing.** The final ZIP archive is built with `Deflater.NO_COMPRESSION` since every per-table file is already GZIP-compressed - re-compressing already-compressed, high-entropy data wastes CPU for essentially no size reduction.
- **Overlapping cycles are prevented, not merely discouraged.** `tick()` uses an `AtomicBoolean` guard (`cycleRunning`) so a new tick that fires while a previous (potentially hours-long) cycle is still running is skipped outright rather than queued or run concurrently.
- **Failures on the scheduler's own thread are caught and logged, never allowed to propagate.** `tick()` wraps `runBackupCycle()` in a broad `try`/`catch (Throwable)` - an uncaught exception on a `ScheduledExecutorService`'s own thread would otherwise silently cancel every future tick for the life of the process.

## Data handling

- Reads raw, already-encrypted `BYTEA` content directly off the database, one keyset page at a time (`id`, `data` pairs) - it never decrypts anything.
- Table names are discovered via `information_schema.tables` (`public` schema only), not configured up front.
- Writes each table into its own length-prefixed, GZIP-compressed `.bin.gz` file under a per-cycle staging directory (`<backupRootDirectory>/.staging/cloud-driver-backup-<timestamp>`), then archives the whole staging directory into one `cloud-driver-backup-<timestamp>.zip` under `backupRootDirectory` (`CloudBackupExtension` targets a `backup` subdirectory of `Constraints.CONFIGURATION_PATH`).
- **Retention/rotation** (`enforceRetention`) deletes the oldest archives once more than `retainedBackups` (default 7) exist, so daily 150-200GB cycles don't fill the disk within a few days.
- The staging directory is always cleaned up in a `finally` block after each cycle, best-effort, even on failure - an orphaned staging directory does not block the next cycle from proceeding.

## Safety & security

- This module reads raw, already-envelope-encrypted `BYTEA` content directly off the database - it never needs to (and does not) decrypt anything. The exported archive is exactly as sensitive as the live database itself and should be protected accordingly; **this module does not itself encrypt the archive.**
- Table/file names are defensively sanitized/quoted before being interpolated into SQL or used as file names (`quoteIdentifier`, `sanitizeFileName`) - table names come from `information_schema.tables`, not user input, but this is still a real SQL-injection-shaped surface if that assumption ever changes.
- `CloudBackupExtension.onLoading()` resolves live Postgres credentials from `postgres-database.json` (a gitignored local secret, per the repo root `CLAUDE.md`'s "Local dev secrets" section) - the same trust boundary `CloudBootstrap`'s own read of that file has.

## Scalability

Purpose-built for the 150-200GB-per-table regime described in its own class Javadoc: memory use per table export is bounded to one page (`batchSize` rows) regardless of table size, and `listTables()` deliberately goes through `SQLExecution` directly rather than `DatabaseProvider`/`SQLDatabaseSection`, since the latter load an entire table into an in-memory cache on section creation - exactly the problem this class exists to avoid. Currently tailored to PostgreSQL only (`information_schema.tables`); supporting another SQL dialect would mean extending `listTables()` with that dialect's equivalent query. Each `DatabaseBackupScheduler` instance runs independently and holds its own dedicated connection pool - running this extension on more than one process against the same database would run uncoordinated, overlapping full backup cycles, which this module has no protection against.

## API surface

- **`CloudBackupExtension`** (`de.lino.cloud.extensions`) - the extension itself: `onLoading()` resolves `Credentials` from `postgres-database.json` and constructs a `DatabaseBackupScheduler` targeting a `backup` subdirectory of `Constraints.CONFIGURATION_PATH`, throwing `IllegalStateException` if that file is missing/malformed; `onRunning` starts it on its default interval; `onEnding()`/`onException(RuntimeException)` both call the scheduler's `shutdown()` (null-guarded), so the scheduler's background thread and its dedicated `SQLExecution` pool are properly released when the extension stops.
- **`DatabaseBackupScheduler`** (`de.lino.cloud.extensions`, `public final`) - the actual job, entirely self-contained (no `cloud-driver-api`/`cloud-driver-plugin` contract of its own, the same "concrete infrastructure, not a swappable behavior" reasoning as `EntityDatabaseClient`): determine tables -> parallel, keyset-paginated exports per table -> archive without re-compressing -> place -> rotate old archives. Two public constructors - `(Credentials, Path)` (default tuning) and `(Credentials, Path, int batchSize, int tableParallelism, int retainedBackups)` (explicit tuning) - plus `start()`/`start(Duration)`, `stop()` (pauses, keeps the pool/executor alive), and `shutdown()` (full teardown).

## API usage

This module exposes no library API meant for external callers - `CloudBackupExtension` is loaded as a jar dropped into `Constraints.EXTENSIONS_PATH` (or built as part of the reactor and picked up the same way by `shell/test-bootstrap.sh`), not called directly from Java in normal operation. In place of a caller-facing usage example, here is the build command and the equivalent direct-construction snippet for a caller (e.g. another extension, or a standalone tool) that wants `DatabaseBackupScheduler` without going through the extension-loading mechanism at all:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-backup -am package
```

`CloudBackupExtension` is registered automatically once its jar sits in the scanned folder - no code wiring is needed. Build it and drop it alongside a `cloud-driver-bootstrap` jar built from the same commit (an unshaded extension jar resolves shared types off the host bootstrap jar's own classpath).

```java
Credentials credentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json"))
        .orElseThrow();

DatabaseBackupScheduler scheduler = new DatabaseBackupScheduler(
        credentials, Constraints.CONFIGURATION_PATH.resolve("backup")
);
scheduler.start();
```
