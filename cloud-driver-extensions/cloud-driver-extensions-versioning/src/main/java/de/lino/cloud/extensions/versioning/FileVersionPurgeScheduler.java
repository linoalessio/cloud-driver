package de.lino.cloud.extensions.versioning;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Permanently prunes a {@link FileVersion} once it falls outside <b>either</b> configured
 * retention cap - more than {@link #maxVersionsPerFile} versions already newer than it exist for
 * the same source file, <b>or</b> it was captured more than {@link #retentionPeriod} ago - a
 * "keep last N versions or last N days" policy. Modeled directly on {@code cloud-driver-plugin}'s {@code TrashPurgeScheduler}: its
 * own daemon thread, ticking on a fixed period, scanning every {@link FileVersion} across every
 * account in one pass (this needs to sweep everyone, not one account at a time, so it talks to
 * {@link DataFactory}/{@link FileFactory} directly rather than through {@code CloudUserService}).
 *
 * <p><b>Unlike {@code TrashPurgeScheduler}, this one is started automatically</b> (by {@link
 * CloudVersioningExtension#onLoading()}, with its configured-or-default retention window) rather
 * than left for an operator to wire in deliberately. {@code TrashPurgeScheduler}'s own caution
 * exists because it could be retrofitted onto trash data that already existed before that
 * scheduler did, where a too-short window destroys real user data the moment it starts ticking;
 * file versioning is a brand-new capability with zero pre-existing version data on any deployment
 * this could run against, so there is nothing an initial default retention window could
 * surprise-delete on day one. The chosen defaults (10 versions / 30 days) are still just
 * defaults, not a permanent decision - both are configurable, see {@link #withConfiguredRetention}.
 */
final class FileVersionPurgeScheduler {

    /** {@code configuration.json} key for the version-count cap - see {@link #withConfiguredRetention}. */
    private static final String MAX_VERSIONS_CONFIG_KEY = "file-versioning-max-versions-per-file";

    /** Default value for {@link #MAX_VERSIONS_CONFIG_KEY} if unset. */
    private static final int DEFAULT_MAX_VERSIONS_PER_FILE = 10;

    /** {@code configuration.json} key for the age-based retention window - see {@link #withConfiguredRetention}. */
    private static final String RETENTION_DAYS_CONFIG_KEY = "file-versioning-retention-days";

    /** Default value for {@link #RETENTION_DAYS_CONFIG_KEY} if unset. */
    private static final long DEFAULT_RETENTION_DAYS = 30L;

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final int maxVersionsPerFile;
    private final Duration retentionPeriod;
    private final Logger logger;
    private final ScheduledExecutorService scheduledExecutorService;
    private final AtomicBoolean purging = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> scheduledFuture;

    FileVersionPurgeScheduler(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory,
                               final int maxVersionsPerFile, @NotNull final Duration retentionPeriod, @NotNull final Logger logger) {
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
        this.maxVersionsPerFile = maxVersionsPerFile;
        this.retentionPeriod = retentionPeriod;
        this.logger = logger;
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    /**
     * Reads {@link #MAX_VERSIONS_CONFIG_KEY}/{@link #RETENTION_DAYS_CONFIG_KEY} from {@code
     * configuration.json} (via {@link CloudDriver#getConfiguration()}), defaulting to {@value
     * #DEFAULT_MAX_VERSIONS_PER_FILE}/{@value #DEFAULT_RETENTION_DAYS} days if unset - the same
     * {@link JsonDocument#contains}-first pattern {@code TrashPurgeScheduler#withConfiguredRetention}
     * already uses. Does not call {@link #start(Duration)}.
     */
    @NotNull
    static FileVersionPurgeScheduler withConfiguredRetention(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory, @NotNull final Logger logger) {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        final int maxVersions = configuration.contains(MAX_VERSIONS_CONFIG_KEY)
                ? configuration.getInteger(MAX_VERSIONS_CONFIG_KEY) : DEFAULT_MAX_VERSIONS_PER_FILE;
        final long retentionDays = configuration.contains(RETENTION_DAYS_CONFIG_KEY)
                ? configuration.getLong(RETENTION_DAYS_CONFIG_KEY) : DEFAULT_RETENTION_DAYS;
        return new FileVersionPurgeScheduler(dataFactory, fileFactory, maxVersions, Duration.ofDays(retentionDays), logger);
    }

    /** Starts ticking every {@code tickPeriod}. A no-op if already running - call {@link #stop()} first to change the period. */
    synchronized void start(@NotNull final Duration tickPeriod) {
        if (this.scheduledFuture != null) return;
        this.scheduledFuture = this.scheduledExecutorService.scheduleWithFixedDelay(
                this::tick, tickPeriod.toMillis(), tickPeriod.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops ticking; the underlying executor stays alive so {@link #start(Duration)} can restart it. A no-op if not running. */
    synchronized void stop() {
        if (this.scheduledFuture != null) {
            this.scheduledFuture.cancel(false);
            this.scheduledFuture = null;
        }
    }

    /** {@link #stop()}s and permanently shuts the underlying executor down. */
    void shutdown() {
        this.stop();
        this.scheduledExecutorService.shutdown();
    }

    /** One scheduled sweep, guarded against overlapping with a still-running previous tick. */
    private void tick() {
        if (!this.purging.compareAndSet(false, true)) return;
        try {
            this.purgeExpiredVersions();
        } finally {
            this.purging.set(false);
        }
    }

    /**
     * Groups every {@link FileVersion} by {@link FileVersion#getSourceFileId()}, and within each
     * group, prunes whichever versions fall outside {@link #maxVersionsPerFile} (counting from
     * the newest) or {@link #retentionPeriod} - either condition alone is enough reason to prune,
     * matching this class's own "keep last N versions OR last N days" Javadoc.
     */
    private void purgeExpiredVersions() {
        final List<FileVersion> allVersions;
        try {
            allVersions = this.dataFactory.getEntities(FileVersion.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            return; // best-effort - try again next tick rather than letting one failed scan kill the whole sweep
        }

        final long cutoffEpochMillis = System.currentTimeMillis() - this.retentionPeriod.toMillis();
        final Map<String, List<FileVersion>> bySourceFile = allVersions.stream()
                .collect(Collectors.groupingBy(FileVersion::getSourceFileId));

        for (final List<FileVersion> versions : bySourceFile.values()) {
            final List<FileVersion> newestFirst = versions.stream()
                    .sorted(Comparator.comparingInt(FileVersion::getVersionNumber).reversed())
                    .toList();
            // First pass: which versions the count/age rules alone would retain.
            FileVersion oldestRetained = null;
            for (int index = 0; index < newestFirst.size(); index++) {
                final FileVersion version = newestFirst.get(index);
                final boolean exceedsCount = index >= this.maxVersionsPerFile;
                final boolean expiredByAge = version.getCapturedAtEpochMillis() < cutoffEpochMillis;
                if (!exceedsCount && !expiredByAge) {
                    oldestRetained = version; // newestFirst order: the last one to pass is the oldest retained
                }
            }
            // Delta chains (roadmap Phase 4): a delta is reconstructed from every older version
            // back to its keyframe, so the purge boundary must snap BACK to the keyframe the
            // oldest-retained version's chain starts at - purging that keyframe (or any link)
            // would leave retained versions unreconstructable. Rows are chained by contiguous
            // version numbers (capture guarantees it), so "keep everything >= the chain's
            // keyframe number" is exactly the chain-safe boundary. A legacy full-copy row is its
            // own keyframe, so pre-delta deployments purge exactly as before.
            final int keepFromNumber = oldestRetained == null ? Integer.MAX_VALUE
                    : chainKeyframeNumber(oldestRetained, newestFirst);
            for (final FileVersion version : newestFirst) {
                if (version.getVersionNumber() < keepFromNumber) {
                    this.purgeVersion(version);
                }
            }
        }
    }

    /**
     * The version number of the keyframe {@code version}'s delta chain starts at - {@code
     * version}'s own number if it is itself a keyframe. If the walk hits a gap (a chain already
     * broken by something else), answers the lowest number actually reachable, so the purge
     * never widens existing damage.
     */
    private static int chainKeyframeNumber(final FileVersion version, final List<FileVersion> allVersions) {
        final Map<Integer, FileVersion> byNumber = new HashMap<>();
        for (final FileVersion candidate : allVersions) {
            byNumber.put(candidate.getVersionNumber(), candidate);
        }
        FileVersion cursor = version;
        while (!cursor.isKeyframe() && cursor.getBaseVersionNumber() != null) {
            final FileVersion base = byNumber.get(cursor.getBaseVersionNumber());
            if (base == null) {
                break;
            }
            cursor = base;
        }
        return cursor.getVersionNumber();
    }

    /** Permanently removes {@code version}'s own {@code StoredFile} content, then the {@link FileVersion} row itself. */
    private void purgeVersion(final FileVersion version) {
        try {
            this.fileFactory.delete(version.getVersionedFileId());
        } catch (final DatabaseClientException alreadyGoneOrOther) {
            // proceed to drop the FileVersion row regardless - a missing StoredFile row (e.g. a
            // previous tick partially completed) shouldn't leave a stale version row forever
        }
        try {
            this.dataFactory.delete(FileVersion.compositeKey(version.getSourceFileId(), version.getVersionNumber()), FileVersion.class);
        } catch (final DatabaseClientException alreadyGone) {
            // nothing left to do - already removed by a previous tick
        }
        this.logger.log(Level.INFO, "Purged version " + version.getVersionNumber() + " of file '" + version.getSourceFileId() + "'");
    }

    /** Builds a {@link ThreadFactory} producing a single, named, daemon thread for {@link #scheduledExecutorService}. */
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "file-version-purge-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }

}
