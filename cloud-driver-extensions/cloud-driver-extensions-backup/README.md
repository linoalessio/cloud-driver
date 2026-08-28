# cloud-driver-extensions-backup

Wraps `DatabaseBackupScheduler`, a keyset-paginated, streaming Postgres backup job, as an `Extension` - a periodic, whole-database export that never loads more than one bounded page of a table into memory at once, purpose-built for tables in the 150-200GB range (this codebase's `StoredFile` content, stored as `BYTEA`, is exactly the kind of data that gets there).

## Why this exists

A naive backup built on `SQLExecution#executeQuery` (a plain parameterized query helper, with no JDBC fetch-size/cursor configured) would have the Postgres driver load an entire `SELECT * FROM table` into client memory before processing even the first row - a guaranteed `OutOfMemoryError` at this data scale. Rather than modifying `SQLExecution` itself (an upstream `database-driver` class), this module works around the limitation entirely at the call site, via keyset pagination.

## `extension.json`

```json
{
  "name": "cloud-driver-backup",
  "version": "1.0.0",
  "description": "CloudDriver backup extension backing up the database periodically",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Depends only on `"cloud-driver-bootstrap"` being registered and `RUNNING` first.

## Code structure

- **`CloudBackupExtension`** (`de.lino.cloud.extensions`) - the extension: `onLoading()` resolves `Credentials` from `postgres-database.json` and constructs a `DatabaseBackupScheduler` targeting a `backup` subdirectory of `Constraints.CONFIGURATION_PATH`; `onRunning` starts it on its default interval. Notably, `onEnding()` is currently a no-op - the scheduler is not explicitly stopped/shut down when the extension itself ends (see Findings).
- **`DatabaseBackupScheduler`** (`de.lino.cloud.extensions`) - the actual job, entirely self-contained (no `cloud-driver-api`/`cloud-driver-plugin` contract of its own, the same "concrete infrastructure, not a swappable behavior" reasoning as `EntityDatabaseClient`): determine tables -> parallel, keyset-paginated exports per table -> archive without re-compressing -> place -> rotate old archives.

## Performance / concurrency characteristics

- **Keyset pagination, not `LIMIT`/`OFFSET`.** Each page is fetched via `WHERE id > ? ORDER BY id LIMIT ?`, whose cost does not grow with how much has already been read - unlike `OFFSET`, which the database must still scan past. At most `batchSize` (default 500) rows are held in memory per table at any point, regardless of table size.
- **Its own, dedicated `SQLExecution` connection pool**, deliberately not the running application's own pool - an hours-long backup run should never compete with production traffic for connections.
- **Bounded parallel table export.** Tables are exported concurrently over a fixed-size thread pool sized to `tableParallelism` (default: `clamp(availableProcessors(), 1, 4)`), so multiple tables progress at once without unbounded concurrency against the database.
- **Compact binary format, not JSON/Base64.** Per-row output is a simple length-prefixed binary layout (`int idLength, id bytes, int dataLength, data bytes`), GZIP-compressed directly as it's written at `Deflater.BEST_SPEED` - Base64 would have inflated the already-binary `BYTEA` content by roughly a third for no benefit, and `BEST_SPEED` trades a small amount of size for a real reduction in CPU cost at this data volume.
- **Archiving without re-compressing.** The final ZIP archive is built with `Deflater.NO_COMPRESSION` since every per-table file is already GZIP-compressed - re-compressing already-compressed, high-entropy data wastes CPU for essentially no size reduction.
- **Overlapping cycles are prevented**, not merely discouraged: `tick()` uses an `AtomicBoolean` guard (`cycleRunning`) so a new tick that fires while a previous (potentially hours-long) cycle is still running is skipped outright rather than queued or run concurrently.
- **Failures on the scheduler's own thread are caught and logged, never allowed to propagate.** `tick()` wraps `runBackupCycle()` in a broad `try`/`catch (Throwable)` - an uncaught exception on a `ScheduledExecutorService`'s own thread would otherwise silently cancel every future tick for the life of the process.

## Data handling and safety

- Retention/rotation (`enforceRetention`) deletes the oldest archives once more than `retainedBackups` (default 7) exist, so daily 150-200GB cycles don't fill the disk within a few days.
- The staging directory (`.staging`, a sibling of the final archive location) is always cleaned up in a `finally` block after each cycle, best-effort, even on failure - an orphaned staging directory does not block the next cycle from proceeding.
- Table/file names are defensively sanitized/quoted before being interpolated into SQL or used as file names (`quoteIdentifier`, `sanitizeFileName`) - table names come from `information_schema.tables`, not user input, but this is still a real SQL-injection-shaped surface if that assumption ever changes.
- This backup job reads raw, already-encrypted `BYTEA` content directly off the database - it never needs to (and does not) decrypt anything; the exported archive is exactly as sensitive as the live database itself and should be protected accordingly (this module does not itself encrypt the archive).

## Scalability

Purpose-built for the 150-200GB-per-table regime described in its own class Javadoc: memory use per table export is bounded to one page (`batchSize` rows) regardless of table size, and the class is explicit that `listTables()` deliberately goes through `SQLExecution` directly rather than `DatabaseProvider`/`SQLDatabaseSection`, since the latter load an entire table into an in-memory cache on section creation - exactly the problem this class exists to avoid. Currently tailored to PostgreSQL only (`information_schema.tables`); supporting another SQL dialect would mean extending `listTables()` with that dialect's equivalent query.

## Javadoc conventions

`DatabaseBackupScheduler` was already thoroughly documented in English, Google Java Style Guide-compliant, prior to this pass - no changes were needed there. `CloudBackupExtension` had no Javadoc at all prior to this pass; it now has a class summary and per-method documentation (including the `@throws IllegalStateException` `onLoading()` raises on a missing/malformed `postgres-database.json`) matching the conventions used elsewhere in this tree.
