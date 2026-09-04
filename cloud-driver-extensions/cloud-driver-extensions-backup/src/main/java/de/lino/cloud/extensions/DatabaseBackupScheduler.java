package de.lino.cloud.extensions;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.utility.UnitParser;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.sql.SQLExecution;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Periodic database backup scheduler that - unlike a {@code pg_dump}-based solution - runs
 * exclusively through {@code database-driver-v2}'s {@link SQLExecution}, as requested. Runs
 * on its own daemon thread, in the same style as {@code PendingUploadScheduler}.
 *
 * <p><b>Important architectural note:</b> {@link SQLExecution#executeQuery} is built as a
 * parameterized query helper - without a JDBC fetch size/cursor set, the Postgres driver loads
 * a {@code SELECT * FROM table} completely into client memory by default before even the first
 * row is processed. At 150-200GB in individual tables (partly due to file uploads stored as
 * {@code BYTEA}), that would be a guaranteed OutOfMemoryError. This class works around that
 * without having to touch {@link SQLExecution} itself, via <b>keyset pagination</b>:
 * {@code WHERE id > ? ORDER BY id LIMIT ?} instead of {@code LIMIT/OFFSET} (whose cost grows
 * linearly with the offset) - each page therefore stays bounded to at most {@code batchSize}
 * rows in memory, regardless of table size.
 *
 * <p><b>Further scalability decisions:</b>
 * <ul>
 *     <li><b>Its own, dedicated {@link SQLExecution} pool:</b> this class opens its own
 *     connection instead of reusing the running application's pool - an hours-long backup run
 *     should not take connections away from production traffic.</li>
 *     <li><b>Parallel table exports</b> over a bounded thread pool ({@code tableParallelism}),
 *     so multiple tables are exported concurrently, but never more than the connection pool can
 *     handle.</li>
 *     <li><b>Compact binary format instead of JSON/Base64:</b> {@code data} is {@code BYTEA}; a
 *     Base64 encoding would inflate the 200GB by roughly 33%. Instead, a simple
 *     length-prefixed binary format ({@code id length, id, data length, data}), compressed with
 *     GZIP directly as it is written.</li>
 *     <li><b>Archiving without re-compressing</b> ({@link Deflater#NO_COMPRESSION}), since the
 *     individual per-table files are already GZIP-compressed.</li>
 *     <li><b>Retention/rotation</b>, so the disk doesn't fill up within a few days at daily
 *     150-200GB cycles.</li>
 * </ul>
 *
 * <p>Currently tailored to PostgreSQL (table list via {@code information_schema.tables}); for
 * other SQL dialects supported by {@code database-driver-v2}, {@link #listTables()} would need
 * to be extended with the matching query, analogous to {@code SQLDatabaseProvider.getPattern}.
 */
public final class DatabaseBackupScheduler {

    /** Formats a backup cycle's start time into the timestamp used in its archive/staging directory name. */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Default interval between backup cycles, used by {@link #start()}. */
    private static final Duration DEFAULT_PERIOD = Duration.ofDays(3);
    /** Prefix every archive file and staging directory name is built with, so {@link #enforceRetention()} can find them among other files in {@link #backupRootDirectory}. */
    private static final String BACKUP_PREFIX = "cloud-driver-backup-";
    /** File extension of a finished, archived backup. */
    private static final String ARCHIVE_SUFFIX = ".zip";
    /** File extension of one table's exported, GZIP-compressed, length-prefixed binary file inside the staging directory. */
    private static final String TABLE_FILE_SUFFIX = ".bin.gz";

    /** This scheduler's own, dedicated connection pool - see the class Javadoc for why it never shares the running application's pool. */
    private final SQLExecution sqlExecution;
    /** Directory finished archives are placed into, and where {@link #enforceRetention()} looks for old ones to delete. */
    private final Path backupRootDirectory;
    /** Working directory ({@code backupRootDirectory/.staging}) each cycle's per-table export files are written into before archiving. */
    private final Path stagingDirectory;
    /** Maximum number of rows fetched per keyset page; bounds the memory footprint of a single page regardless of table size. */
    private final int batchSize;
    /** Number of tables exported concurrently during one backup cycle. */
    private final int tableParallelism;
    /** Number of most recently created archives {@link #enforceRetention()} keeps; older ones are deleted. */
    private final int retainedBackups;

    /** Single-thread scheduler this instance's periodic ticks run on. */
    private final ScheduledExecutorService scheduledExecutorService;

    /** Prevents a new tick from starting while a backup (potentially hours long) is still running. */
    private final AtomicBoolean cycleRunning = new AtomicBoolean(false);

    /** The active schedule, or {@code null} while stopped. */
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * @param credentials         connection details of the PostgreSQL database to back up; a
     *                            dedicated {@link SQLExecution} pool is deliberately built for
     *                            it, see the class Javadoc
     * @param backupRootDirectory target directory the finished archives are placed in
     */
    public DatabaseBackupScheduler(@NotNull final Credentials credentials, @NotNull final Path backupRootDirectory) {
        this(
                credentials, backupRootDirectory,
                500,                                                     // batchSize: rows per keyset page
                Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 4), // tableParallelism
                7                                                        // retainedBackups
        );
    }

    /**
     * @param credentials         connection details of the PostgreSQL database to back up
     * @param backupRootDirectory target directory the finished archives are placed in
     * @param batchSize           maximum number of rows per keyset page; bounds the memory footprint of a single page
     * @param tableParallelism    number of tables exported concurrently
     * @param retainedBackups     number of most recently created archives to keep; older ones are deleted
     */
    public DatabaseBackupScheduler(
            @NotNull final Credentials credentials, @NotNull final Path backupRootDirectory,
            final int batchSize, final int tableParallelism, final int retainedBackups
    ) {
        Objects.requireNonNull(credentials, "@DatabaseBackupScheduler: credentials cannot be null");

        this.sqlExecution = new SQLExecution(DatabaseType.POSTGRES_SQL, credentials);
        this.backupRootDirectory = Objects.requireNonNull(backupRootDirectory, "@DatabaseBackupScheduler: backupRootDirectory cannot be null");
        this.stagingDirectory = backupRootDirectory.resolve(".staging");
        this.batchSize = Math.max(50, batchSize);
        this.tableParallelism = Math.max(1, tableParallelism);
        this.retainedBackups = Math.max(1, retainedBackups);
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("postgres-backup-scheduler"));
    }

    /**
     * Starts the scheduler with the default interval ({@link #DEFAULT_PERIOD}). The first run
     * happens immediately (initialDelay = 0).
     */
    public synchronized void start() {
        start(DEFAULT_PERIOD);
    }

    /**
     * Starts the scheduler with a custom interval. Calling this again while the scheduler is
     * already running is a no-op - call {@link #stop()} first to change the interval.
     *
     * @param period how often a backup cycle is triggered
     */
    public synchronized void start(@NotNull final Duration period) {

        Objects.requireNonNull(period, "@DatabaseBackupScheduler.start: period cannot be null");

        if (this.scheduledFuture != null) {
            return;
        }

        this.scheduledFuture = this.scheduledExecutorService.scheduleAtFixedRate(
                this::tick, 0L, period.toMillis(), TimeUnit.MILLISECONDS
        );

    }

    /**
     * Stops periodically triggering new cycles. A backup cycle already in progress is not
     * aborted. The executor and the {@link SQLExecution} pool stay alive, so
     * {@link #start(Duration)} can be called again later.
     */
    public synchronized void stop() {

        if (this.scheduledFuture == null) {
            return;
        }

        this.scheduledFuture.cancel(false);
        this.scheduledFuture = null;

    }

    /**
     * {@link #stop()}, then shuts the executor down for good and closes this instance's own
     * {@link SQLExecution} connection pool - for a full shutdown (e.g. from {@code CloudBootstrap}'s
     * shutdown hook), not for merely pausing.
     */
    public void shutdown() {

        stop();
        this.scheduledExecutorService.shutdown();

        try {
            if (!this.scheduledExecutorService.awaitTermination(60, TimeUnit.SECONDS)) {
                this.scheduledExecutorService.shutdownNow();
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.scheduledExecutorService.shutdownNow();
        }

        this.sqlExecution.shutdown();

    }

    /**
     * Scheduled entry point invoked on every tick. Skips this tick if a previous cycle is still
     * running, otherwise runs {@link #runBackupCycle()} and logs (rather than propagates) any
     * failure - this runs on the scheduler's own thread, and an uncaught exception here would
     * silently stop every future tick from ever firing again.
     */
    private void tick() {

        if (!this.cycleRunning.compareAndSet(false, true)) {
            CloudDriver.getInstance().getTerminal().displayApproved("Backup tick skipped since the there is a remaining cycle running");
            return;
        }

        try {
            runBackupCycle();
        } catch (final Throwable throwable) {
            CloudDriver.getInstance().getLogger().warning("Backup cycle failed: &c" + throwable.getMessage());
            throwable.printStackTrace();
        } finally {
            this.cycleRunning.set(false);
        }

    }

    /**
     * One full cycle: determine tables -> parallel, keyset-paginated exports per table ->
     * archiving (without re-compressing) -> placement -> rotation of old archives.
     *
     * @throws IOException if creating a working directory, exporting a table, archiving, or
     *                      rotating old archives fails
     */
    private void runBackupCycle() throws IOException {

        Files.createDirectories(this.backupRootDirectory);
        Files.createDirectories(this.stagingDirectory);

        final String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        final Path dumpDirectory = this.stagingDirectory.resolve(BACKUP_PREFIX + timestamp);
        Files.createDirectories(dumpDirectory);
        final Path finalArchive = this.backupRootDirectory.resolve(BACKUP_PREFIX + timestamp + ARCHIVE_SUFFIX);

        try {

            final List<String> tables = listTables();
            CloudDriver.getInstance().getTerminal().displayApproved("Saving &b%s &7tables (Parallelism: &b%s&7) (Batchsize: &b%s&7)", tables.size(), this.tableParallelism, this.batchSize);

            exportTablesInParallel(tables, dumpDirectory);

            CloudDriver.getInstance().getTerminal().displayApproved("Export data finished. Archiving data...");
            archiveDumpDirectory(dumpDirectory, finalArchive);
            CloudDriver.getInstance().getTerminal().displayApproved("Backup stored under '&b%s&7' (&b%s&7)", finalArchive, UnitParser.parseByteUnit(Files.size(finalArchive)));

            enforceRetention();

        } finally {
            deleteRecursivelyQuietly(dumpDirectory);
        }

    }

    /**
     * Determines all tables in the {@code public} schema via {@link #sqlExecution}, rather than
     * via {@code DatabaseProvider}/{@code SQLDatabaseSection} - the latter load the entire table
     * into an in-memory cache when a section is created, which at 150-200GB is exactly the
     * problem this class exists to avoid.
     *
     * @return all table names in the {@code public} schema
     */
    private List<String> listTables() {
        return this.sqlExecution.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                resultSet -> {
                    final List<String> tables = new ArrayList<>();
                    try {
                        while (resultSet.next()) {
                            tables.add(resultSet.getString("table_name"));
                        }
                    } catch (final SQLException exception) {
                        throw new RuntimeException(exception);
                    }
                    return tables;
                },
                List.of()
        );
    }

    /**
     * Exports {@code tables} in parallel (bounded to {@link #tableParallelism} concurrent
     * tables), each into its own file under {@code dumpDirectory}.
     *
     * @throws IOException if a table export fails; the others still run to completion, the
     *                      first failure encountered is thrown after {@link CompletableFuture#allOf}
     */
    private void exportTablesInParallel(final List<String> tables, final Path dumpDirectory) throws IOException {

        final ExecutorService tableExecutor = Executors.newFixedThreadPool(
                Math.clamp(tables.size(), 1, this.tableParallelism),
                daemonThreadFactory("postgres-backup-table-worker")
        );

        try {

            final List<CompletableFuture<Void>> exports = tables.stream()
                    .map(table -> CompletableFuture.runAsync(() -> exportTableUnchecked(table, dumpDirectory), tableExecutor))
                    .toList();

            CompletableFuture.allOf(exports.toArray(new CompletableFuture[0])).join();

        } catch (final CompletionException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) throw ioException;
            throw new IOException("@DatabaseBackupScheduler.exportTablesInParallel: Export failed due ", cause != null ? cause : exception);
        } finally {
            tableExecutor.shutdown();
        }

    }

    /**
     * Unchecked wrapper around {@link #exportTable(String, Path)} so it can be submitted as a
     * {@link Runnable} to {@link CompletableFuture#runAsync}; wraps a thrown {@link IOException}
     * into a {@link CompletionException} so {@link #exportTablesInParallel} can unwrap and
     * rethrow it.
     *
     * @param table         the table name
     * @param dumpDirectory the directory the exported file is written into
     */
    private void exportTableUnchecked(final String table, final Path dumpDirectory) {
        try {
            exportTable(table, dumpDirectory.resolve(sanitizeFileName(table) + TABLE_FILE_SUFFIX));
        } catch (final IOException exception) {
            throw new CompletionException(exception);
        }
    }

    /**
     * Fully exports a single table, page by page via keyset pagination
     * ({@code WHERE id > ? ORDER BY id LIMIT ?}), streamed directly GZIP-compressed to
     * {@code outputFile}. At any point in time, at most one page ({@link #batchSize} rows) is
     * held in memory - regardless of how large the table is overall.
     *
     * <p>Per-row file format: {@code int idLength, byte[] id (UTF-8), int dataLength,
     * byte[] data} - deliberately no JSON/Base64, so the already-binary {@code BYTEA} values
     * aren't inflated by roughly 33%.
     *
     * @param table      the table name
     * @param outputFile the target file; overwritten if it already exists
     * @throws IOException if writing the output file or fetching a page fails
     */
    private void exportTable(final String table, final Path outputFile) throws IOException {

        long totalRows = 0;
        String lastId = null;

        try (
                OutputStream fileOutputStream = Files.newOutputStream(outputFile);
                GZIPOutputStream gzipOutputStream = new BestSpeedGzipOutputStream(fileOutputStream);
                DataOutputStream dataOutputStream = new DataOutputStream(new BufferedOutputStream(gzipOutputStream, 1 << 16))
        ) {

            while (true) {

                final List<Row> batch = fetchBatch(table, lastId);
                if (batch.isEmpty()) break;

                for (final Row row : batch) {

                    final byte[] idBytes = row.id().getBytes(StandardCharsets.UTF_8);
                    final byte[] data = row.data() != null ? row.data() : new byte[0];

                    dataOutputStream.writeInt(idBytes.length);
                    dataOutputStream.write(idBytes);
                    dataOutputStream.writeInt(data.length);
                    dataOutputStream.write(data);

                }

                lastId = batch.get(batch.size() - 1).id();
                totalRows += batch.size();

                if (batch.size() < this.batchSize) break; // last, not-full page -> done

            }

        }

        CloudDriver.getInstance().getTerminal().displayApproved("Table '&b%s&7': &c%s &7rows exported to &e%s", table, totalRows, outputFile.getFileName());

    }

    /**
     * Loads the next page of {@code table}, up to {@link #batchSize} rows, starting after
     * {@code afterId} (exclusive), sorted ascending by {@code id} - keyset instead of
     * {@code LIMIT/OFFSET} pagination, so every page stays equally fast regardless of how much
     * data has already been read.
     *
     * @param table   the table name
     * @param afterId the last id read, or {@code null} for the first page
     * @return up to {@link #batchSize} rows, in ascending id order
     */
    private List<Row> fetchBatch(final String table, final String afterId) {

        final String quotedTable = quoteIdentifier(table);

        final String query = afterId == null
                ? "SELECT id, data FROM " + quotedTable + " ORDER BY id ASC LIMIT ?"
                : "SELECT id, data FROM " + quotedTable + " WHERE id > ? ORDER BY id ASC LIMIT ?";

        final Object[] parameters = afterId == null
                ? new Object[]{this.batchSize}
                : new Object[]{afterId, this.batchSize};

        return this.sqlExecution.executeQuery(query, resultSet -> {

            final List<Row> rows = new ArrayList<>();

            try {
                while (resultSet.next()) {
                    rows.add(new Row(resultSet.getString("id"), resultSet.getBytes("data")));
                }
            } catch (final SQLException exception) {
                throw new RuntimeException(exception);
            }

            return rows;

        }, List.of(), parameters);

    }

    /**
     * Archives {@code dumpDirectory} losslessly into a single ZIP file, without re-compressing
     * the per-table files that are already GZIP-compressed ({@link Deflater#NO_COMPRESSION}).
     *
     * @param dumpDirectory the directory holding the per-table files from {@link #exportTable}
     * @param output        the target file; overwritten if it already exists
     * @throws IOException if deleting an existing target file, walking {@code dumpDirectory}, or writing the archive fails
     */
    private void archiveDumpDirectory(final Path dumpDirectory, final Path output) throws IOException {

        if (Files.exists(output)) {
            Files.delete(output);
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {

            zip.setLevel(Deflater.NO_COMPRESSION);

            try (Stream<Path> files = Files.walk(dumpDirectory).filter(Files::isRegularFile)) {

                for (final Path file : (Iterable<Path>) files::iterator) {

                    final String entryName = dumpDirectory.relativize(file).toString().replace('\\', '/');

                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zip);
                    zip.closeEntry();

                }

            }

        }

    }

    /**
     * Deletes the oldest archives in {@link #backupRootDirectory}, so that at most
     * {@link #retainedBackups} remain.
     *
     * @throws IOException if listing {@link #backupRootDirectory} fails
     */
    private void enforceRetention() throws IOException {

        try (Stream<Path> files = Files.list(this.backupRootDirectory)) {

            final List<Path> archives = files
                    .filter(path -> path.getFileName().toString().startsWith(BACKUP_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(ARCHIVE_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())) // timestamp in the name -> lexicographic = chronological
                    .toList();

            final int toDelete = archives.size() - this.retainedBackups;

            for (int i = 0; i < toDelete; i++) {
                final Path archive = archives.get(i);
                Files.deleteIfExists(archive);
                CloudDriver.getInstance().getTerminal().displayApproved("Old archive deleted: '&b%s&7'", archive);
            }

        }

    }

    /**
     * A raw row pair from a table, as read by {@link #fetchBatch}.
     *
     * @param id   the primary key id
     * @param data the raw {@code BYTEA} content of the row
     */
    private record Row(String id, byte[] data) {
    }

    /**
     * A {@link GZIPOutputStream} whose internal {@link Deflater} is set to
     * {@link Deflater#BEST_SPEED} immediately after construction, instead of the JDK default
     * {@code DEFAULT_COMPRESSION} - at 150-200GB of raw data, a noticeable difference in CPU
     * usage, with no meaningful size penalty over higher compression levels given the data is
     * already binary, low-redundancy BYTEA content.
     */
    private static final class BestSpeedGzipOutputStream extends GZIPOutputStream {

        /**
         * Wraps {@code out} in a 64KB-buffered {@link GZIPOutputStream} and forces its internal
         * {@link Deflater} to {@link Deflater#BEST_SPEED}.
         *
         * @param out the stream to write compressed bytes to
         * @throws IOException if the superclass constructor fails to write the GZIP header
         */
        BestSpeedGzipOutputStream(final OutputStream out) throws IOException {
            super(out, 1 << 16);
            this.def.setLevel(Deflater.BEST_SPEED);
        }
    }

    /**
     * Quotes {@code identifier} as a safe SQL identifier (double-quoted, with embedded double
     * quotes escaped), so a table name can be interpolated directly into a query.
     *
     * @param identifier the raw identifier to quote
     * @return the quoted identifier
     */
    private static String quoteIdentifier(final String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Replaces every character that isn't safe for a file name with {@code _}, so a table name
     * can be used directly as (part of) a file name.
     *
     * @param name the raw name to sanitize
     * @return the sanitized name
     */
    private static String sanitizeFileName(final String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Best-effort recursive delete of {@code directory} and everything under it; failures to
     * delete an individual file are ignored (see {@link #runBackupCycle()}'s {@code finally}
     * block, which calls this to clean up the staging directory).
     *
     * @param directory the directory to delete; a no-op if it doesn't exist
     */
    private static void deleteRecursivelyQuietly(final Path directory) {

        if (Files.notExists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {

            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // Best-effort cleanup; an orphaned staging directory does not block the next cycle.
                }
            });

        } catch (final IOException ignored) {
            // see above
        }

    }

    /**
     * Builds a {@link ThreadFactory} producing named daemon threads, so this class's background
     * threads never keep the JVM alive on their own.
     *
     * @param name the thread name to use
     * @return the thread factory
     */
    private static ThreadFactory daemonThreadFactory(final String name) {
        return runnable -> {
            final Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

}
