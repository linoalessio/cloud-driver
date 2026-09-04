package de.lino.cloud.plugin.storage.object;

import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedUpload;
import software.amazon.awssdk.transfer.s3.model.Download;
import software.amazon.awssdk.transfer.s3.model.DownloadRequest;
import software.amazon.awssdk.transfer.s3.model.Upload;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.io.InputStream;
import java.util.concurrent.CompletionException;

/**
 * Production {@link ObjectStorageService} backed by AWS S3 - {@code StoredFile} content's object
 * key naming, credential resolution, and "not wired in as a default" convention all deliberately
 * mirror {@code de.lino.cloud.plugin.security.keys.AwsKmsKeyEncryptionService} (see that class's
 * own Javadoc, since both classes share the same design philosophy despite backing entirely
 * separate concerns - object storage, not key wrapping).
 *
 * <p><strong>Credentials/config resolution:</strong> this class takes a pre-built {@link
 * S3AsyncClient} (or a {@link Region} for the convenience constructor, which builds one via the
 * AWS SDK's own default credential provider chain - environment variables, {@code
 * ~/.aws/credentials}, an EC2/ECS instance role, ...) - it never reads {@code configuration.json}
 * itself. A caller wiring this in (e.g. {@code CloudBootstrap}) resolves {@code
 * "aws-s3-region"}/{@code "aws-s3-bucket"}/{@code "aws-s3-key-prefix"} from {@code
 * configuration.json} and passes the resolved values in, the same "caller resolves config, this
 * class just takes the resolved value" shape {@code AwsKmsKeyEncryptionService} uses.
 *
 * <p><strong>Multipart transfer.</strong> Uses the AWS SDK v2 S3 Transfer Manager ({@code
 * software.amazon.awssdk:s3-transfer-manager}) rather than {@link S3AsyncClient} alone - it
 * automatically splits a large {@link #putObject(String, InputStream, long)}/{@link
 * #getObjectStream(String)} transfer into parallel multipart requests above a size threshold, the
 * concrete mechanism behind the "streaming, no heap buffering, high throughput" benefit {@code
 * architecture/AWS_S3_IMPL.md} describes. The convenience constructor's {@link S3AsyncClient} is
 * built with {@code multipartEnabled(true)} (the pure-Java multipart uploader introduced in
 * recent AWS SDK v2 releases) rather than the CRT-based client ({@code S3AsyncClient#crtBuilder()})
 * - the CRT client needs an additional native-library dependency ({@code aws-crt-client}) this
 * module does not otherwise pull in, and the pure-Java multipart uploader already delivers the
 * same "streaming, parallel multipart" behavior this class needs.
 *
 * <p><strong>Object key naming</strong> (see {@link ObjectStorageService#putObject(String, byte[])}):
 * {@code {keyPrefix}/{objectKey}}, where {@code objectKey} is always {@code
 * StoredFile#fileId()} (already a UUID-shaped unique id) at every call site in this codebase - no
 * collision risk, no need to derive a content hash for the key. {@code keyPrefix} defaults to
 * {@code ""} (no prefix) via the two-argument constructors.
 *
 * <p><strong>Required IAM permissions</strong> on the configured bucket: {@code s3:PutObject},
 * {@code s3:GetObject}, {@code s3:DeleteObject}, {@code s3:AbortMultipartUpload} (multipart
 * uploads that fail mid-transfer must be cleanable), {@code s3:ListBucket} (multipart upload
 * completion needs to list in-progress parts).
 *
 * <p><strong>Not wired in as {@code CloudBootstrap}'s default</strong> - an operator opts into
 * this explicitly once an S3 bucket and IAM credentials are actually provisioned for a
 * deployment, exactly like {@code AwsKmsKeyEncryptionService} today.
 */
public final class S3ObjectStorageService implements ObjectStorageService {

    /** The underlying async S3 client every {@link #transferManager} operation ultimately runs through, and what {@link #deleteObject}/{@link #exists} call directly. */
    private final S3AsyncClient s3AsyncClient;
    /** Drives every {@link #putObject}/{@link #getObject}/{@link #getObjectStream} call - see this class's own Javadoc for why Transfer Manager rather than {@link #s3AsyncClient} alone. */
    private final S3TransferManager transferManager;
    /** The S3 bucket every object is stored in/read from. */
    private final String bucket;
    /** Prepended (with a separating {@code /}) to every {@code objectKey}; {@code ""} for no prefix. */
    private final String keyPrefix;

