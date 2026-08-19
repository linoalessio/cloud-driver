package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.FileIntegrityException;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.task.MultiTaskingFactory;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link FileFactory} implementation backed by a {@link DataFactory}: since
 * {@link StoredFile} is itself a {@code Serialized} domain entity, {@link
 * #upload}/{@link #download}/{@link #findById}/{@link #delete} are a thin
 * pass-through to it - {@code register}/{@code fetch}/{@code findById}/
 * {@code delete} do every envelope-encryption, concurrency, caching, and
 * persistence-error-handling concern (see {@code EntityDatabaseClient}
 * behind {@link DataFactory}), so none of that is duplicated here. The one
 * thing added on top is {@link #verifyIntegrity}: after {@link DataFactory}
 * decrypts a file and confirms its AES-256-GCM authentication tag, this
 * additionally checks the decrypted bytes against {@link
 * StoredFile#checksum()} before handing the file back, throwing {@link
 * FileIntegrityException} on a mismatch - see {@link FileFactory}'s class
 * Javadoc for why both checks happen. {@code *Async} variants need no
 * override at all - they are inherited directly from {@link FileFactory},
 * which implements them generically in terms of the abstract sync methods
 * this class provides.
 */
public final class DefaultFileFactory extends FileFactory {

    private final DataFactory dataFactory;

    public DefaultFileFactory(@NotNull final DataFactory dataFactory) {
        this.dataFactory = Asserts.assertNotNull(dataFactory, "@DefaultFileFactory: dataFactory cannot be null");
    }

    @Override
    public void upload(@NotNull final StoredFile file) throws DatabaseClientException, KeyWrapException {
        this.dataFactory.register(file);
    }

    @Override
    public void upload(@NotNull final StoredFile... files) throws DatabaseClientException, KeyWrapException {
        this.dataFactory.register(files);
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
