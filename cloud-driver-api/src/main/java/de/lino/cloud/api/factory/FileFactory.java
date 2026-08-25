package de.lino.cloud.api.factory;

import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.file.meta.FileMetadata;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Uploads, downloads, and deletes {@link StoredFile}s of any content type -
 * the file-persistence counterpart of {@link DataFactory}, built the same
 * way: {@link StoredFile} is itself a {@code Serialized} domain meta, so
 * implementations are expected to persist it through a {@link DataFactory}
 * rather than duplicating {@code EntityDatabaseClient}-level persistence
 * logic - see {@code DefaultFileFactory} in {@code cloud-driver-plugin}.
 * Reached through {@code CloudAPI#getFileFactory()}.
 *
 * <p>Only {@link #upload(StoredFile)}, {@link #upload(StoredFile[])}, {@link
 * #download(String)}, {@link #download(String[])}, {@link #findById}, and
 * {@link #delete(String)}/{@link #delete(String[])} are abstract; every
 * {@code *Async} variant plus {@link #metadata} is implemented here,
 * generically, in terms of those - the same "abstract primitives + generic
 * concrete methods" shape {@link DataFactory} and {@link ExtensionFactory}
 * use.
 *
 * <p>Every download verifies two independent things before handing content
 * back to a caller: the AES-256-GCM authentication tag over the stored
 * ciphertext ({@link AuthenticationFailedException} on failure - section 6,
 * AUTHENTICATED ENCRYPTION AND INTEGRITY), and the plaintext checksum
 * recorded on {@link StoredFile#checksum()} against the actually-decrypted
 * bytes ({@link FileIntegrityException} on failure) - so a file that
 * round-trips successfully is guaranteed byte-for-byte identical to what was
 * uploaded, regardless of its original type.
 */
public abstract class FileFactory {

    /**
     * Encrypts {@code file} and stores it in the configured database under
     * its {@link StoredFile#fileId() file id}, inserting it if no file with
     * that id exists yet or overwriting it otherwise - the same
     * insert-or-update semantics as {@link
     * DataFactory#register(de.lino.database.database.entity.Serialized)}.
     *
     * @param file the file to store, of any content type
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the file's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract void upload(@NotNull StoredFile file) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts and stores every file in {@code files}, each under its own
     * {@link StoredFile#fileId() file id}, the same way {@link
     * #upload(StoredFile)} stores a single file. Implementations dispatch
     * files concurrently rather than one at a time - see the concrete
     * implementation's Javadoc for the exact failure semantics of a batch
     * with more than one failing file.
     *
     * @param files the files to store
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any file's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract void upload(@NotNull StoredFile... files) throws DatabaseClientException, KeyWrapException;

    /**
     * Retrieves the file stored under {@code fileId}, decrypts it, verifies
     * its authentication tag, then verifies its decrypted content against
     * {@link StoredFile#checksum()} before returning it - see the class
     * Javadoc for why both checks happen.
     *
     * @param fileId the file's {@link StoredFile#fileId() file id}
     * @return the decrypted, integrity-verified file
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the file's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     * @throws FileIntegrityException if the decrypted content does not match its recorded checksum
     */
    @NotNull
    public abstract StoredFile download(@NotNull String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException;

    /**
     * Retrieves every file stored under {@code fileIds}, in the same order,
     * the same way {@link #download(String)} retrieves a single file.
     *
     * @param fileIds the files' {@link StoredFile#fileId() file ids}
     * @return the decrypted, integrity-verified files, in the same order as {@code fileIds}
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any file's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     * @throws FileIntegrityException if any decrypted content does not match its recorded checksum
     */
    @NotNull
    public abstract List<StoredFile> download(@NotNull String[] fileIds)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException;

    /**
     * Looks up the file stored under {@code fileId} the same way {@link
     * #download(String)} does, but returns {@link Optional#empty()} instead
     * of throwing when no such file exists - for callers that mean "does
     * this exist?" rather than "this must exist". Only a confirmed-absent id
     * becomes {@code empty()}; a corrupted record, an unwrappable key, a
     * failed authentication check, or a checksum mismatch still throw
     * exactly like {@link #download(String)} does, since those are real
     * failures, not absence.
     *
     * @param fileId the file's {@link StoredFile#fileId() file id}
     * @return the decrypted, integrity-verified file, or {@link Optional#empty()} if no file exists under {@code fileId}
     * @throws DatabaseClientException if the file exists but its record is corrupted
     * @throws KeyWrapException if the file's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     * @throws FileIntegrityException if the decrypted content does not match its recorded checksum
     */
    @NotNull
    public abstract Optional<StoredFile> findById(@NotNull String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException;

    /**
     * Retrieves every stored file, decrypting and integrity-verifying each
     * one the same way {@link #download(String)} does for a single file -
     * see {@link DataFactory#getEntities}.
     *
     * @return every stored, integrity-verified file, in no particular guaranteed order
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if any file's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     * @throws FileIntegrityException if any decrypted content does not match its recorded checksum
     */
    @NotNull
    public abstract List<StoredFile> getEntities()
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException;

    /**
     * Deletes the file stored under {@code fileId} from the configured database.
     *
     * @param fileId the file's {@link StoredFile#fileId() file id}
     * @throws DatabaseClientException if no file exists under {@code fileId}, or the persistence operation otherwise fails
     */
    public abstract void delete(@NotNull String fileId) throws DatabaseClientException;

    /**
     * Deletes every file stored under {@code fileIds}, the same way {@link
     * #delete(String)} deletes a single file. Implementations dispatch ids
     * concurrently rather than one at a time - see the concrete
     * implementation's Javadoc for the exact failure semantics of a batch
     * with more than one failing id.
     *
     * @param fileIds the files' {@link StoredFile#fileId() file ids}
     * @throws DatabaseClientException if no file exists under any of {@code fileIds}, or any persistence operation otherwise fails
     */
    public abstract void delete(@NotNull String[] fileIds) throws DatabaseClientException;

    /**
     * Clears every stored file, leaving the underlying database section
     * itself intact - the files are gone, but the section they were stored
     * in still exists and is ready to receive new ones. Use {@link
     * #deleteSection} instead to remove the section itself. See {@link
     * DataFactory#clear}.
     */
    public abstract void clear();

    /**
     * Deletes the database section files are stored in entirely - not just
     * its entries, the section itself. A later {@link #upload} lazily
     * recreates the section. See {@link DataFactory#deleteSection}.
     */
    public abstract void deleteSection();

    /**
     * The descriptive attributes of the file stored under {@code fileId},
     * without its content - see {@link FileMetadata}. Built on {@link
     * #findById}, so it pays the same cost as a full download (this
     * contract has no partial-fetch storage layout to exploit) but spares
     * the caller from having to hold the file's content just to inspect its
     * name, type, size, checksum, or timestamps.
     *
     * @param fileId the file's {@link StoredFile#fileId() file id}
     * @return the file's metadata, or {@link Optional#empty()} if no file exists under {@code fileId}
     * @throws DatabaseClientException if the file exists but its record is corrupted
     * @throws KeyWrapException if the file's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     * @throws FileIntegrityException if the decrypted content does not match its recorded checksum
     */
    @NotNull
    public Optional<FileMetadata> metadata(@NotNull final String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return findById(fileId).map(StoredFile::metadata);
    }

    /**
     * Async counterpart of {@link #upload(StoredFile)}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor so the calling
     * thread never blocks on database or KMS I/O. On failure, the returned
     * future completes exceptionally with a {@link CompletionException}
     * wrapping the checked exception {@link #upload(StoredFile)} would
     * otherwise have thrown.
     */
    @NotNull
    public CompletableFuture<Void> uploadAsync(@NotNull final StoredFile file) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.upload(file);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #upload(StoredFile[])}.
     */
    @NotNull
    public CompletableFuture<Void> uploadAsync(@NotNull final StoredFile... files) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.upload(files);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #download(String)}.
     */
    @NotNull
    public CompletableFuture<StoredFile> downloadAsync(@NotNull final String fileId) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.download(fileId);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #download(String[])}.
     */
    @NotNull
    public CompletableFuture<List<StoredFile>> downloadAsync(@NotNull final String[] fileIds) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.download(fileIds);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #findById(String)}.
     */
    @NotNull
    public CompletableFuture<Optional<StoredFile>> findByIdAsync(@NotNull final String fileId) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.findById(fileId);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #getEntities()}.
     */
    @NotNull
    public CompletableFuture<List<StoredFile>> getEntitiesAsync() {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.getEntities();
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #metadata(String)}.
     */
    @NotNull
    public CompletableFuture<Optional<FileMetadata>> metadataAsync(@NotNull final String fileId) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.metadata(fileId);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #delete(String)}.
     */
    @NotNull
    public CompletableFuture<Void> deleteAsync(@NotNull final String fileId) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.delete(fileId);
            } catch (final DatabaseClientException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #delete(String[])}.
     */
    @NotNull
    public CompletableFuture<Void> deleteAsync(@NotNull final String[] fileIds) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.delete(fileIds);
            } catch (final DatabaseClientException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #clear()}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor.
     */
    @NotNull
    public CompletableFuture<Void> clearAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::clear);
    }

    /**
     * Async counterpart of {@link #deleteSection()}.
     */
    @NotNull
    public CompletableFuture<Void> deleteSectionAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::deleteSection);
    }

}
