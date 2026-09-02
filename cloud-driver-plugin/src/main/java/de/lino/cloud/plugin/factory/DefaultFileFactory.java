package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.metrics.MetricsRecorder;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link FileFactory} implementation backed by a {@link DataFactory}: since
 * {@link StoredFile} is itself a {@code Serialized} entity, {@link
 * #download}/{@link #findById}/{@link #delete} are a thin pass-through to
 * it, plus a {@link #verifyIntegrity} checksum check on top of {@link
 * DataFactory}'s own AES-256-GCM authentication.
 *
 * <p><b>Offline-safe {@link #upload}.</b> If {@code connectivityChecker}
 * reports no connectivity, the file(s) are queued into {@code
 * pendingUploadCache} instead of attempting a database call - a {@code
 * PendingUploadScheduler} sharing the same cache is expected to drain it
 * later. A {@link DatabaseClientException} thrown mid-call is treated the
 * same way if connectivity has since dropped, otherwise it is rethrown.
 *
 * <p>{@code *Async} variants need no override - they're inherited from
 * {@link FileFactory}, implemented generically on top of the sync methods
 * here, dispatched onto {@link MultiTaskingFactory}'s virtual-thread
 * executor.
 */
public final class DefaultFileFactory extends FileFactory {

    /** The {@link DataFactory} {@link StoredFile}s are persisted through. */
    private final DataFactory dataFactory;
    /** Where files are queued while connectivity is unavailable; see {@link #getPendingUploadCache()}. */
    private final PendingUploadCache pendingUploadCache;
    /** Reports whether connectivity is currently available; see {@link #getConnectivityChecker()}. */
    private final ConnectivityChecker connectivityChecker;

    /**
     * Defaults {@link #getPendingUploadCache()} to a fresh {@link InMemoryPendingUploadCache}
     * and {@link #getConnectivityChecker()} to a fresh {@link InternetConnectivityChecker}.
     *
     * @param dataFactory the {@link DataFactory} {@link StoredFile}s are persisted through
     * @throws NullPointerException if {@code dataFactory} is {@code null}
     */
    public DefaultFileFactory(@NotNull final DataFactory dataFactory) {
        this(dataFactory, new InMemoryPendingUploadCache(), new InternetConnectivityChecker());
    }

    /**
     * @param dataFactory the {@link DataFactory} {@link StoredFile}s are persisted through
     * @param pendingUploadCache where files are queued while connectivity is unavailable
     * @param connectivityChecker reports whether connectivity is currently available
     * @throws NullPointerException if any argument is {@code null}
     */
    public DefaultFileFactory(@NotNull final DataFactory dataFactory, @NotNull final PendingUploadCache pendingUploadCache,
                               @NotNull final ConnectivityChecker connectivityChecker) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@DefaultFileFactory: dataFactory cannot be null");
        this.pendingUploadCache = Asserts.requireNonNull(pendingUploadCache, "@DefaultFileFactory: pendingUploadCache cannot be null");
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@DefaultFileFactory: connectivityChecker cannot be null");
    }

    /** Delegates to {@link DataFactory#register(Serialized)}, deferring to {@link #pendingUploadCache} while offline. */
    @Override
    public void upload(@NotNull final StoredFile file) throws DatabaseClientException, KeyWrapException {
        if (!this.connectivityChecker.isAvailable()) {
            this.pendingUploadCache.enqueue(file);
            recordMetric(MetricsRecorder::recordUploadQueued);
            return;
        }

        try {
            this.dataFactory.register(file);
            recordMetric(MetricsRecorder::recordUploadSuccess);
        } catch (final DatabaseClientException uploadFailed) {
            if (this.connectivityChecker.isAvailable()) {
                recordMetric(MetricsRecorder::recordUploadFailure);
                throw uploadFailed;
            }
            this.pendingUploadCache.enqueue(file);
            recordMetric(MetricsRecorder::recordUploadQueued);
        }
    }

    /** Delegates to {@link DataFactory#register(Serialized...)}, deferring to {@link #pendingUploadCache} while offline. */
    @Override
    public void upload(@NotNull final StoredFile... files) throws DatabaseClientException, KeyWrapException {
        if (!this.connectivityChecker.isAvailable()) {
            this.pendingUploadCache.enqueue(files);
            recordMetric(MetricsRecorder::recordUploadQueued, files.length);
            return;
        }

        try {
            this.dataFactory.register(files);
            recordMetric(MetricsRecorder::recordUploadSuccess, files.length);
        } catch (final DatabaseClientException uploadFailed) {
            if (this.connectivityChecker.isAvailable()) {
                recordMetric(MetricsRecorder::recordUploadFailure, files.length);
                throw uploadFailed;
            }
            // some files in the batch may already have been stored successfully -
            // re-queueing all of them is harmless, since a retried upload is the
            // same insert-or-update StoredFile#fileId() operation either way.
            this.pendingUploadCache.enqueue(files);
            recordMetric(MetricsRecorder::recordUploadQueued, files.length);
        }
    }

    /** Same as {@link #recordMetric(Consumer, int)} with {@code times = 1}, for the single-file {@link #upload(StoredFile)}. */
    private static void recordMetric(@NotNull final Consumer<MetricsRecorder> action) {
        recordMetric(action, 1);
    }

    /**
     * Forwards one metric event to {@link CloudDriver#getInstance()}'s {@link MetricsRecorder},
     * {@code times} times, if {@code cloud-driver-extensions-metrics} has published one - a no-op
     * otherwise (e.g. this deployment doesn't run that extension). Never throws: a
     * missing/misbehaving metrics sink must never affect a real upload, matching {@link
     * MetricsRecorder}'s own "must never throw" contract, enforced here defensively too in case an
     * implementation ever violates it.
     *
     * @param action the {@link MetricsRecorder} method to invoke, e.g. {@code
     *     MetricsRecorder::recordUploadSuccess}
     * @param times how many times to invoke {@code action} - the batch {@link #upload(StoredFile...)}
     *     counts every file in the batch as its own event, not the batch call itself as one event
     */
    private static void recordMetric(@NotNull final Consumer<MetricsRecorder> action, final int times) {
        try {
            final MetricsRecorder recorder = CloudDriver.getInstance().getServiceContainer().getMetricsRecorder();
            if (recorder == null) return;
            for (int i = 0; i < times; i++) action.accept(recorder);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

    /**
     * The {@link PendingUploadCache} {@link #upload} defers files into while
     * offline - exposed so external infrastructure (e.g. {@code
     * PendingUploadScheduler}) can be wired to drain the very same cache.
     */
    @NotNull
    public PendingUploadCache getPendingUploadCache() {
        return this.pendingUploadCache;
    }

    /**
     * The {@link ConnectivityChecker} {@link #upload} checks before
     * attempting a database call - exposed so external infrastructure can
     * query the very same instance rather than constructing its own.
     */
    @NotNull
    public ConnectivityChecker getConnectivityChecker() {
        return this.connectivityChecker;
    }

    /** Fetches via {@link #dataFactory} and checks the result's checksum. */
    @NotNull
    @Override
    public StoredFile download(@NotNull final String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyIntegrity(this.dataFactory.fetch(fileId, StoredFile.class));
    }

    /** Fetches via {@link #dataFactory} and checks every result's checksum, concurrently. */
    @NotNull
    @Override
    public List<StoredFile> download(@NotNull final String[] fileIds)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyAll(this.dataFactory.fetch(fileIds, StoredFile.class));
    }

    /** Looks up via {@link #dataFactory} and checks the result's checksum, if present. */
    @NotNull
    @Override
    public Optional<StoredFile> findById(@NotNull final String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        final Optional<StoredFile> file = this.dataFactory.findById(fileId, StoredFile.class);
        if (file.isPresent()) {
            verifyIntegrity(file.get());
        }
        return file;
    }

    /** Lists via {@link #dataFactory} and checks every result's checksum, concurrently. */
    @NotNull
    @Override
    public List<StoredFile> getEntities()
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyAll(this.dataFactory.getEntities(StoredFile.class));
    }

    /** Delegates to {@link DataFactory#delete(String, Class)}. */
    @Override
    public void delete(@NotNull final String fileId) throws DatabaseClientException {
        this.dataFactory.delete(fileId, StoredFile.class);
    }

    /** Delegates to {@link DataFactory#delete(String[], Class)}. */
    @Override
    public void delete(@NotNull final String[] fileIds) throws DatabaseClientException {
        this.dataFactory.delete(fileIds, StoredFile.class);
    }

    /** Delegates to {@link DataFactory#clear}. */
    @Override
    public void clear() {
        this.dataFactory.clear(StoredFile.class);
    }

    /** Delegates to {@link DataFactory#deleteSection}. */
    @Override
    public void deleteSection() {
        this.dataFactory.deleteSection(StoredFile.class);
    }

    /** Verifies every file in {@code files} via {@link #verifyIntegrity}, concurrently. */
    private static List<StoredFile> verifyAll(final List<StoredFile> files) throws FileIntegrityException {
        final List<CompletableFuture<Void>> verifications = files.stream()
                .map(file -> MultiTaskingFactory.getInstance().runAsync(() -> verifyIntegrityUnchecked(file)))
                .toList();
        joinAllVerifications(verifications);
        return files;
    }

    /**
     * Checks {@code file}'s content against its recorded checksum.
     *
     * @throws FileIntegrityException if the content does not match
     */
    private static StoredFile verifyIntegrity(final StoredFile file) throws FileIntegrityException {
        if (!file.verifyChecksum()) {
            throw new FileIntegrityException(
                    "@DefaultFileFactory: checksum mismatch for file '" + file.primaryKey()
                            + "' - decrypted content does not match its recorded " + file.checksum().algorithm() + " checksum"
            );
        }
        return file;
    }

    /**
     * Same as {@link #verifyIntegrity(StoredFile)}, but rethrows a checksum
     * mismatch wrapped in a {@link CompletionException} so it can be run inside
     * a {@link CompletableFuture} task by {@link #verifyAll}.
     *
     * @param file the file to verify
     * @throws CompletionException wrapping a {@link FileIntegrityException} if the content does not match
     */
    private static void verifyIntegrityUnchecked(final StoredFile file) {
        try {
            verifyIntegrity(file);
        } catch (final FileIntegrityException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Waits for every verification task in {@code futures} to complete, unwrapping
     * the first failure encountered from its {@link CompletionException} wrapper.
     *
     * @param futures the in-flight verification tasks, as produced by {@link #verifyAll}
     * @throws FileIntegrityException if any task failed a checksum check
     * @throws RuntimeException the original unchecked cause, if a task failed with something other than {@link FileIntegrityException}
     * @throws IllegalStateException if a task failed with a non-{@link RuntimeException} cause
     */
    private static void joinAllVerifications(final List<CompletableFuture<Void>> futures) throws FileIntegrityException {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (final CompletionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof FileIntegrityException fileIntegrityException) {
                throw fileIntegrityException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("@DefaultFileFactory: unexpected failure while verifying integrity", cause);
        }
    }

}
