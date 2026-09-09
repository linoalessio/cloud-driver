package de.lino.cloud.extensions.intelligence;

import de.lino.cloud.api.intelligence.DuplicateGroup;
import de.lino.cloud.api.intelligence.IntelligenceDocument;
import de.lino.cloud.api.intelligence.IntelligenceService;
import de.lino.cloud.api.intelligence.SemanticMatch;
import de.lino.cloud.api.intelligence.TagSuggestion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one {@link IntelligenceService} implementation - talks to the {@code
 * cloud-driver-intelligence} Python service via {@link IntelligenceHttpClient}. Indexing runs
 * entirely on {@link #executor} (first attempt) / {@link #retryScheduler} (retries), the same
 * small-bounded-daemon-pool shape {@code DefaultContentScanService} already established, never the
 * Postgres notification thread or a request thread.
 *
 * <p><b>Fail-open, always.</b> An unreachable or unhappy Python service is retried up to {@link
 * #MAX_ATTEMPTS} times (fixed 3s/15s delays, the same non-exponential precedent {@code
 * DefaultWebhookService}/{@code DefaultContentScanService} both set), then given up on with a
 * {@code SEVERE} log - the file is simply left un-indexed. This is materially less consequential
 * than {@code DefaultContentScanService}'s own fail-open decision: that one trades away real
 * malware protection, whereas this one only costs a file its semantic discoverability until it is
 * next re-uploaded or its content replaced. Nothing about access, quota, or integrity depends on
 * it, which is exactly why no indexing state is persisted on {@code StoredFile} at all.
 *
 * <p><b>Searching is never retried.</b> A user is waiting on it, a retry would push a slow request
 * past every client's own timeout, and "no semantic results" is a perfectly serviceable answer that
 * the route can fall back to keyword search from - so a failure returns an empty list immediately.
 */
final class DefaultIntelligenceService implements IntelligenceService {

    /** Total attempts (first attempt plus retries) before giving up on indexing one file. */
    private static final int MAX_ATTEMPTS = 3;

    /** Delay before each retry, indexed by (attemptNumber - 1) - matches {@code DefaultContentScanService}'s own delays exactly. */
    private static final Duration[] RETRY_DELAYS = {Duration.ofSeconds(3), Duration.ofSeconds(15)};

    /** Bounded pool size for {@link #executor} - matches {@code DefaultContentScanService}'s own "do not over-engineer this in v1" sizing. */
    private static final int INDEX_EXECUTOR_THREADS = 2;

    private final Logger logger;
    private final IntelligenceHttpClient httpClient;
    private final long maxIndexableBytes;
    private final ExecutorService executor;
    private final ScheduledExecutorService retryScheduler;

