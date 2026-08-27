package de.lino.cloud.plugin.file;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.database.PendingUploadEvent;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.database.json.JsonDocument;
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
 * PendingUploadCache}. Each tick: does nothing if the cache is empty or
 * connectivity is still unavailable; otherwise retries every queued file
 * concurrently via {@link DataFactory#registerAsync}, removing each on
 * success and leaving failures queued for the next tick.
 *
 * <p>Retries go through {@code registerAsync} directly, not {@code
 * DefaultFileFactory#upload} - {@code upload} would silently re-queue a
 * still-failing file into the very cache this scheduler is draining;
 * {@code register} either persists or throws.
 */
public final class PendingUploadScheduler {

    private final DataFactory dataFactory;
    private final PendingUploadCache pendingUploadCache;
    private final ConnectivityChecker connectivityChecker;
    private final ScheduledExecutorService scheduledExecutorService;

    /** Guards against a tick starting a second, concurrent flush while one is still running. */
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    /** The active tick schedule, or {@code null} while stopped. */
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * @param dataFactory the factory retried uploads are attempted against, via {@code registerAsync}
     * @param pendingUploadCache the cache polled and drained on every tick
     * @param connectivityChecker reports whether connectivity is currently available
     * @throws NullPointerException if any argument is {@code null}
     */
    public PendingUploadScheduler(@NotNull final DataFactory dataFactory, @NotNull final PendingUploadCache pendingUploadCache,
                                   @NotNull final ConnectivityChecker connectivityChecker) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@PendingUploadScheduler: dataFactory cannot be null");
        this.pendingUploadCache = Asserts.requireNonNull(pendingUploadCache, "@PendingUploadScheduler: pendingUploadCache cannot be null");
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@PendingUploadScheduler: connectivityChecker cannot be null");
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
        Asserts.requireNonNull(period, "@PendingUploadScheduler.start: period cannot be null");
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

        if (this.pendingUploadCache.isEmpty()) return;
        if (!this.connectivityChecker.isAvailable()) return;
        if (!this.flushing.compareAndSet(false, true)) return;

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
     * Retries {@code file} via {@link DataFactory#registerAsync}, removing it
     * from the cache on success. Failures are swallowed (the future still
     * completes normally) so one failing retry doesn't abort the whole flush.
     */
    private CompletableFuture<Void> retryUpload(final StoredFile file) {
        return this.dataFactory.registerAsync(file)
                .thenRun(() -> {
                    this.pendingUploadCache.remove(file.fileId());
                    CloudDriver.getInstance().getFactoryContainer().getEventFactory().dispatch(PendingUploadEvent.class, new JsonDocument().append("fileId", file.fileId()));
                })
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
