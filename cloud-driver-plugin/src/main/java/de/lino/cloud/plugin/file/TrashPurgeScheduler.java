package de.lino.cloud.plugin.file;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Permanently purges every soft-deleted {@link StoredFileOwnership} row (and the {@code
 * StoredFile} content it points at) and {@link Folder} once it has sat in the trash longer than
 * {@link #retentionPeriod} - the "empty the trash" counterpart to {@code
 * CloudUserService#deleteFile}/{@code #deleteFolder}'s soft delete. Modeled directly on {@link
 * PendingUploadScheduler}'s shape: its own daemon thread, ticking on a fixed period, doing
 * nothing on a tick with nothing past the window.
 *
 * <p><b>Deliberately not wired into {@code CloudBootstrap}, or started anywhere automatically -
 * a real, explicit decision</b> (see {@code architecture/SERVICES.md} item 3, and this class's
 * mention in {@code CLAUDE.md}'s "Folder organization" section). An operator must explicitly
 * construct this scheduler with a retention window they have actually chosen and call {@link
 * #start(Duration)} themselves. Getting that window wrong (too short) causes real, permanent,
 * silent data loss the moment this scheduler starts ticking - unlike most bugs in this codebase,
 * this one cannot be caught and fixed after the fact, since irreversibly deleting expired trash
 * is this class's entire job. Wire it in only once that trade-off has been made deliberately -
 * e.g. from {@code CloudBootstrap.initiateCloudDriver()}, alongside where {@link
 * de.lino.cloud.plugin.factory.container.FactoryContainer} is built, once a retention window is
 * chosen.
 *
 * <p>Scans every {@link StoredFileOwnership}/{@link Folder} row across <em>every</em> account
 * directly via {@link DataFactory#getEntities} - unlike {@code CloudUserService}, which is
 * scoped to one {@code authUserId} at a time, this needs to sweep every account in one pass, so
 * it talks to {@link DataFactory}/{@link FileFactory} directly rather than through {@code
 * CloudUserService} (which has no "every account" primitive of its own). A per-item failure
 * (e.g. one already-gone row) is swallowed and left for the next tick, the same "one failure
 * doesn't abort the batch" philosophy {@link PendingUploadScheduler#retryUpload} already uses -
 * appropriate here since a tick runs unattended and a stack trace has nowhere useful to surface
 * to.
 */
public final class TrashPurgeScheduler {

    /**
     * {@code configuration.json} key an operator opting into this scheduler can set the
     * retention window under - read only by {@link #withConfiguredRetention}, never by this
     * class's own constructor (which always takes an explicit {@link Duration}, the same
     * "constructor takes collaborators/config directly, a caller resolves them" shape {@link
     * PendingUploadScheduler} already uses).
     */
    private static final String RETENTION_DAYS_CONFIG_KEY = "trash-retention-days";

    /** Default retention window if {@link #RETENTION_DAYS_CONFIG_KEY} is unset - 30 days. */
    private static final long DEFAULT_RETENTION_DAYS = 30L;

    /** Purged files' content is removed through this; also backs {@link #dataFactory}'s own generic CRUD for ownership/folder rows. */
    private final DataFactory dataFactory;
    /** Removes a purged {@code StoredFile}'s own content. */
    private final FileFactory fileFactory;
    /** How long a soft-deleted row must have sat in the trash before this scheduler permanently removes it. */
    private final Duration retentionPeriod;
    /** Single-thread, daemon-backed executor driving the tick schedule. */
    private final ScheduledExecutorService scheduledExecutorService;

    /** Guards against a tick starting a second, concurrent purge while one is still running. */
    private final AtomicBoolean purging = new AtomicBoolean(false);

    /** The active tick schedule, or {@code null} while stopped. */
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * @param dataFactory removes purged ownership/folder rows, and looks up/updates each owner's usage total
     * @param fileFactory removes a purged file's own content
     * @param retentionPeriod how long a row must have sat in the trash before it is eligible for permanent removal
     * @throws NullPointerException if any argument is {@code null}
     */
    public TrashPurgeScheduler(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory,
                                @NotNull final Duration retentionPeriod) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@TrashPurgeScheduler: dataFactory cannot be null");
        this.fileFactory = Asserts.requireNonNull(fileFactory, "@TrashPurgeScheduler: fileFactory cannot be null");
        this.retentionPeriod = Asserts.requireNonNull(retentionPeriod, "@TrashPurgeScheduler: retentionPeriod cannot be null");
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    /**
     * Starts ticking every {@code tickPeriod}, first tick after one {@code tickPeriod} has
     * elapsed. Calling this again while already running is a no-op - call {@link #stop()} first
     * to change the period.
     *
     * @param tickPeriod how often to sweep for expired trash
     * @throws NullPointerException if {@code tickPeriod} is {@code null}
     */
    public synchronized void start(@NotNull final Duration tickPeriod) {
        Asserts.requireNonNull(tickPeriod, "@TrashPurgeScheduler.start: tickPeriod cannot be null");
        if (this.scheduledFuture != null) {
            return;
        }
        this.scheduledFuture = this.scheduledExecutorService.scheduleWithFixedDelay(
                this::tick, tickPeriod.toMillis(), tickPeriod.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    /**
     * Convenience factory reading the retention window from {@code configuration.json}'s {@value
     * #RETENTION_DAYS_CONFIG_KEY} key (via {@link CloudDriver#getConfiguration()}), defaulting to
     * {@value #DEFAULT_RETENTION_DAYS} days if unset - the same {@link JsonDocument#contains}-first
     * pattern {@code CloudUser#resolveMaxBytesToUpload} already uses for its own optional
     * configuration key. Does <b>not</b> call {@link #start(Duration)} - constructing the instance
     * is separate from starting it, and this class is never started automatically regardless (see
     * this class's own top-level Javadoc).
     *
     * @param dataFactory removes purged ownership/folder rows, and looks up/updates each owner's usage total
     * @param fileFactory removes a purged file's own content
     * @return a new, not-yet-started {@code TrashPurgeScheduler} using the configured (or default) retention window
     */
    @NotNull
    public static TrashPurgeScheduler withConfiguredRetention(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory) {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        final long retentionDays = configuration.contains(RETENTION_DAYS_CONFIG_KEY)
                ? configuration.getLong(RETENTION_DAYS_CONFIG_KEY)
                : DEFAULT_RETENTION_DAYS;
        return new TrashPurgeScheduler(dataFactory, fileFactory, Duration.ofDays(retentionDays));
    }

    /**
     * Stops ticking. The underlying executor stays alive, so {@link #start(Duration)} can be
     * called again afterward. A no-op if not currently running.
     */
    public synchronized void stop() {
        if (this.scheduledFuture != null) {
            this.scheduledFuture.cancel(false);
            this.scheduledFuture = null;
        }
    }

    /**
     * {@link #stop()}s and permanently shuts down the underlying executor - call this when the
     * scheduler itself is no longer needed, not merely to pause it.
     */
    public void shutdown() {
        stop();
        this.scheduledExecutorService.shutdown();
    }

    /** One scheduled sweep, guarded by {@link #purging} against overlapping with a still-running previous tick. */
    private void tick() {
        if (!this.purging.compareAndSet(false, true)) {
            return;
        }
        try {
            purgeExpiredFiles();
            purgeExpiredFolders();
        } finally {
            this.purging.set(false);
        }
    }

    /** @return the epoch-millis cutoff - a row deleted before this instant is eligible for permanent removal */
    private long cutoffEpochMillis() {
        return System.currentTimeMillis() - this.retentionPeriod.toMillis();
    }

    /** Finds every {@link StoredFileOwnership} row past {@link #retentionPeriod} and permanently removes it via {@link #purgeFile}. */
    private void purgeExpiredFiles() {
        final long cutoff = cutoffEpochMillis();
        final List<StoredFileOwnership> expired;
        try {
            expired = this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getDeletedAtEpochMillis() != null && ownership.getDeletedAtEpochMillis() < cutoff)
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            return; // best-effort - try again next tick rather than letting one failed scan kill the whole sweep
        }
        expired.forEach(this::purgeFile);
    }

    /**
     * Permanently removes {@code ownership}'s own {@code StoredFile} content, then the ownership
     * row itself, then decrements the owner's usage total (if the row's size is known) - the
     * final, irreversible step {@code CloudUserService#deleteFile}'s soft delete deferred.
     */
    private void purgeFile(final StoredFileOwnership ownership) {
        try {
            this.fileFactory.delete(ownership.getStoredFileId());
        } catch (final DatabaseClientException alreadyGoneOrOther) {
            // proceed to drop the ownership row regardless - a missing StoredFile row (e.g. a
            // previous tick partially completed) shouldn't leave a stale ownership row forever
        }
        try {
            this.dataFactory.delete(
                    StoredFileOwnership.compositeKey(ownership.getAuthUserId(), ownership.getStoredFileId()),
                    StoredFileOwnership.class
            );
        } catch (final DatabaseClientException alreadyGone) {
            return; // nothing left to account for if the ownership row was already gone
        }
        if (ownership.hasMetadata()) {
            decrementUsage(ownership.getAuthUserId(), ownership.getSizeBytes());
        }
    }

    /** Mirrors {@code CloudUserService#updateCloudUserBytesUsage}'s clamped-at-zero decrement, without going through that per-account-scoped class. */
    private void decrementUsage(final String authUserId, final long bytes) {
        final Optional<CloudUser> cloudUser;
        try {
            cloudUser = this.dataFactory.findById(authUserId, CloudUser.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            return; // best-effort accounting only - a missed decrement here doesn't undo the purge itself
        }
        if (cloudUser.isEmpty()) {
            return;
        }
        final CloudUser existing = cloudUser.get();
        existing.setCurrentUploadedBytes(Math.max(0, existing.getCurrentUploadedBytes() - bytes));
        try {
            this.dataFactory.update(existing);
        } catch (final DatabaseClientException | KeyWrapException e) {
            // best-effort accounting only - see above
        }
    }

    /** Finds every {@link Folder} past {@link #retentionPeriod} and permanently deletes it. */
    private void purgeExpiredFolders() {
        final long cutoff = cutoffEpochMillis();
        final List<Folder> expired;
        try {
            expired = this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getDeletedAtEpochMillis() != null && folder.getDeletedAtEpochMillis() < cutoff)
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            return;
        }
        for (final Folder folder : expired) {
            try {
                this.dataFactory.delete(folder.getFolderId(), Folder.class);
            } catch (final DatabaseClientException alreadyGone) {
                // already removed by a previous tick - nothing left to do
            }
        }
    }

    /**
     * Builds a {@link ThreadFactory} producing a single, named, daemon thread ({@code
     * "trash-purge-scheduler"}) for {@link #scheduledExecutorService}, so this scheduler never by
     * itself keeps the JVM alive - same reasoning as {@link PendingUploadScheduler#daemonThreadFactory}.
     */
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "trash-purge-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }

}
