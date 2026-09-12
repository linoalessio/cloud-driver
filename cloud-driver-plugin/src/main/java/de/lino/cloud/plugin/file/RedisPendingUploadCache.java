package de.lino.cloud.plugin.file;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.redis.RedisSupport;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-backed {@link PendingUploadCache}:
 * with several instances behind a load balancer, a file queued on one instance becomes
 * <b>visible</b> to every other - {@link #size()}/{@link #isEmpty()} answer from the shared
 * Redis section, so the cross-instance queue depth (and the {@code
 * cloud_driver_pending_upload_queue_depth} gauge built on it) is honest, instead of each
 * instance only ever seeing its own arrivals.
 *
 * <p><b>The security constraint this class is designed around:</b>
 * at enqueue time the {@link StoredFile} carries its full plaintext content ({@code
 * contentBase64} - that is the queue's entire purpose, holding content whose persistence is
 * still owed), and {@code RedisSupport}'s own contract forbids plaintext file content (or any
 * key material) from ever reaching Redis. So only a minimal metadata record crosses Redis per
 * entry - {@code fileId}, {@code sizeBytes}, {@code enqueuedAtEpochMillis}; deliberately not
 * the file name and not the checksum, both of which would be new plaintext exposure with no
 * consumer - while the content itself stays in this instance's local {@link
 * InMemoryPendingUploadCache} delegate. It follows that {@link #snapshot()} (what {@code
 * PendingUploadScheduler} drains and retries) returns only the <b>local</b>, content-carrying
 * entries: an instance can only ever retry uploads whose bytes it holds; a remote instance's
 * entry is visible, never drainable from here - a deliberate design consequence of the
 * constraint, not a shortcut.
 *
 * <p><b>Degradation</b> follows {@code WebhookDeliveryLog}'s exact posture: no Redis configured,
 * or the section failing to open, means this class <em>is</em> the in-memory cache (the delegate
 * alone); a Redis write failing mid-run disables the Redis half for the rest of the process
 * (logged once), never throws into an enqueue.
 *
 * <p><b>Known limitation, documented rather than hidden:</b> an instance that dies while holding
 * queued content leaves its Redis metadata entries behind (the content is gone with the process
 * - true of the in-memory cache before this class existed too). They keep the shared depth
 * honest about the loss but can only be cleared by an operator (or a future reconciliation
 * sweep); this class never guesses that another instance's entry is safe to delete.
 */
public final class RedisPendingUploadCache implements PendingUploadCache {

    /** The {@link RedisSupport#section(String)} name every instance shares. */
    private static final String SECTION_NAME = "cloud-driver-pending-uploads";

    /** Holds the actual, content-carrying entries this instance received - see the class Javadoc on why content never crosses Redis. */
    private final InMemoryPendingUploadCache localDelegate = new InMemoryPendingUploadCache();

    /** Where Redis failures are reported once - this class never throws them onward. */
    private final Logger logger;

    /** The shared Redis section, or {@code null} once/if Redis is unavailable - the in-memory-only mode. */
    @Nullable
    private volatile DatabaseSection section;

    /**
     * @param redisSupport the deployment's Redis facet, or {@code null} for a plain
     *     single-instance deployment - this class then behaves exactly like {@link
     *     InMemoryPendingUploadCache}
     */
    public RedisPendingUploadCache(@Nullable final RedisSupport redisSupport) {
        this.logger = Logger.getLogger(RedisPendingUploadCache.class.getSimpleName());
        DatabaseSection resolved = null;
        if (redisSupport != null) {
            try {
                resolved = redisSupport.section(SECTION_NAME);
            } catch (final Exception redisUnavailable) {
                this.logger.log(Level.WARNING, "@RedisPendingUploadCache: could not open the shared Redis section - "
                        + "pending uploads stay visible to this instance only.", redisUnavailable);
            }
        }
        this.section = resolved;
    }

    /** {@inheritDoc} Locally (full content) plus the shared metadata record - see the class Javadoc. */
    @Override
    public void enqueue(@NotNull final StoredFile file) {
        this.localDelegate.enqueue(file);
        this.writeMetadata(file);
    }

    /** {@inheritDoc} */
    @Override
    public void enqueue(@NotNull final StoredFile... files) {
        for (final StoredFile file : files) {
            this.enqueue(file);
        }
    }

    /** {@inheritDoc} Removes both the local entry and the shared metadata record. */
    @Override
    public void remove(@NotNull final String fileId) {
        this.localDelegate.remove(fileId);
        final DatabaseSection currentSection = this.section;
        if (currentSection == null) {
            return;
        }
        try {
            currentSection.delete(fileId);
        } catch (final Exception deleteFailed) {
            // A missing entry is the normal race (another path already cleared it); a genuine
            // Redis failure disables the Redis half like every other write failure would.
            this.disableRedis("remove", deleteFailed);
        }
    }

    /** {@inheritDoc} Answered from the shared section when Redis is up - the cross-instance truth - and locally otherwise. */
    @Override
    public boolean isEmpty() {
        return this.size() == 0;
    }

    /** {@inheritDoc} See {@link #isEmpty()}. */
    @Override
    public int size() {
        final DatabaseSection currentSection = this.section;
        if (currentSection == null) {
            return this.localDelegate.size();
        }
        try {
            return (int) currentSection.count();
        } catch (final Exception countFailed) {
            this.disableRedis("size", countFailed);
            return this.localDelegate.size();
        }
    }

    /**
     * {@inheritDoc} Deliberately the <b>local</b> entries only - the ones whose content this
     * instance actually holds and can therefore retry; see the class Javadoc's security section.
     */
    @NotNull
    @Override
    public List<StoredFile> snapshot() {
        return this.localDelegate.snapshot();
    }

    /** Writes one entry's minimal metadata record to the shared section - insert-or-update, failure disables the Redis half. */
    private void writeMetadata(final StoredFile file) {
        final DatabaseSection currentSection = this.section;
        if (currentSection == null) {
            return;
        }
        // Field-by-field, hand-written - the WebhookDeliveryLog convention: an unknown/changed
        // field on read can then never break anything, and what crosses Redis stays explicit.
        final DatabaseEntry entry = new DatabaseEntry(file.fileId(), new JsonDocument()
                .append("fileId", file.fileId())
                .append("sizeBytes", file.sizeBytes())
                .append("enqueuedAtEpochMillis", System.currentTimeMillis()));
        try {
            try {
                currentSection.insert(entry);
            } catch (final DataAlreadyExist reEnqueued) {
                currentSection.update(entry);
            }
        } catch (final Exception writeFailed) {
            this.disableRedis("enqueue", writeFailed);
        }
    }

    /** Turns the Redis half off for the rest of this process's life - one warning, then pure in-memory behavior. */
    private void disableRedis(final String operation, final Exception failure) {
        this.section = null;
        this.logger.log(Level.WARNING, "@RedisPendingUploadCache." + operation + ": Redis failed - "
                + "pending uploads stay visible to this instance only for the rest of this run.", failure);
    }
}
