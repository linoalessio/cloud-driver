package de.lino.cloud.plugin.s3storage;

import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import software.amazon.awssdk.regions.Region;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Standalone, runnable worked example (not an {@code mvn test} target - see "Build" in {@code
 * CLAUDE.md} for this repo's "sample under {@code src/test}, run {@code main} directly"
 * convention) round-tripping a small byte array through a real {@link S3ObjectStorageService} -
 * {@code putObject}/{@code exists}/{@code getObject}/{@code deleteObject}, asserting the
 * downloaded content matches what was uploaded, printing pass/fail to stdout.
 *
 * <p>Needs a real S3 bucket (or a <a href="https://github.com/localstack/localstack">LocalStack</a>
 * S3 endpoint pointed at via the AWS SDK's own {@code AWS_ENDPOINT_URL_S3} environment variable)
 * this process's own AWS credentials (the default credential provider chain - see {@link
 * S3ObjectStorageService}'s own Javadoc) already have {@code s3:PutObject}/{@code s3:GetObject}/
 * {@code s3:DeleteObject}/{@code s3:ListBucket} on - configured via two environment variables,
 * neither of which this sample reads from {@code configuration.json} (a throwaway sample has no
 * reason to touch the real deployment's config file):
 *
 * <ul>
 *     <li>{@code CLOUD_DRIVER_S3_TEST_BUCKET} (required) - the bucket to round-trip a test object through</li>
 *     <li>{@code CLOUD_DRIVER_S3_TEST_REGION} (optional, defaults to {@code eu-central-1}) - the bucket's region</li>
 * </ul>
 *
 * <p>Every object this sample writes is placed under the {@code cloud-driver-sample/} key prefix
 * and deleted again before exiting - safe to run repeatedly against a shared bucket without
 * leaving anything behind (barring a failed run - {@link #main} still attempts the delete even on
 * a mismatch, just reports the earlier failure).
 */
public final class S3ObjectStorageServiceSample {

    /** Every object this sample writes lives under this key prefix, so it's trivially distinguishable from real {@code StoredFile} content in the same bucket. */
    private static final String KEY_PREFIX = "cloud-driver-sample";

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private S3ObjectStorageServiceSample() {
    }

    /**
     * Round-trips a small byte array through a real {@link S3ObjectStorageService} and reports
     * pass/fail to stdout - see this class's own Javadoc for the required environment variables.
     *
     * @param args unused
     */
    public static void main(final String[] args) {

        final String bucket = System.getenv("CLOUD_DRIVER_S3_TEST_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.err.println(
                    "S3ObjectStorageServiceSample: set CLOUD_DRIVER_S3_TEST_BUCKET (and optionally "
                            + "CLOUD_DRIVER_S3_TEST_REGION) to a real bucket your AWS credentials already have "
                            + "s3:PutObject/s3:GetObject/s3:DeleteObject/s3:ListBucket on before running this sample."
            );
            System.exit(1);
            return;
        }
        final Region region = Region.of(System.getenv().getOrDefault("CLOUD_DRIVER_S3_TEST_REGION", "eu-central-1"));

        final ObjectStorageService objectStorageService = new S3ObjectStorageService(region, bucket, KEY_PREFIX);
        final String objectKey = "roundtrip-" + UUID.randomUUID();
        final byte[] originalContent = "Hello from S3ObjectStorageServiceSample!".getBytes(StandardCharsets.UTF_8);

        boolean passed = true;

        try {
            objectStorageService.putObject(objectKey, originalContent);
            System.out.println("putObject: OK");

            passed &= reportCheck("exists (after putObject)", objectStorageService.exists(objectKey));

            final byte[] downloadedContent = objectStorageService.getObject(objectKey);
            passed &= reportCheck("getObject round-trip matches", Arrays.equals(originalContent, downloadedContent));

            objectStorageService.deleteObject(objectKey);
            passed &= reportCheck("exists (after deleteObject)", !objectStorageService.exists(objectKey));

        } catch (final ObjectStorageException e) {
            e.printStackTrace();
            passed = false;
        }

        System.out.println(passed ? "S3ObjectStorageServiceSample: PASSED" : "S3ObjectStorageServiceSample: FAILED");
        if (!passed) {
            System.exit(1);
        }
    }

    /**
     * Prints {@code label}'s pass/fail outcome and returns it unchanged, for {@code &=}-chaining
     * across {@link #main}'s three checks without short-circuiting past a later one (unlike
     * {@code &&}, which would skip {@code getObject}/{@code deleteObject} entirely the moment
     * {@code exists} first failed).
     */
    private static boolean reportCheck(final String label, final boolean condition) {
        System.out.println(label + ": " + (condition ? "OK" : "FAIL"));
        return condition;
    }
}
