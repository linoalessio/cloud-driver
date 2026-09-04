package de.lino.cloud.api.s3storage;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Abstraction over generating short-lived, signed URLs a client can upload/download a {@link
 * de.lino.cloud.api.file.StoredFile}'s content directly to/from an external object store with -
 * bypassing this server entirely for the data path. The same "contract in {@code
 * cloud-driver-api}, production implementation ({@code S3PresignedTransferService}) in {@code
 * cloud-driver-plugin}" shape {@link ObjectStorageService} already uses, and deliberately a
 * separate interface from it: generating a presigned URL moves no bytes at all, and not every
 * {@link ObjectStorageService} implementation could support it (a hypothetical local-disk one,
 * for instance).
 *
 * <p>Unlike {@link ObjectStorageService}, a file transferred this way is <b>not</b> encrypted by
 * this application's own {@code EnvelopeEncryptionService} - the whole point is that content
 * never reaches this server, so there is nothing here to encrypt. Confidentiality at rest instead
 * comes from the object store's own server-side encryption (see {@code S3PresignedTransferService}'s
 * own Javadoc for exactly which mode) - a deliberate, explicit deviation from {@code
 * architecture/SECURITY_REQUIREMENTS.md}'s documented app-controlled DEK/KEK guarantee, scoped
 * only to files that took this specific path.
 */
public interface PresignedTransferService {

    /**
     * Presigns a URL a client can {@code PUT} {@code contentLength} bytes to directly, valid for
     * {@code expiry}.
     *
     * @param objectKey the key the uploaded object will be stored under
     * @param contentLength the exact number of bytes the client intends to upload
     * @param expiry how long the returned URL stays valid
     * @return the presigned upload, including any headers the client's {@code PUT} must replay
     * @throws ObjectStorageException if generating the presigned URL fails
     */
    @NotNull
    PresignedUpload presignUpload(@NotNull String objectKey, long contentLength, @NotNull Duration expiry) throws ObjectStorageException;

    /**
     * Presigns a URL a client can {@code GET} an object's bytes directly from, valid for {@code expiry}.
     *
     * @param objectKey the key of the object to download
     * @param expiry how long the returned URL stays valid
     * @return the presigned download
     * @throws ObjectStorageException if generating the presigned URL fails
     */
    @NotNull
    PresignedDownload presignDownload(@NotNull String objectKey, @NotNull Duration expiry) throws ObjectStorageException;

    /**
     * Looks up {@code objectKey}'s real, already-stored content length - used to verify a client's
     * declared upload size against what was actually written, once a presigned upload is reported
     * complete.
     *
     * @param objectKey the object's key
     * @return the object's real size, in bytes
     * @throws ObjectStorageException if no object exists under {@code objectKey}, or the lookup fails
     */
    long headObjectContentLength(@NotNull String objectKey) throws ObjectStorageException;

    /**
     * Deletes {@code objectKey}, if it exists - used to clean up an object a client uploaded
     * directly (bypassing {@link ObjectStorageService} entirely) that turned out to violate a
     * constraint only checkable once the upload is complete (e.g. the account's upload quota).
     * Same idempotent-on-absence contract as {@link ObjectStorageService#deleteObject(String)}.
     *
     * @param objectKey the object's key
     * @throws ObjectStorageException if the delete call itself fails (not if the key was already absent)
     */
    void deleteObject(@NotNull String objectKey) throws ObjectStorageException;
}
