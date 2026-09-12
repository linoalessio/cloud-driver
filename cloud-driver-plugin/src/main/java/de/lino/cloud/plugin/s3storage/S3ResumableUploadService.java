package de.lino.cloud.plugin.s3storage;

import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.PresignedUpload;
import de.lino.cloud.api.s3storage.ResumableUploadService;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Production {@link ResumableUploadService} backed by S3's own multipart-upload API - the same
 * "thin wrapper, caller resolves config, opt-in wiring" philosophy {@link
 * S3PresignedTransferService} documents, extended to explicit multipart control: {@code
 * CreateMultipartUpload}/{@code UploadPart} (presigned, so part bytes go client → S3 directly,
 * never through this server)/{@code ListParts}/{@code CompleteMultipartUpload}/{@code
 * AbortMultipartUpload}. Session bookkeeping and every business rule live in {@code
 * CloudUserService}, per the interface's own contract.
 *
 * <p>Unlike {@link S3PresignedTransferService}'s whole-object uploads, no server-side-encryption
 * header is signed per part - SSE is declared once, on the {@code CreateMultipartUpload} call,
 * and applies to the assembled object.
 *
 * <p><strong>Required IAM permissions</strong> beyond {@code S3ObjectStorageService}'s set:
 * {@code s3:AbortMultipartUpload} and {@code s3:ListMultipartUploadParts} on the bucket.
 */
public final class S3ResumableUploadService implements ResumableUploadService {

    /** The presigner every {@link #presignPart} call goes through. */
    private final S3Presigner s3Presigner;
    /** A plain, synchronous client for the non-presigned calls (create/list/complete/abort). */
    private final S3Client s3Client;
    /** The S3 bucket every multipart upload is scoped to. */
    private final String bucket;
    /** Prepended (with a separating {@code /}) to every {@code objectKey}; {@code ""} for no prefix - kept consistent with the deployment's other S3 services. */
    private final String keyPrefix;

    /**
     * Defaults {@link #keyPrefix} to {@code ""} (no prefix).
     *
     * @param region the AWS region {@code bucket} lives in
     * @param bucket the S3 bucket to run multipart uploads against
     * @throws NullPointerException if {@code region} or {@code bucket} is {@code null}
     */
    public S3ResumableUploadService(@NotNull final Region region, @NotNull final String bucket) {
        this(region, bucket, "");
    }

    /**
     * @param region the AWS region {@code bucket} lives in
     * @param bucket the S3 bucket to run multipart uploads against
     * @param keyPrefix prepended (with a separating {@code /}) to every {@code objectKey}; {@code ""} for no prefix
     * @throws NullPointerException if any argument is {@code null}
     */
    public S3ResumableUploadService(@NotNull final Region region, @NotNull final String bucket, @NotNull final String keyPrefix) {
        Asserts.requireNonNull(region, "@S3ResumableUploadService: region cannot be null");
        this.bucket = Asserts.requireNonNull(bucket, "@S3ResumableUploadService: bucket cannot be null");
        this.keyPrefix = Asserts.requireNonNull(keyPrefix, "@S3ResumableUploadService: keyPrefix cannot be null");
        this.s3Presigner = S3Presigner.builder().region(region).build();
        this.s3Client = S3Client.builder().region(region).build();
    }

