package de.lino.cloud.plugin.file.pending;

import de.lino.cloud.api.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically retries every {@link StoredFile} queued in a {@link
 * PendingUploadCache} - the counterpart to {@code DefaultFileFactory}'s
 * {@code upload}, which only ever adds to that cache, never drains it.
 * Concrete infrastructure with no {@code cloud-driver-api} contract of its
 * own, the same reasoning as {@code EntityDatabaseClient}: it runs real I/O
 * (uploads, a connectivity probe, a background thread) rather than defining
 * a swappable behavior.
 *
 * <p>Each tick (see {@link #start(Duration)}):
 * <ol>
 *     <li>if the cache is empty, does nothing - no connectivity probe is spent when there is nothing to upload;</li>
 *     <li>otherwise checks {@code connectivityChecker}; if connectivity is still unavailable, does nothing this tick;</li>
 *     <li>otherwise retries every currently queued file concurrently (see {@link
 *     de.lino.cloud.api.task.MultiTaskingFactory}), removing each one from the cache on success and leaving it
 *     queued - for the next tick to retry - on failure.</li>
 * </ol>
 *
 * <p>Retries go through {@link DataFactory#registerAsync(de.lino.database.database.entity.Serialized)}
 * directly, not {@code DefaultFileFactory#upload} - deliberately: {@code
 * upload} defers back into {@code pendingUploadCache} instead of throwing
 * when connectivity is (still, or again) unavailable, which is exactly right
 * for a fresh, direct call but wrong for a scheduler-driven retry, whose own
 * success/failure handling below already assumes "no exception thrown" means
 * "actually persisted". Composing the two would let {@code upload} silently
 * re-queue a file and this scheduler remove that very entry a moment later,
 * losing it. {@code register} has no such softening - it either persists or
 * throws - so it is the right primitive for an unconditional retry attempt.
 *
 * <p><b>Async / big-data handling.</b> A flush dispatches every currently
 * queued file's retry through {@link DataFactory#registerAsync(de.lino.database.database.entity.Serialized)}
 * rather than looping over {@link PendingUploadCache#snapshot()} and calling
 * the blocking {@link DataFactory#register(de.lino.database.database.entity.Serialized)}
 * one file at a time: {@code registerAsync} already runs each retry on
 * {@link de.lino.cloud.api.task.MultiTaskingFactory}'s shared virtual-thread
 * executor (see its Javadoc), so every queued file is retried concurrently -
 * the same reasoning {@code EntityDatabaseClient}'s batch operations apply -
 * and a queue built up over a long outage does not retry its entries one
 * network round-trip at a time.
 */
public final class PendingUploadScheduler {

    private final DataFactory dataFactory;
    private final PendingUploadCache pendingUploadCache;
    private final ConnectivityChecker connectivityChecker;
    private final ScheduledExecutorService scheduledExecutorService;

    /**
     * Guards against overlapping flushes: if a tick's flush is still running
     * (e.g. the database is slow to respond) when the next tick fires, the
     * next tick is skipped rather than starting a second, concurrent flush
     * of the same cache.
     */
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * @param dataFactory the factory retried uploads are attempted against, via {@code registerAsync}
     * @param pendingUploadCache the cache polled and drained on every tick
     * @param connectivityChecker reports whether connectivity is currently available
     * @throws NullPointerException if any argument is {@code null}
     */
    public PendingUploadScheduler(@NotNull final DataFactory dataFactory, @NotNull final PendingUploadCache pendingUploadCache,
                                   @NotNull final ConnectivityChecker connectivityChecker) {
        this.dataFactory = Asserts.assertNotNull(dataFactory, "@PendingUploadScheduler: dataFactory cannot be null");
        this.pendingUploadCache = Asserts.assertNotNull(pendingUploadCache, "@PendingUploadScheduler: pendingUploadCache cannot be null");
        this.connectivityChecker = Asserts.assertNotNull(connectivityChecker, "@PendingUploadScheduler: connectivityChecker cannot be null");
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    /**
     * Starts ticking every {@code period}, first tick after one {@code
     * period} has elapsed. Calling this again while already running is a
     * no-op - call {@link #stop()} first to change the period.
     *
     * @param period how often to check the pending cache
     * @throws NullPointerException if {@code period} is {@code null}
     */
    public synchronized void start(@NotNull final Duration period) {
        Asserts.assertNotNull(period, "@PendingUploadScheduler.start: period cannot be null");
        if (this.scheduledFuture != null) {
            return;
        }
        this.scheduledFuture = this.scheduledExecutorService.scheduleWithFixedDelay(
                this::tick, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops ticking. The underlying executor stays alive, so {@link
     * #start(Duration)} can be called again afterward. A no-op if not currently running.
     */
    public synchronized void stop() {
        if (this.scheduledFuture != null) {
            this.scheduledFuture.cancel(false);
            this.scheduledFuture = null;
        }
    }

    /**
     * {@link #stop()}s and permanently shuts down the underlying executor -
     * call this when the scheduler itself is no longer needed, not merely to pause it.
     */
    public void shutdown() {
        stop();
        this.scheduledExecutorService.shutdown();
    }

    private void tick() {
        if (this.pendingUploadCache.isEmpty()) {
            return;
        }
        if (!this.connectivityChecker.isAvailable()) {
            return;
        }
        if (!this.flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            flushPending();
        } finally {
            this.flushing.set(false);
        }
    }

    private void flushPending() {
        final List<StoredFile> snapshot = this.pendingUploadCache.snapshot();
        final List<CompletableFuture<Void>> retries = snapshot.stream().map(this::retryUpload).toList();
        CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new)).join();
    }

    /**
     * Retries {@code file} via {@link DataFactory#registerAsync} - already
     * dispatched on {@link de.lino.cloud.api.task.MultiTaskingFactory}'s
     * shared virtual-thread executor, so no extra dispatch is needed here -
     * removing it from {@code pendingUploadCache} on success. On failure the
     * returned future still completes normally (not exceptionally): {@link
     * CompletableFuture#allOf} in {@link #flushPending()} should wait for
     * every retry to finish either way, not abort the whole flush's join the
     * moment the first file's retry fails, so the failure is swallowed here -
     * the file simply stays queued for the next tick to retry.
     */
    private CompletableFuture<Void> retryUpload(final StoredFile file) {
        return this.dataFactory.registerAsync(file)
                .thenRun(() -> this.pendingUploadCache.remove(file.fileId()))
                .exceptionally(stillFailing -> null);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "pending-upload-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }

}