    DefaultIntelligenceService(@NotNull final Logger logger, @NotNull final String host, final int port,
                               @NotNull final String sharedSecret, @NotNull final Duration timeout,
                               final long maxIndexableBytes) {
        this.logger = logger;
        this.httpClient = new IntelligenceHttpClient(host, port, sharedSecret, timeout);
        this.maxIndexableBytes = maxIndexableBytes;
        this.executor = Executors.newFixedThreadPool(INDEX_EXECUTOR_THREADS, daemonThreadFactory("intelligence-index"));
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("intelligence-index-retry"));
    }

    private static ThreadFactory daemonThreadFactory(final String namePrefix) {
        return runnable -> {
            final Thread thread = new Thread(runnable, namePrefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * @return {@code true} if the Python service answered its health probe with a usable embedding
     * backend - used for {@code CloudIntelligenceExtension}'s startup line. Identical to {@link
     * #isServiceHealthy()}, kept as the package-private name that extension already calls.
     */
    boolean isServiceReachable() {
        return this.isServiceHealthy();
    }

    /** Shuts {@link #executor}/{@link #retryScheduler} down - called by {@code CloudIntelligenceExtension#onEnding}/{@code #onException}. */
    void shutdown() {
        this.executor.shutdown();
        this.retryScheduler.shutdown();
    }

    /** {@inheritDoc} */
    @Override
    public void indexAsync(@NotNull final IntelligenceDocument document) {
        if (document.content() != null && document.content().length > this.maxIndexableBytes) {
            this.logger.info("@DefaultIntelligenceService: " + document.storedFileId() + " (" + document.content().length
                    + " bytes) exceeds the configured index size cap (" + this.maxIndexableBytes + ") - skipping it, un-indexed");
            return;
        }
        try {
            this.executor.execute(() -> performIndex(document, 1));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see IntelligenceService's own Javadoc.
        }
    }

    /** {@inheritDoc} */
    @Override
    public void removeAsync(@NotNull final String storedFileId) {
        try {
            this.executor.execute(() -> performRemove(storedFileId, 1));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see IntelligenceService's own Javadoc.
        }
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public List<SemanticMatch> search(@NotNull final String queryText, @NotNull final Collection<String> candidateFileIds,
                                      final int limit) {
        if (candidateFileIds.isEmpty() || queryText.isBlank() || limit <= 0) return List.of();
        try {
            return this.httpClient.search(queryText, candidateFileIds, limit);
        } catch (final IOException | IntelligenceServiceException | RuntimeException searchFailed) {
            // Not retried - see this class's own Javadoc.
            this.logger.log(Level.WARNING, "@DefaultIntelligenceService: semantic search failed - returning no results", searchFailed);
            return List.of();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void refreshOwnerAsync(@NotNull final String storedFileId, @NotNull final String ownerAuthUserId) {
        try {
            this.executor.execute(() -> performRefreshOwner(storedFileId, ownerAuthUserId));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see IntelligenceService's own Javadoc.
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Synchronous and never retried, exactly like {@link #search}: a caller is waiting on it,
     * and an empty result is a serviceable answer.
     */
    @NotNull
    @Override
    public List<DuplicateGroup> findDuplicates(@NotNull final Collection<String> candidateFileIds,
                                               final double minimumSimilarity, final int limit) {
        if (candidateFileIds.isEmpty() || limit <= 0) return List.of();
        try {
            return this.httpClient.findDuplicates(candidateFileIds, minimumSimilarity, limit);
        } catch (final IOException | IntelligenceServiceException | RuntimeException failed) {
            this.logger.log(Level.WARNING, "@DefaultIntelligenceService: duplicate detection failed - returning no groups", failed);
            return List.of();
        }
    }

    /** {@inheritDoc} Synchronous and never retried, for the same reason as {@link #findDuplicates}. */
    @NotNull
    @Override
    public List<TagSuggestion> suggestTags(@NotNull final String storedFileId, final int limit) {
        if (limit <= 0) return List.of();
        try {
            return this.httpClient.suggestTags(storedFileId, limit);
        } catch (final IOException | IntelligenceServiceException | RuntimeException failed) {
            this.logger.log(Level.WARNING, "@DefaultIntelligenceService: tag suggestion failed for " + storedFileId, failed);
            return List.of();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isServiceHealthy() {
        return this.httpClient.isHealthy();
    }

    /**
     * Refreshes one file's recorded owner, retrying on the same schedule indexing uses.
     *
     * <p>Worth retrying at all - unlike a search - because this is a fire-and-forget correction
     * with nobody waiting on it, and an owner left stale produces a silently wrong duplicate
     * grouping later rather than a visible failure now.
     */
    private void performRefreshOwner(final String storedFileId, final String ownerAuthUserId) {
        try {
            this.httpClient.updateOwner(storedFileId, ownerAuthUserId);
        } catch (final IOException | IntelligenceServiceException | RuntimeException failed) {
            this.logger.log(Level.WARNING, "@DefaultIntelligenceService: owner refresh failed for " + storedFileId, failed);
        }
    }

    private void performIndex(final IntelligenceDocument document, final int attemptNumber) {
        try {
            this.httpClient.index(document);
        } catch (final IOException | IntelligenceServiceException | RuntimeException indexFailed) {
            this.logger.log(Level.WARNING, "@DefaultIntelligenceService: index attempt " + attemptNumber
                    + " failed for " + document.storedFileId(), indexFailed);
            scheduleRetryOrGiveUp(() -> performIndex(document, attemptNumber + 1), document.storedFileId(), attemptNumber);
        }
    }

    private void performRemove(final String storedFileId, final int attemptNumber) {
        try {
            this.httpClient.remove(storedFileId);
        } catch (final IOException | IntelligenceServiceException | RuntimeException removeFailed) {
            this.logger.log(Level.WARNING, "@DefaultIntelligenceService: remove attempt " + attemptNumber
                    + " failed for " + storedFileId, removeFailed);
            scheduleRetryOrGiveUp(() -> performRemove(storedFileId, attemptNumber + 1), storedFileId, attemptNumber);
        }
    }

    /**
     * Schedules {@code retry} after this attempt's configured delay, or gives up (logging loudly)
     * once {@link #MAX_ATTEMPTS} has been reached. Unlike {@code
     * DefaultContentScanService#scheduleRetryOrFailOpen}, giving up here persists nothing at all -
     * there is no per-file indexing state to write, by design.
     */
    private void scheduleRetryOrGiveUp(final Runnable retry, final String storedFileId, final int attemptNumber) {
        if (attemptNumber >= MAX_ATTEMPTS) {
            this.logger.severe("@DefaultIntelligenceService: giving up on " + storedFileId + " after " + attemptNumber
                    + " attempts - leaving it un-indexed (semantic search will simply not find it)");
            return;
        }
        final Duration delay = RETRY_DELAYS[attemptNumber - 1];
        try {
            this.retryScheduler.schedule(retry, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final RuntimeException schedulingFailed) {
            this.logger.log(Level.WARNING, "@DefaultIntelligenceService: failed to schedule retry for " + storedFileId, schedulingFailed);
        }
    }

}