    /** {@inheritDoc} SSE-S3 is declared here, once, for the assembled object - matching {@link S3PresignedTransferService}'s defense-in-depth posture. */
    @NotNull
    @Override
    public String createMultipartUpload(@NotNull final String objectKey) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ResumableUploadService.createMultipartUpload: objectKey cannot be null");
        final String key = resolveKey(objectKey);
        try {
            return this.s3Client.createMultipartUpload(builder -> builder
                            .bucket(this.bucket).key(key)
                            .serverSideEncryption(software.amazon.awssdk.services.s3.model.ServerSideEncryption.AES256))
                    .uploadId();
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3ResumableUploadService.createMultipartUpload: failed for key '" + key + "' in bucket '" + this.bucket + "'", e);
        }
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public PresignedUpload presignPart(@NotNull final String objectKey, @NotNull final String uploadId,
                                        final int partNumber, @NotNull final Duration expiry) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ResumableUploadService.presignPart: objectKey cannot be null");
        Asserts.requireNonNull(uploadId, "@S3ResumableUploadService.presignPart: uploadId cannot be null");
        Asserts.requireNonNull(expiry, "@S3ResumableUploadService.presignPart: expiry cannot be null");
        final String key = resolveKey(objectKey);
        try {
            final PresignedUploadPartRequest presigned = this.s3Presigner.presignUploadPart(UploadPartPresignRequest.builder()
                    .signatureDuration(expiry)
                    .uploadPartRequest(builder -> builder
                            .bucket(this.bucket).key(key).uploadId(uploadId).partNumber(partNumber))
                    .build());
            return new PresignedUpload(presigned.url(), new LinkedHashMap<>(), presigned.expiration());
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3ResumableUploadService.presignPart: failed for part " + partNumber + " of key '" + key + "'", e);
        }
    }

    /** {@inheritDoc} Pages through {@code ListParts} until S3 reports no more - a session can legitimately hold more parts than one page. */
    @NotNull
    @Override
    public Map<Integer, String> listUploadedParts(@NotNull final String objectKey, @NotNull final String uploadId) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ResumableUploadService.listUploadedParts: objectKey cannot be null");
        Asserts.requireNonNull(uploadId, "@S3ResumableUploadService.listUploadedParts: uploadId cannot be null");
        final String key = resolveKey(objectKey);
        try {
            final Map<Integer, String> parts = new TreeMap<>();
            Integer marker = null;
            while (true) {
                final ListPartsRequest.Builder request = ListPartsRequest.builder()
                        .bucket(this.bucket).key(key).uploadId(uploadId);
                if (marker != null) request.partNumberMarker(marker);
                final ListPartsResponse response = this.s3Client.listParts(request.build());
                for (final Part part : response.parts()) {
                    parts.put(part.partNumber(), part.eTag());
                }
                if (!Boolean.TRUE.equals(response.isTruncated())) {
                    return parts;
                }
                marker = response.nextPartNumberMarker();
            }
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3ResumableUploadService.listUploadedParts: failed for key '" + key + "', uploadId '" + uploadId + "'", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void completeMultipartUpload(@NotNull final String objectKey, @NotNull final String uploadId,
                                         @NotNull final Map<Integer, String> partETags) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ResumableUploadService.completeMultipartUpload: objectKey cannot be null");
        Asserts.requireNonNull(uploadId, "@S3ResumableUploadService.completeMultipartUpload: uploadId cannot be null");
        Asserts.requireNonNull(partETags, "@S3ResumableUploadService.completeMultipartUpload: partETags cannot be null");
        final String key = resolveKey(objectKey);
        final List<CompletedPart> completedParts = partETags.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(part -> CompletedPart.builder().partNumber(part.getKey()).eTag(part.getValue()).build())
                .toList();
        try {
            this.s3Client.completeMultipartUpload(builder -> builder
                    .bucket(this.bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build()));
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3ResumableUploadService.completeMultipartUpload: failed for key '" + key + "', uploadId '" + uploadId + "'", e);
        }
    }

    /** {@inheritDoc} S3's own {@code NoSuchUpload} is swallowed - already-aborted is this method's success case. */
    @Override
    public void abortMultipartUpload(@NotNull final String objectKey, @NotNull final String uploadId) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ResumableUploadService.abortMultipartUpload: objectKey cannot be null");
        Asserts.requireNonNull(uploadId, "@S3ResumableUploadService.abortMultipartUpload: uploadId cannot be null");
        final String key = resolveKey(objectKey);
        try {
            this.s3Client.abortMultipartUpload(builder -> builder.bucket(this.bucket).key(key).uploadId(uploadId));
        } catch (final NoSuchUploadException alreadyGone) {
            // Idempotent-on-absence, per the interface contract.
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3ResumableUploadService.abortMultipartUpload: failed for key '" + key + "', uploadId '" + uploadId + "'", e);
        }
    }

    /** Prepends {@link #keyPrefix} (with a separating {@code /}) to {@code objectKey}, or returns it unchanged if the prefix is empty. */
    private String resolveKey(final String objectKey) {
        return this.keyPrefix.isEmpty() ? objectKey : this.keyPrefix + "/" + objectKey;
    }
}
