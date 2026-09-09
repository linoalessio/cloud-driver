package de.lino.cloud.api.s3storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.List;

/**
 * Abstraction over a content-addressable/key-addressable binary object store for {@link
 * de.lino.cloud.api.file.StoredFile} content - the same "contract in {@code cloud-driver-api},
 * production implementation ({@code S3ObjectStorageService}) in {@code cloud-driver-plugin}"
 * shape {@code KeyEncryptionService} already uses. Implementations do not perform any encryption
 * of their own - callers (see {@code DefaultFileFactory}) are responsible for handing this
 * service ciphertext bytes that are already the output of {@code EnvelopeEncryptionService}, the
 * same way {@code EntityDatabaseClient} never encrypts on the database's behalf either.
 */
public interface ObjectStorageService {

    /**
     * Stores {@code content} under {@code objectKey}, overwriting any existing object at that key.
     *
     * @param objectKey the key to store the object under - see {@code S3ObjectStorageService}'s
     *     Javadoc for this implementation's key naming convention
     * @param content the bytes to store, already encrypted by the caller
     * @throws ObjectStorageException if the underlying store rejects or fails the write
     */
    void putObject(@NotNull String objectKey, byte[] content) throws ObjectStorageException;

    /**
     * Streams {@code content} into s3storage under {@code objectKey} without requiring the caller to
     * hold the entire object in memory at once - prefer this over {@link #putObject(String, byte[])}
     * for large files.
     *
     * @param objectKey the key to store the object under
     * @param content a stream of the (already-encrypted) bytes to store
     * @param contentLength the exact number of bytes {@code content} will yield - required
     *     up-front by most object stores' multipart upload APIs
     * @throws ObjectStorageException if the underlying store rejects or fails the write
     */
    void putObject(@NotNull String objectKey, @NotNull InputStream content, long contentLength) throws ObjectStorageException;

    /**
     * Retrieves the full object stored under {@code objectKey}.
     *
     * @param objectKey the object's key
     * @return the object's raw (still-encrypted) bytes
     * @throws ObjectStorageException if no object exists under {@code objectKey}, or retrieval fails
     */
    @NotNull
    byte[] getObject(@NotNull String objectKey) throws ObjectStorageException;

    /**
     * Same as {@link #getObject(String)}, but as a stream - prefer this for large files so the
     * caller can pipe bytes straight through (e.g. into a Javalin streamed HTTP response) rather
     * than buffering the whole object first.
     *
     * @param objectKey the object's key
     * @return a stream of the object's raw (still-encrypted) bytes; the caller must close it
     * @throws ObjectStorageException if no object exists under {@code objectKey}, or retrieval fails
     */
    @NotNull
    InputStream getObjectStream(@NotNull String objectKey) throws ObjectStorageException;

    /**
     * Deletes the object stored under {@code objectKey}, if any. A no-op (not an error) if nothing
     * exists under that key - unlike {@code DataFactory#delete}'s throw-on-missing contract, this
     * method must NOT throw for a missing key, since callers (e.g. a future trash-purge job) may
     * retry a delete that already succeeded.
     *
     * @param objectKey the object's key
     * @throws ObjectStorageException if the delete call itself fails (not if the key was already absent)
     */
    void deleteObject(@NotNull String objectKey) throws ObjectStorageException;

    /**
     * @param objectKey the object's key
     * @return {@code true} if an object currently exists under {@code objectKey}
     * @throws ObjectStorageException if checking existence fails
     */
    boolean exists(@NotNull String objectKey) throws ObjectStorageException;

    /**
     * Lists one page of the object keys currently held by this store, for reconciliation/audit
     * purposes - the counterpart every other method here deliberately lacks, since normal
     * operation only ever addresses an object by a key the caller already knows.
     *
     * <p><b>Why this exists at all.</b> Nothing in this codebase reconciles the object store
     * against the {@code StoredFile} rows that are supposed to reference it, and that gap has
     * already cost real money: an abandoned-presigned-upload leak left 1.1 GB across 121,728
     * objects in the live bucket while only ~400 MB of files had ever actually been uploaded
     * through the app (see {@code PendingPresignedUploadPurgeScheduler} for the fix to that
     * specific cause). Two known gaps still orphan objects - {@code FileFactory#clear()} and
     * {@code #deleteSection()} never purge - so a periodic audit remains the only way to notice.
     *
     * <p><b>Deliberately paginated rather than returning every key at once.</b> A bucket at the
     * scale above would materialize a six-figure {@code List} in heap, precisely the class of
     * unbounded full-scan this codebase has already been bitten by (see {@code
     * EntityDatabaseClient#getEntities}'s own {@code OutOfMemoryError} incidents). A caller
     * streams pages and keeps only what it needs.
     *
     * <p>Keys are returned <b>without</b> any implementation-side prefix (the {@code keyPrefix} an
     * {@code S3ObjectStorageService} may be configured with), so a returned key is directly
     * comparable with the {@code objectKey} a caller originally passed to {@link #putObject} - in
     * this codebase always a {@code StoredFile#fileId()}.
     *
     * @param continuationToken the {@link ObjectListing#nextContinuationToken()} of the previous
     * page, or {@code null} to start from the beginning
     * @param maxKeys the maximum number of keys to return in this page; implementations may return
     * fewer even when more remain, so a caller must page until {@link
     * ObjectListing#nextContinuationToken()} is {@code null} rather than until a short page
     * @return one page of keys plus the token needed to request the next
     * @throws ObjectStorageException if the listing call itself fails
     */
    @NotNull
    ObjectListing listObjects(@Nullable String continuationToken, int maxKeys) throws ObjectStorageException;

    /**
     * One page of {@link #listObjects}.
     *
     * @param keys this page's object keys, prefix already stripped
     * @param nextContinuationToken the token to pass back to {@link #listObjects} for the next
     * page, or {@code null} when this was the last page
     */
    record ObjectListing(@NotNull List<String> keys, @Nullable String nextContinuationToken) {
    }
}