    /**
     * Defaults {@link #keyPrefix} to {@code ""} (no prefix).
     *
     * @param s3AsyncClient a fully configured async S3 client (region/credentials already resolved by the caller)
     * @param bucket the S3 bucket to store/read objects in
     * @throws NullPointerException if {@code s3AsyncClient} or {@code bucket} is {@code null}
     */
    public S3ObjectStorageService(@NotNull final S3AsyncClient s3AsyncClient, @NotNull final String bucket) {
        this(s3AsyncClient, bucket, "");
    }

    /**
     * @param s3AsyncClient a fully configured async S3 client (region/credentials already resolved by the caller)
     * @param bucket the S3 bucket to store/read objects in
     * @param keyPrefix prepended (with a separating {@code /}) to every {@code objectKey}; {@code ""} for no prefix
     * @throws NullPointerException if any argument is {@code null}
     */
    public S3ObjectStorageService(@NotNull final S3AsyncClient s3AsyncClient, @NotNull final String bucket, @NotNull final String keyPrefix) {
        this.s3AsyncClient = Asserts.requireNonNull(s3AsyncClient, "@S3ObjectStorageService: s3AsyncClient cannot be null");
        this.bucket = Asserts.requireNonNull(bucket, "@S3ObjectStorageService: bucket cannot be null");
        this.keyPrefix = Asserts.requireNonNull(keyPrefix, "@S3ObjectStorageService: keyPrefix cannot be null");
        this.transferManager = S3TransferManager.builder().s3Client(this.s3AsyncClient).build();
    }

    /**
     * Convenience constructor: a fresh {@link S3AsyncClient} for {@code region}, built via the AWS
     * SDK's default credential provider chain with multipart upload enabled - see this class's own
     * Javadoc for why credentials/multipart resolve this way. Defaults {@link #keyPrefix} to
     * {@code ""}.
     *
     * @param region the AWS region {@code bucket} lives in
     * @param bucket the S3 bucket to store/read objects in
     * @throws NullPointerException if {@code region} or {@code bucket} is {@code null}
     */
    public S3ObjectStorageService(@NotNull final Region region, @NotNull final String bucket) {
        this(region, bucket, "");
    }

    /**
     * Same as {@link #S3ObjectStorageService(Region, String)}, with an explicit {@code keyPrefix}.
     *
     * @param region the AWS region {@code bucket} lives in
     * @param bucket the S3 bucket to store/read objects in
     * @param keyPrefix prepended (with a separating {@code /}) to every {@code objectKey}; {@code ""} for no prefix
     * @throws NullPointerException if any argument is {@code null}
     */
    public S3ObjectStorageService(@NotNull final Region region, @NotNull final String bucket, @NotNull final String keyPrefix) {
        this(buildDefaultS3AsyncClient(Asserts.requireNonNull(region, "@S3ObjectStorageService: region cannot be null")), bucket, keyPrefix);
    }

    private static S3AsyncClient buildDefaultS3AsyncClient(final Region region) {
        return S3AsyncClient.builder().region(region).multipartEnabled(true).build();
    }

