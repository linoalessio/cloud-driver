package de.lino.cloud.plugin.file;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.database.PendingUploadEvent;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 *
 * <p><b>S3-backed content (architecture/AWS_S3_IMPL.md).</b> If constructed with a non-{@code
 * null} {@link #fileFactory}, every retry first runs {@code file} through {@link
 * DefaultFileFactory#prepareForPersistence} - the same S3-then-metadata sequence {@code
 * DefaultFileFactory#upload} itself applies - before handing the (possibly now metadata-only)
 * result to {@link #dataFactory}'s {@code registerAsync}; a {@code null} {@code fileFactory}
 * (the three-argument constructor) skips this and registers the original, still content-carrying
 * {@code file} unchanged, exactly as before this feature existed.
 */
public final class PendingUploadScheduler {

    /** The factory retried uploads are attempted against, via {@code registerAsync}. */
    private final DataFactory dataFactory;
    /** The cache polled and drained on every tick. */
    private final PendingUploadCache pendingUploadCache;
    /** Reports whether connectivity is currently available. */
    private final ConnectivityChecker connectivityChecker;
    /** Applies {@link DefaultFileFactory#prepareForPersistence} to a retried file before registering it, or {@code null} to skip that step (S3-backed storage not configured). */
    private final DefaultFileFactory fileFactory;
    /** Single-thread, daemon-backed executor driving the tick schedule. */
    private final ScheduledExecutorService scheduledExecutorService;

    /** Guards against a tick starting a second, concurrent flush while one is still running. */
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    /** The active tick schedule, or {@code null} while stopped. */
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * Same as {@link #PendingUploadScheduler(DataFactory, PendingUploadCache, ConnectivityChecker,
     * DefaultFileFactory)} with {@link #fileFactory} defaulted to {@code null} - a retry is
     * registered as-is, with no S3-then-metadata step applied first.
     *
     * @param dataFactory the factory retried uploads are attempted against, via {@code registerAsync}
     * @param pendingUploadCache the cache polled and drained on every tick
     * @param connectivityChecker reports whether connectivity is currently available
     * @throws NullPointerException if any argument is {@code null}
     */
    public PendingUploadScheduler(@NotNull final DataFactory dataFactory, @NotNull final PendingUploadCache pendingUploadCache,
                                   @NotNull final ConnectivityChecker connectivityChecker) {
        this(dataFactory, pendingUploadCache, connectivityChecker, null);
    }

    /**
     * @param dataFactory the factory retried uploads are attempted against, via {@code registerAsync}
     * @param pendingUploadCache the cache polled and drained on every tick
     * @param connectivityChecker reports whether connectivity is currently available
     * @param fileFactory applies {@code prepareForPersistence} to a retried file before
     *     registering it (see this class's own Javadoc), or {@code null} to skip that step
     * @throws NullPointerException if {@code dataFactory}/{@code pendingUploadCache}/{@code connectivityChecker} is {@code null}
     */
    public PendingUploadScheduler(@NotNull final DataFactory dataFactory, @NotNull final PendingUploadCache pendingUploadCache,
                                   @NotNull final ConnectivityChecker connectivityChecker, @Nullable final DefaultFileFactory fileFactory) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@PendingUploadScheduler: dataFactory cannot be null");
        this.pendingUploadCache = Asserts.requireNonNull(pendingUploadCache, "@PendingUploadScheduler: pendingUploadCache cannot be null");
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@PendingUploadScheduler: connectivityChecker cannot be null");
        this.fileFactory = fileFactory;
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

    /**
     * One scheduled check: does nothing if {@link #pendingUploadCache} is empty or {@link
     * #connectivityChecker} reports unavailable, and does nothing if a flush is already in
     * progress (guarded by {@link #flushing}); otherwise runs {@link #flushPending()}.
     */
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

    /**
     * Snapshots {@link #pendingUploadCache} and retries every queued file concurrently via
     * {@link #retryUpload}, blocking until every retry has completed (success or failure).
     */
    private void flushPending() {
        final List<StoredFile> snapshot = this.pendingUploadCache.snapshot();
        final List<CompletableFuture<Void>> retries = snapshot.stream().map(this::retryUpload).toList();
        CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new)).join();
    }

    /**
     * Retries {@code file} - via {@link #fileFactory}'s {@code prepareForPersistence} first, if
     * configured (see this class's own Javadoc), then {@link DataFactory#registerAsync} -
     * removing it from {@link #pendingUploadCache} and dispatching a {@link PendingUploadEvent}
     * through the {@link CloudDriver#getInstance()}'s {@code EventFactory} on success. Failures
     * are swallowed (the returned future still completes normally) so one failing retry doesn't
     * abort the whole flush - the file is simply left queued for the next tick.
     *
     * @param file the file to retry
     * @return a future that completes once this file's retry has finished, success or failure
     */
    private CompletableFuture<Void> retryUpload(final StoredFile file) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> prepareForRegistrationUnchecked(file))
                .thenCompose(this.dataFactory::registerAsync)
                .thenRun(() -> {
                    this.pendingUploadCache.remove(file.fileId());
                    CloudDriver.getInstance().getFactoryContainer().getEventFactory().dispatch(PendingUploadEvent.class, new JsonDocument().append("fileId", file.fileId()));
                })
                .exceptionally(stillFailing -> null);
    }

    /**
     * Applies {@link #fileFactory}'s {@code prepareForPersistence} to {@code file}, or returns it
     * unchanged if {@link #fileFactory} is {@code null} - the checked-exception-wrapping form
     * {@link #retryUpload} needs to run this inside a {@link java.util.function.Supplier}. An
     * unchecked {@code ObjectStorageException} from {@code prepareForPersistence} itself needs no
     * wrapping here - it already propagates out of the {@link java.util.function.Supplier} as-is.
     *
     * @throws CompletionException wrapping a {@link KeyWrapException} on failure
     */
    private StoredFile prepareForRegistrationUnchecked(final StoredFile file) {
        if (this.fileFactory == null) {
            return file;
        }
        try {
            return this.fileFactory.prepareForPersistence(file);
        } catch (final KeyWrapException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Builds a {@link ThreadFactory} producing a single, named, daemon thread
     * ({@code "pending-upload-scheduler"}) for {@link #scheduledExecutorService}, so this
     * scheduler never by itself keeps the JVM alive.
     *
     * @return a daemon-thread-producing factory
     */
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "pending-upload-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }

}
