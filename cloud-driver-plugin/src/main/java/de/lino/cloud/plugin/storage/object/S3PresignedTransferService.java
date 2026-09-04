package de.lino.cloud.plugin.storage.object;

import de.lino.cloud.api.storage.object.ObjectStorageException;
import de.lino.cloud.api.storage.object.PresignedDownload;
import de.lino.cloud.api.storage.object.PresignedTransferService;
import de.lino.cloud.api.storage.object.PresignedUpload;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Production {@link PresignedTransferService} backed by AWS S3 - credential/config resolution and
 * "not wired in as a default" convention mirror {@link S3ObjectStorageService}'s own (see that
 * class's Javadoc); the two are deliberately separate classes despite sharing a bucket, since
 * generating a presigned URL needs {@link S3Presigner}/a plain {@link S3Client}, not {@link
 * S3ObjectStorageService}'s {@code S3TransferManager} - no bytes move through this process at all
 * for either operation.
 *
 * <p><strong>Encryption: SSE-S3 (AES256), not this project's own AES-256-GCM/DEK-KEK scheme.</strong>
 * A file uploaded through this path never reaches this server, so there is no plaintext here for
 * {@code EnvelopeEncryptionService} to encrypt - confidentiality at rest instead comes from S3's
 * own server-side encryption, applied transparently by AWS. {@link #presignUpload} signs the
 * upload with {@code x-amz-server-side-encryption: AES256} as part of the request (returned in
 * {@link PresignedUpload#requiredHeaders()} - the client's actual {@code PUT} must replay it
 * exactly, or the signature no longer matches and S3 rejects the request); {@link #presignDownload}
 * needs no equivalent header - S3 decrypts on read transparently for anyone with {@code
 * s3:GetObject}. SSE-KMS was considered and deliberately not used for this first pass: it would
 * need the signing principal (this server's own IAM identity, not the client's - the client never
 * has AWS credentials of its own here) to additionally hold {@code kms:GenerateDataKey} on a CMK,
 * a second piece of IAM/KMS provisioning beyond what {@link S3ObjectStorageService}'s own AES-256-GCM
 * path already needs; swap {@link ServerSideEncryption#AES256} for {@link ServerSideEncryption#AWS_KMS}
 * (plus {@code ssekmsKeyId(...)}) here if that trade-off is ever revisited.
 *
 * <p><strong>Required IAM permissions</strong> on the bucket, for whichever identity signs these
 * URLs: {@code s3:PutObject}, {@code s3:GetObject} (the same two {@link S3ObjectStorageService}
 * already needs) - a presigned URL only ever grants the exact permission its signing principal
 * already has, nothing more.
 */
public final class S3PresignedTransferService implements PresignedTransferService {

    /** Signed into every {@link #presignUpload} request, and required back from the client's own {@code PUT} - see this class's own Javadoc. */
    private static final ServerSideEncryption UPLOAD_SERVER_SIDE_ENCRYPTION = ServerSideEncryption.AES256;

    /** The presigner every {@link #presignUpload}/{@link #presignDownload} call goes through. */
    private final S3Presigner s3Presigner;
    /** A plain, synchronous client - only ever used for {@link #headObjectContentLength}'s single, quick call. */
    private final S3Client s3Client;
    /** The S3 bucket every presigned URL is scoped to. */
    private final String bucket;
    /** Prepended (with a separating {@code /}) to every {@code objectKey}; {@code ""} for no prefix - kept consistent with whatever {@link S3ObjectStorageService} in the same deployment uses. */
    private final String keyPrefix;

    /**
     * Defaults {@link #keyPrefix} to {@code ""} (no prefix).
     *
     * @param region the AWS region {@code bucket} lives in
     * @param bucket the S3 bucket to presign URLs against
     * @throws NullPointerException if {@code region} or {@code bucket} is {@code null}
     */
    public S3PresignedTransferService(@NotNull final Region region, @NotNull final String bucket) {
        this(region, bucket, "");
    }

    /**
     * @param region the AWS region {@code bucket} lives in
     * @param bucket the S3 bucket to presign URLs against
     * @param keyPrefix prepended (with a separating {@code /}) to every {@code objectKey}; {@code ""} for no prefix
     * @throws NullPointerException if any argument is {@code null}
     */
    public S3PresignedTransferService(@NotNull final Region region, @NotNull final String bucket, @NotNull final String keyPrefix) {
        Asserts.requireNonNull(region, "@S3PresignedTransferService: region cannot be null");
        this.bucket = Asserts.requireNonNull(bucket, "@S3PresignedTransferService: bucket cannot be null");
        this.keyPrefix = Asserts.requireNonNull(keyPrefix, "@S3PresignedTransferService: keyPrefix cannot be null");
        this.s3Presigner = S3Presigner.builder().region(region).build();
        this.s3Client = S3Client.builder().region(region).build();
    }

    /**
     * {@inheritDoc} Signs {@link #UPLOAD_SERVER_SIDE_ENCRYPTION} into the request - see this
     * class's own Javadoc. {@code contentLength} is deliberately <b>not</b> signed into the
     * request or returned in {@link PresignedUpload#requiredHeaders()}: {@code Content-Length} is
     * a restricted/forbidden header on every HTTP client this codebase's own clients use (the JDK
     * {@code java.net.http.HttpClient} throws {@code IllegalArgumentException: restricted header
     * name: "Content-Length"} the moment a caller tries to set it directly via {@code
     * HttpRequest.Builder#header} - confirmed the hard way, 2026-09-04, against a real desktop
     * upload; {@code URLSession} on Apple platforms manages it the same way) - it's always
     * computed automatically by the HTTP client from the request body/file being sent, never
     * settable by user code. S3 doesn't need it pre-declared in the signature for a plain
     * (non-multipart) presigned {@code PUT} either way - the real uploaded size is independently
     * verified server-side afterward via {@link #headObjectContentLength}, not trusted from this
     * signing step at all.
     */
    @NotNull
    @Override
    public PresignedUpload presignUpload(@NotNull final String objectKey, final long contentLength, @NotNull final Duration expiry) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3PresignedTransferService.presignUpload: objectKey cannot be null");
        Asserts.requireNonNull(expiry, "@S3PresignedTransferService.presignUpload: expiry cannot be null");
        final String key = resolveKey(objectKey);

        try {
            final PresignedPutObjectRequest presigned = this.s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .putObjectRequest(builder -> builder
                            .bucket(this.bucket)
                            .key(key)
                            .serverSideEncryption(UPLOAD_SERVER_SIDE_ENCRYPTION))
                    .build());

            final Map<String, String> requiredHeaders = new LinkedHashMap<>();
            requiredHeaders.put("x-amz-server-side-encryption", UPLOAD_SERVER_SIDE_ENCRYPTION.toString());

            return new PresignedUpload(presigned.url(), requiredHeaders, presigned.expiration());
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3PresignedTransferService.presignUpload: failed to presign an upload for key '" + key + "' in bucket '" + this.bucket + "'", e
            );
        }
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public PresignedDownload presignDownload(@NotNull final String objectKey, @NotNull final Duration expiry) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3PresignedTransferService.presignDownload: objectKey cannot be null");
        Asserts.requireNonNull(expiry, "@S3PresignedTransferService.presignDownload: expiry cannot be null");
        final String key = resolveKey(objectKey);

        try {
            final PresignedGetObjectRequest presigned = this.s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .getObjectRequest(builder -> builder.bucket(this.bucket).key(key))
                    .build());

            return new PresignedDownload(presigned.url(), presigned.expiration());
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3PresignedTransferService.presignDownload: failed to presign a download for key '" + key + "' in bucket '" + this.bucket + "'", e
            );
        }
    }

    /** {@inheritDoc} A missing object surfaces as {@link ObjectStorageException}, same as any other failure - this method has no "doesn't exist" success case, unlike {@code ObjectStorageService#exists}. */
    @Override
    public long headObjectContentLength(@NotNull final String objectKey) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3PresignedTransferService.headObjectContentLength: objectKey cannot be null");
        final String key = resolveKey(objectKey);

        try {
            final HeadObjectResponse response = this.s3Client.headObject(builder -> builder.bucket(this.bucket).key(key));
            final Long contentLength = response.contentLength();
            if (contentLength == null) {
                throw new ObjectStorageException(
                        "@S3PresignedTransferService.headObjectContentLength: no content length reported for key '" + key + "' in bucket '" + this.bucket + "'"
                );
            }
            return contentLength;
        } catch (final NoSuchKeyException e) {
            throw new ObjectStorageException(
                    "@S3PresignedTransferService.headObjectContentLength: no object exists under key '" + key + "' in bucket '" + this.bucket + "'", e
            );
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3PresignedTransferService.headObjectContentLength: HEAD failed for key '" + key + "' in bucket '" + this.bucket + "'", e
            );
        }
    }

    /** {@inheritDoc} A no-op (not an error) if {@code objectKey} doesn't exist - S3's own {@code DeleteObject} is already idempotent-on-absence. */
    @Override
    public void deleteObject(@NotNull final String objectKey) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3PresignedTransferService.deleteObject: objectKey cannot be null");
        final String key = resolveKey(objectKey);
        try {
            this.s3Client.deleteObject(builder -> builder.bucket(this.bucket).key(key));
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3PresignedTransferService.deleteObject: delete failed for key '" + key + "' in bucket '" + this.bucket + "'", e
            );
        }
    }

    /**
     * Prepends {@link #keyPrefix} (with a separating {@code /}) to {@code objectKey}, or returns
     * {@code objectKey} unchanged if {@link #keyPrefix} is empty - the same convention {@link
     * S3ObjectStorageService#resolveKey} uses, kept consistent so both classes resolve the same
     * {@code objectKey} to the same real S3 key.
     */
    private String resolveKey(final String objectKey) {
        return this.keyPrefix.isEmpty() ? objectKey : this.keyPrefix + "/" + objectKey;
    }
}
