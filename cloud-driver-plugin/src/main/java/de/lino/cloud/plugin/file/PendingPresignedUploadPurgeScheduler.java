package de.lino.cloud.plugin.file;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.auth.pending.PendingPresignedUpload;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closes a real, previously-accepted gap in presigned direct-to-client uploads: {@link
 * CloudUserService#beginPresignedUpload} used to persist nothing at all, so a client that
 * abandoned the upload after receiving its ticket (crash, closed app, network failure, or simply
 * never calling {@link CloudUserService#completePresignedUpload}) left its already-uploaded S3
 * object permanently orphaned - nothing anywhere ever tracked or cleaned it up. {@code
 * beginPresignedUpload} now persists a {@link PendingPresignedUpload} row per issued ticket, and
 * this scheduler periodically sweeps for ones that were never confirmed within {@link
 * #retentionPeriod}, deleting the orphaned S3 object (if any) and the tracking row together.
 *
 * <p>Modeled on {@link TrashPurgeScheduler}'s exact shape (its own daemon thread, ticking on a
 * fixed period, one {@link AtomicBoolean} guard against overlapping ticks) - but, unlike that
 * class, this one is safe to wire in and start automatically without an operator first choosing a
 * retention window. A wrong (too short) {@link TrashPurgeScheduler} retention window permanently
 * destroys a user's live data; a wrong (too short) retention window here can at worst make this
 * scheduler delete an S3 object out from under an upload whose {@link
 * CloudUserService#completePresignedUpload} call is unusually delayed - and even that failure mode
 * is bounded and non-catastrophic: that pending call would simply fail the same
 * "no object uploaded yet" way it already fails for a genuinely-never-uploaded ticket (see {@link
 * CloudUserService#completePresignedUpload}'s own {@code headObjectContentLength} check), and the
 * user retries the upload. The one real safety property this class does guarantee unconditionally
 * is that it <b>never</b> deletes an S3 object once a real {@link StoredFile} exists under that
 * same id - see {@link #purgeIfAbandoned} for the check that makes this true regardless of the
 * configured retention window or of {@link CloudUserService#completePresignedUpload}'s own
 * best-effort tracking-row cleanup ever failing.
 */
public final class PendingPresignedUploadPurgeScheduler {

    /**
     * {@code configuration.json} key an operator can override this scheduler's retention window
     * under - read only by {@link #withConfiguredRetention}, never by this class's own
     * constructor, the same "constructor takes config directly, a caller resolves it" shape
     * {@link TrashPurgeScheduler#RETENTION_DAYS_CONFIG_KEY} already uses.
     */
    private static final String RETENTION_HOURS_CONFIG_KEY = "presigned-upload-ticket-retention-hours";

    /**
     * Default retention window if {@link #RETENTION_HOURS_CONFIG_KEY} is unset - 6 hours.
     * Comfortably longer than the presigned URL's own 15-minute expiry (see {@code
     * CloudUserService#PRESIGNED_URL_EXPIRY}) and than any realistic delay between a successful S3
     * upload and the client's follow-up {@link CloudUserService#completePresignedUpload} call,
     * while still cleaning up an abandoned ticket the same day it was issued.
     */
    private static final long DEFAULT_RETENTION_HOURS = 6L;

    /** Removes/scans {@link PendingPresignedUpload} rows, and checks whether a real {@link StoredFile} now exists under a ticket's id. */
    private final DataFactory dataFactory;
    /** Deletes an abandoned ticket's orphaned S3 object. */
    private final ObjectStorageService objectStorageService;
    /** How long a {@link PendingPresignedUpload} row may sit unconfirmed before being treated as abandoned. */
    private final Duration retentionPeriod;
    /** Single-thread, daemon-backed executor driving the tick schedule. */
    private final ScheduledExecutorService scheduledExecutorService;

    /** Guards against a tick starting a second, concurrent sweep while one is still running. */
    private final AtomicBoolean purging = new AtomicBoolean(false);

    /** The active tick schedule, or {@code null} while stopped. */
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * @param dataFactory scans/removes {@link PendingPresignedUpload} rows, and confirms whether a real {@link StoredFile} now exists under a given id
     * @param objectStorageService deletes an abandoned ticket's orphaned S3 object
     * @param retentionPeriod how long a ticket may sit unconfirmed before this scheduler treats it as abandoned
     * @throws NullPointerException if any argument is {@code null}
     */
    public PendingPresignedUploadPurgeScheduler(@NotNull final DataFactory dataFactory, @NotNull final ObjectStorageService objectStorageService,
                                                 @NotNull final Duration retentionPeriod) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@PendingPresignedUploadPurgeScheduler: dataFactory cannot be null");
        this.objectStorageService = Asserts.requireNonNull(objectStorageService, "@PendingPresignedUploadPurgeScheduler: objectStorageService cannot be null");
        this.retentionPeriod = Asserts.requireNonNull(retentionPeriod, "@PendingPresignedUploadPurgeScheduler: retentionPeriod cannot be null");
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    /**
     * Convenience factory reading the retention window from {@code configuration.json}'s {@value
     * #RETENTION_HOURS_CONFIG_KEY} key (via {@link CloudDriver#getConfiguration()}), defaulting to
     * {@value #DEFAULT_RETENTION_HOURS} hours if unset. Does <b>not</b> call {@link
     * #start(Duration)}.
     *
     * @param dataFactory scans/removes {@link PendingPresignedUpload} rows
     * @param objectStorageService deletes an abandoned ticket's orphaned S3 object
     * @return a new, not-yet-started scheduler using the configured (or default) retention window
     */
    @NotNull
    public static PendingPresignedUploadPurgeScheduler withConfiguredRetention(@NotNull final DataFactory dataFactory,
                                                                                @NotNull final ObjectStorageService objectStorageService) {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        final long retentionHours = configuration.contains(RETENTION_HOURS_CONFIG_KEY)
                ? configuration.getLong(RETENTION_HOURS_CONFIG_KEY)
                : DEFAULT_RETENTION_HOURS;
        return new PendingPresignedUploadPurgeScheduler(dataFactory, objectStorageService, Duration.ofHours(retentionHours));
    }

    /**
     * Starts ticking every {@code tickPeriod}, first tick after one {@code tickPeriod} has
     * elapsed. Calling this again while already running is a no-op - call {@link #stop()} first
     * to change the period.
     *
     * @param tickPeriod how often to sweep for abandoned tickets
     * @throws NullPointerException if {@code tickPeriod} is {@code null}
     */
    public synchronized void start(@NotNull final Duration tickPeriod) {
        Asserts.requireNonNull(tickPeriod, "@PendingPresignedUploadPurgeScheduler.start: tickPeriod cannot be null");
        if (this.scheduledFuture != null) {
            return;
        }
        this.scheduledFuture = this.scheduledExecutorService.scheduleWithFixedDelay(
                this::tick, tickPeriod.toMillis(), tickPeriod.toMillis(), TimeUnit.MILLISECONDS
        );
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
            final long cutoff = System.currentTimeMillis() - this.retentionPeriod.toMillis();
            final List<PendingPresignedUpload> expired;
            try {
                expired = this.dataFactory.getEntities(PendingPresignedUpload.class).stream()
                        .filter(pending -> pending.getCreatedAtEpochMillis() < cutoff)
                        .toList();
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                return; // best-effort - try again next tick rather than letting one failed scan kill the whole sweep
            }
            expired.forEach(this::purgeIfAbandoned);
        } finally {
            this.purging.set(false);
        }
    }

    /**
     * Purges one expired {@link PendingPresignedUpload} row - but only ever deletes its S3 object
     * after confirming no real {@link StoredFile} exists under the same id first. That check is
     * what makes this class safe regardless of the configured retention window: {@link
     * CloudUserService#completePresignedUpload} deletes its own tracking row on success, but only
     * best-effort - if that delete itself happened to fail, this row would otherwise look
     * "abandoned" even though the file it refers to is now a real, live account file. Checking
     * {@link StoredFile} existence directly, rather than trusting the tracking row's own absence,
     * closes that race unconditionally.
     */
    private void purgeIfAbandoned(final PendingPresignedUpload pending) {
        final boolean realFileExists;
        try {
            realFileExists = this.dataFactory.findById(pending.getFileId(), StoredFile.class).isPresent();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            return; // best-effort - leave this row for the next tick rather than risking a wrong delete on an inconclusive lookup
        }
        if (!realFileExists) {
            try {
                this.objectStorageService.deleteObject(pending.getFileId());
            } catch (final ObjectStorageException cleanupFailed) {
                return; // leave the tracking row in place - retry the S3 delete on the next tick
            }
        }
        try {
            this.dataFactory.delete(pending.getFileId(), PendingPresignedUpload.class);
        } catch (final DatabaseClientException alreadyGone) {
            // already removed by a previous tick (or CloudUserService's own best-effort cleanup) - nothing left to do
        }
    }

    /**
     * Builds a {@link ThreadFactory} producing a single, named, daemon thread ({@code
     * "presigned-upload-purge-scheduler"}) for {@link #scheduledExecutorService}, so this
     * scheduler never by itself keeps the JVM alive - same reasoning as {@link
     * TrashPurgeScheduler#daemonThreadFactory}.
     */
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "presigned-upload-purge-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }

}
