package de.lino.cloud.api.s3storage;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;

/**
 * The thin object-store boundary behind resumable multipart upload sessions (roadmap Phase 5):
 * explicit control over S3's own multipart-upload primitives - create, per-part presigned URLs,
 * a durable "which parts actually landed" listing, complete, abort - so a client that loses
 * connectivity mid-transfer can ask what the store already holds and re-send only the missing
 * parts, rather than restarting a large upload from zero.
 *
 * <p>Deliberately as thin as {@link PresignedTransferService}: no session bookkeeping, no
 * business rules - {@code CloudUserService} owns those (session persistence via its
 * pending-upload tracking rows, quota/dedup/ownership checks, completion verification). This
 * interface exists so that logic is testable against an in-memory fake, with the one real
 * implementation ({@code S3ResumableUploadService}) staying a direct wrapper over the AWS SDK.
 *
 * <p>{@code getStatus}-style reads go through {@link #listUploadedParts} against the store
 * itself (S3's {@code ListParts}), never a local mirror - the store is the only honest source
 * of truth for what is durably uploaded.
 *
 * <p>An incomplete multipart upload accrues storage cost until explicitly aborted - {@code
 * PendingPresignedUploadPurgeScheduler} sweeps abandoned sessions through {@link
 * #abortMultipartUpload}, which is cost control, not optional tidying.
 */
public interface ResumableUploadService {

    /**
     * Starts a multipart upload for {@code objectKey}.
     *
     * @param objectKey the object key the completed upload will live under
     * @return the store's upload id for this multipart upload
     * @throws ObjectStorageException if the store rejects the request
     */
    @NotNull
    String createMultipartUpload(@NotNull String objectKey) throws ObjectStorageException;

    /**
     * Presigns one part's upload URL - the client {@code PUT}s that part's bytes there
     * directly, exactly like a whole-object presigned upload but scoped to one part.
     *
     * @param objectKey the multipart upload's object key
     * @param uploadId the store's upload id, from {@link #createMultipartUpload}
     * @param partNumber the 1-based part number
     * @param expiry how long the URL stays valid
     * @return the presigned part upload
     * @throws ObjectStorageException if presigning fails
     */
    @NotNull
    PresignedUpload presignPart(@NotNull String objectKey, @NotNull String uploadId, int partNumber,
                                 @NotNull Duration expiry) throws ObjectStorageException;

    /**
     * Every part durably stored so far for this multipart upload, straight from the store
     * ({@code ListParts}) - the resume primitive: after a reconnect, a client re-sends exactly
     * the part numbers missing from this map.
     *
     * @param objectKey the multipart upload's object key
     * @param uploadId the store's upload id
     * @return {@code part number → the store's ETag for it}, empty if nothing landed yet
     * @throws ObjectStorageException if the listing fails (an unknown/aborted upload id included)
     */
    @NotNull
    Map<Integer, String> listUploadedParts(@NotNull String objectKey, @NotNull String uploadId) throws ObjectStorageException;

    /**
     * Completes the multipart upload - the store assembles the parts, in part-number order,
     * into the final object under {@code objectKey}.
     *
     * @param objectKey the multipart upload's object key
     * @param uploadId the store's upload id
     * @param partETags {@code part number → ETag}, exactly as {@link #listUploadedParts} reported
     * @throws ObjectStorageException if completion fails
     */
    void completeMultipartUpload(@NotNull String objectKey, @NotNull String uploadId,
                                  @NotNull Map<Integer, String> partETags) throws ObjectStorageException;

    /**
     * Aborts the multipart upload, discarding every already-uploaded part - the store stops
     * billing for them. Idempotent-on-absence: aborting an unknown/already-aborted upload id is
     * a no-op, not an error.
     *
     * @param objectKey the multipart upload's object key
     * @param uploadId the store's upload id
     * @throws ObjectStorageException if the abort genuinely fails
     */
    void abortMultipartUpload(@NotNull String objectKey, @NotNull String uploadId) throws ObjectStorageException;
}
