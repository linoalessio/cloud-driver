package de.lino.cloud.api.storage.object;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

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
     * Streams {@code content} into storage under {@code objectKey} without requiring the caller to
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
}