    /** {@inheritDoc} Uploaded via {@link #transferManager} ({@link AsyncRequestBody#fromBytes(byte[])}). */
    @Override
    public void putObject(@NotNull final String objectKey, final byte[] content) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ObjectStorageService.putObject: objectKey cannot be null");
        upload(objectKey, AsyncRequestBody.fromBytes(content));
    }

    /** {@inheritDoc} Uploaded via {@link #transferManager} ({@link AsyncRequestBody#fromInputStream(InputStream, Long, java.util.concurrent.ExecutorService)}), on {@link MultiTaskingFactory}'s shared virtual-thread executor. */
    @Override
    public void putObject(@NotNull final String objectKey, @NotNull final InputStream content, final long contentLength) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ObjectStorageService.putObject: objectKey cannot be null");
        Asserts.requireNonNull(content, "@S3ObjectStorageService.putObject: content cannot be null");
        upload(objectKey, AsyncRequestBody.fromInputStream(content, contentLength, MultiTaskingFactory.getExecutorService()));
    }

    private void upload(final String objectKey, final AsyncRequestBody requestBody) {
        final String key = resolveKey(objectKey);
        try {
            final Upload upload = this.transferManager.upload(UploadRequest.builder()
                    .putObjectRequest(builder -> builder.bucket(this.bucket).key(key))
                    .requestBody(requestBody)
                    .build());
            final CompletedUpload ignored = upload.completionFuture().join();
        } catch (final CompletionException | SdkException e) {
            throw new ObjectStorageException(
                    "@S3ObjectStorageService.putObject: upload failed for key '" + key + "' in bucket '" + this.bucket + "'", unwrap(e)
            );
        }
    }

    /** {@inheritDoc} Downloaded fully in-memory via {@link #transferManager} ({@link AsyncResponseTransformer#toBytes()}). */
    @NotNull
    @Override
    public byte[] getObject(@NotNull final String objectKey) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ObjectStorageService.getObject: objectKey cannot be null");
        final String key = resolveKey(objectKey);
        try {
            final DownloadRequest<ResponseBytes<GetObjectResponse>> request = DownloadRequest.builder()
                    .getObjectRequest(builder -> builder.bucket(this.bucket).key(key))
                    .responseTransformer(AsyncResponseTransformer.<GetObjectResponse>toBytes())
                    .build();
            final Download<ResponseBytes<GetObjectResponse>> download = this.transferManager.download(request);
            final CompletedDownload<ResponseBytes<GetObjectResponse>> completed = download.completionFuture().join();
            return completed.result().asByteArray();
        } catch (final CompletionException | SdkException e) {
            throw new ObjectStorageException(
                    "@S3ObjectStorageService.getObject: download failed for key '" + key + "' in bucket '" + this.bucket + "'", unwrap(e)
            );
        }
    }

    /** {@inheritDoc} Streamed via {@link #transferManager} ({@link AsyncResponseTransformer#toBlockingInputStream()}) - see {@code ObjectStorageService}'s own Javadoc for why the caller must close the returned stream. */
    @NotNull
    @Override
    public InputStream getObjectStream(@NotNull final String objectKey) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ObjectStorageService.getObjectStream: objectKey cannot be null");
        final String key = resolveKey(objectKey);
        try {
            final DownloadRequest<ResponseInputStream<GetObjectResponse>> request = DownloadRequest.builder()
                    .getObjectRequest(builder -> builder.bucket(this.bucket).key(key))
                    .responseTransformer(AsyncResponseTransformer.<GetObjectResponse>toBlockingInputStream())
                    .build();
            final Download<ResponseInputStream<GetObjectResponse>> download = this.transferManager.download(request);
            final CompletedDownload<ResponseInputStream<GetObjectResponse>> completed = download.completionFuture().join();
            return completed.result();
        } catch (final CompletionException | SdkException e) {
            throw new ObjectStorageException(
                    "@S3ObjectStorageService.getObjectStream: download failed for key '" + key + "' in bucket '" + this.bucket + "'", unwrap(e)
            );
        }
    }

    /** {@inheritDoc} A no-op (not an error) if {@code objectKey} doesn't exist - S3's own {@code DeleteObject} is already idempotent-on-absence, no extra existence check needed. */
    @Override
    public void deleteObject(@NotNull final String objectKey) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ObjectStorageService.deleteObject: objectKey cannot be null");
        final String key = resolveKey(objectKey);
        try {
            this.s3AsyncClient.deleteObject(builder -> builder.bucket(this.bucket).key(key)).join();
        } catch (final CompletionException | SdkException e) {
            throw new ObjectStorageException(
                    "@S3ObjectStorageService.deleteObject: delete failed for key '" + key + "' in bucket '" + this.bucket + "'", unwrap(e)
            );
        }
    }

    /** {@inheritDoc} Backed by {@code HeadObject}; a {@code 404} response (missing key/bucket) is treated as {@code false}, any other failure is rethrown. */
    @Override
    public boolean exists(@NotNull final String objectKey) throws ObjectStorageException {
        Asserts.requireNonNull(objectKey, "@S3ObjectStorageService.exists: objectKey cannot be null");
        final String key = resolveKey(objectKey);
        try {
            this.s3AsyncClient.headObject(builder -> builder.bucket(this.bucket).key(key)).join();
            return true;
        } catch (final CompletionException e) {
            final Throwable cause = unwrap(e);
            if (cause instanceof final S3Exception s3Exception && s3Exception.statusCode() == 404) {
                return false;
            }
            throw new ObjectStorageException(
                    "@S3ObjectStorageService.exists: existence check failed for key '" + key + "' in bucket '" + this.bucket + "'", cause
            );
        } catch (final SdkException e) {
            throw new ObjectStorageException(
                    "@S3ObjectStorageService.exists: existence check failed for key '" + key + "' in bucket '" + this.bucket + "'", e
            );
        }
    }

    /**
     * Prepends {@link #keyPrefix} (with a separating {@code /}) to {@code objectKey}, or returns
     * {@code objectKey} unchanged if {@link #keyPrefix} is empty.
     */
    private String resolveKey(final String objectKey) {
        return this.keyPrefix.isEmpty() ? objectKey : this.keyPrefix + "/" + objectKey;
    }

    /** Unwraps a {@link CompletionException}'s real cause, or returns {@code exception} itself if it isn't one. */
    private static Throwable unwrap(final Throwable exception) {
        return exception instanceof CompletionException && exception.getCause() != null ? exception.getCause() : exception;
    }
}
