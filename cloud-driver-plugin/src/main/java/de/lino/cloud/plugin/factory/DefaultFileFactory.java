package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link FileFactory} implementation backed by a {@link DataFactory}: since
 * {@link StoredFile} is itself a {@code Serialized} domain meta, {@link
 * #download}/{@link #findById}/{@link #delete} are a thin pass-through to it -
 * {@code fetch}/{@code findById}/{@code delete} do every envelope-encryption,
 * concurrency, caching, and persistence-error-handling concern (see {@code
 * EntityDatabaseClient} behind {@link DataFactory}), so none of that is
 * duplicated here. The one thing added on top of those is {@link
 * #verifyIntegrity}: after {@link DataFactory} decrypts a file and confirms
 * its AES-256-GCM authentication tag, this additionally checks the decrypted
 * bytes against {@link StoredFile#checksum()} before handing the file back,
 * throwing {@link FileIntegrityException} on a mismatch - see {@link
 * FileFactory}'s class Javadoc for why both checks happen.
 *
 * <p><b>Offline-safe {@link #upload}.</b> {@link DataFactory#register}
 * ultimately reaches whatever {@code DatabaseProvider} was configured - in
 * production, typically a database reached over the network - so an upload
 * attempted with no internet connection would otherwise just fail. Before
 * delegating to {@code dataFactory}, {@link #upload} checks {@code
 * connectivityChecker}, and if connectivity is currently down, queues the
 * file(s) into {@code pendingUploadCache} instead of attempting (and waiting
 * out a timeout on) a database call that has no realistic chance of
 * succeeding - a {@code PendingUploadScheduler} sharing the same {@link
 * PendingUploadCache} is expected to drain it once connectivity returns,
 * this class only ever adds to it, never drains it itself. Connectivity can
 * also drop mid-call, after the proactive check passed: {@link #upload}
 * additionally catches {@link DatabaseClientException} from {@code
 * dataFactory} and re-checks {@code connectivityChecker}, treating a
 * still-down connection as another "queue it for later" case rather than
 * propagating it; otherwise it is a genuine persistence failure and is
 * rethrown unchanged. {@link #getPendingUploadCache()}/{@link
 * #getConnectivityChecker()} expose the instances this factory checks
 * against, so external infrastructure (e.g. {@code PendingUploadScheduler})
 * can be wired against the very same ones.
 *
 * <p>{@code *Async} variants need no override at all - they are inherited
 * directly from {@link FileFactory}, which implements them generically in
 * terms of the abstract sync methods this class provides, dispatched onto
 * {@link MultiTaskingFactory}'s shared virtual-thread executor. That matters
 * specifically for {@link #upload}: {@code connectivityChecker}'s probe
 * blocks the calling thread for up to its configured timeout, and running
 * that probe on a virtual thread (via {@code uploadAsync}) means it parks
 * cheaply instead of tying up a platform thread - the same reasoning {@code
 * EntityDatabaseClient}'s class Javadoc gives for using virtual threads on
 * I/O-bound calls in the first place.
 */
public final class DefaultFileFactory extends FileFactory {

    private final DataFactory dataFactory;
    private final PendingUploadCache pendingUploadCache;
    private final ConnectivityChecker connectivityChecker;

    /**
     * Convenience constructor defaulting {@link #getPendingUploadCache()} to
     * a fresh {@link InMemoryPendingUploadCache} and {@link
     * #getConnectivityChecker()} to a fresh {@link InternetConnectivityChecker}.
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

    @Override
    public void upload(@NotNull final StoredFile file) throws DatabaseClientException, KeyWrapException {
        if (!this.connectivityChecker.isAvailable()) {
            this.pendingUploadCache.enqueue(file);
            return;
        }

        try {
            this.dataFactory.register(file);
        } catch (final DatabaseClientException uploadFailed) {
            if (this.connectivityChecker.isAvailable()) {
                throw uploadFailed;
            }
            this.pendingUploadCache.enqueue(file);
        }
    }

    @Override
    public void upload(@NotNull final StoredFile... files) throws DatabaseClientException, KeyWrapException {
        if (!this.connectivityChecker.isAvailable()) {
            this.pendingUploadCache.enqueue(files);
            return;
        }

        try {
            this.dataFactory.register(files);
        } catch (final DatabaseClientException uploadFailed) {
            if (this.connectivityChecker.isAvailable()) {
                throw uploadFailed;
            }
            // some files in the batch may already have been stored successfully -
            // re-queueing all of them is harmless, since a retried upload is the
            // same insert-or-update StoredFile#fileId() operation either way.
            this.pendingUploadCache.enqueue(files);
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

    @NotNull
    @Override
    public StoredFile download(@NotNull final String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyIntegrity(this.dataFactory.fetch(fileId, StoredFile.class));
    }

    @NotNull
    @Override
    public List<StoredFile> download(@NotNull final String[] fileIds)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyAll(this.dataFactory.fetch(fileIds, StoredFile.class));
    }

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

    @NotNull
    @Override
    public List<StoredFile> getEntities()
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyAll(this.dataFactory.getEntities(StoredFile.class));
    }

    @Override
    public void delete(@NotNull final String fileId) throws DatabaseClientException {
        this.dataFactory.delete(fileId, StoredFile.class);
    }

    @Override
    public void delete(@NotNull final String[] fileIds) throws DatabaseClientException {
        this.dataFactory.delete(fileIds, StoredFile.class);
    }

    @Override
    public void clear() {
        this.dataFactory.clear(StoredFile.class);
    }

    @Override
    public void deleteSection() {
        this.dataFactory.deleteSection(StoredFile.class);
    }

    /**
     * Verifies every file in {@code files} the same way {@link
     * #verifyIntegrity} verifies a single one, dispatched concurrently
     * (each file's checksum is independent, CPU-bound work) rather than
     * hashing every file sequentially after they have all already been
     * fetched - used by both {@link #download(String[])} and {@link
     * #getEntities()}.
     */
    private static List<StoredFile> verifyAll(final List<StoredFile> files) throws FileIntegrityException {
        final List<CompletableFuture<Void>> verifications = files.stream()
                .map(file -> MultiTaskingFactory.getInstance().runAsync(() -> verifyIntegrityUnchecked(file)))
                .toList();
        joinAllVerifications(verifications);
        return files;
    }

    private static StoredFile verifyIntegrity(final StoredFile file) throws FileIntegrityException {
        if (!file.verifyChecksum()) {
            throw new FileIntegrityException(
                    "@DefaultFileFactory: checksum mismatch for file '" + file.primaryKey()
                            + "' - decrypted content does not match its recorded " + file.checksum().algorithm() + " checksum"
            );
        }
        return file;
    }

    private static void verifyIntegrityUnchecked(final StoredFile file) {
        try {
            verifyIntegrity(file);
        } catch (final FileIntegrityException e) {
            throw new CompletionException(e);
        }
    }

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
