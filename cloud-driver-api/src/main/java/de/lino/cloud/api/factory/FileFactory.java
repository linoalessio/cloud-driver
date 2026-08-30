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
 * the file-persistence counterpart of {@link DataFactory}, reached through
 * {@code CloudDriver#getFactoryContainer()}'s {@code getFileFactory()}. Every
 * download verifies both the AES-256-GCM authentication tag over the stored
 * ciphertext ({@link AuthenticationFailedException}) and the plaintext
 * checksum against {@link StoredFile#checksum()} ({@link
 * FileIntegrityException}) before handing content back.
 *
 * <p>{@link #upload(StoredFile)}, {@link #upload(StoredFile[])}, {@link
 * #download(String)}, {@link #download(String[])}, {@link #findById}, {@link
 * #getEntities()}, {@link #delete(String)}, {@link #delete(String[])},
 * {@link #clear()}, and {@link #deleteSection()} are abstract; every {@code
 * *Async} variant plus {@link #metadata} is implemented here generically in
 * terms of those.
 */
public abstract class FileFactory {

    /**
     * Encrypts {@code file} and stores it under its {@link
     * StoredFile#fileId() file id}, inserting or overwriting as needed.
     *
     * @param file the file to store, of any content type
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the file's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract void upload(@NotNull StoredFile file) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts and stores every file in {@code files}, concurrently.
     *
     * @param files the files to store
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any file's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract void upload(@NotNull StoredFile... files) throws DatabaseClientException, KeyWrapException;

    /**
     * Retrieves, decrypts, and integrity-verifies the file stored under
     * {@code fileId} - see the class Javadoc for the two checks performed.
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
     * Retrieves every file in {@code fileIds}, in the same order.
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
     * Looks up the file stored under {@code fileId}, returning {@link
     * Optional#empty()} instead of throwing when it doesn't exist. A
     * corrupted record, an unwrappable key, a failed authentication check,
     * or a checksum mismatch still throw, since those are real failures,
     * not absence.
     *
     * @param fileId the file's {@link StoredFile#fileId() file id}
     * @return the decrypted, integrity-verified file, or {@link Optional#empty()} if none exists under {@code fileId}
     * @throws DatabaseClientException if the file exists but its record is corrupted
     * @throws KeyWrapException if the file's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     * @throws FileIntegrityException if the decrypted content does not match its recorded checksum
     */
    @NotNull
    public abstract Optional<StoredFile> findById(@NotNull String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException;

    /**
     * Retrieves, decrypts, and integrity-verifies every stored file.
     *
     * @return every stored, integrity-verified file, in no particular order
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if any file's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     * @throws FileIntegrityException if any decrypted content does not match its recorded checksum
     */
    @NotNull
    public abstract List<StoredFile> getEntities()
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException;

    /**
     * Deletes the file stored under {@code fileId}.
     *
     * @param fileId the file's {@link StoredFile#fileId() file id}
     * @throws DatabaseClientException if no file exists under {@code fileId}, or persistence otherwise fails
     */
    public abstract void delete(@NotNull String fileId) throws DatabaseClientException;

    /**
     * Deletes every file in {@code fileIds}, concurrently.
     *
     * @param fileIds the files' {@link StoredFile#fileId() file ids}
     * @throws DatabaseClientException if no file exists under any of {@code fileIds}, or persistence otherwise fails
     */
    public abstract void delete(@NotNull String[] fileIds) throws DatabaseClientException;

    /**
     * Clears every stored file, leaving the underlying database section
     * itself intact. Use {@link #deleteSection} to remove the section too.
     */
    public abstract void clear();

    /**
     * Deletes the database section files are stored in entirely, section
     * included. A later {@link #upload} lazily recreates it.
     */
    public abstract void deleteSection();

    /**
     * The descriptive attributes of the file stored under {@code fileId},
     * without its content - built on {@link #findById}, so it pays the same
     * cost as a full download.
     *
     * @param fileId the file's {@link StoredFile#fileId() file id}
     * @return the file's metadata, or {@link Optional#empty()} if none exists under {@code fileId}
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

    /** Async counterpart of {@link #upload(StoredFile)}. */
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

    /** Async counterpart of {@link #upload(StoredFile[])}. */
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

    /** Async counterpart of {@link #download(String)}. */
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

    /** Async counterpart of {@link #download(String[])}. */
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

    /** Async counterpart of {@link #findById(String)}. */
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

    /** Async counterpart of {@link #getEntities()}. */
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

    /** Async counterpart of {@link #metadata(String)}. */
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

    /** Async counterpart of {@link #delete(String)}. */
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

    /** Async counterpart of {@link #delete(String[])}. */
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

    /** Async counterpart of {@link #clear()}. */
    @NotNull
    public CompletableFuture<Void> clearAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::clear);
    }

    /** Async counterpart of {@link #deleteSection()}. */
    @NotNull
    public CompletableFuture<Void> deleteSectionAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::deleteSection);
    }

}
