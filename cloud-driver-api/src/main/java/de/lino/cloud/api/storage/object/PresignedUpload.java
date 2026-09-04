package de.lino.cloud.api.storage.object;

import de.lino.cloud.api.utility.Asserts;

import java.net.URL;
import java.time.Instant;
import java.util.Map;

/**
 * A short-lived, signed URL a client can {@code PUT} a file's raw bytes directly to (no
 * {@link de.lino.cloud.api.CloudDriver} credentials involved), produced by {@link
 * PresignedTransferService#presignUpload}.
 *
 * @param url the presigned URL to {@code PUT} the object's bytes to
 * @param requiredHeaders headers the signature was computed over (e.g. {@code
 *     x-amz-server-side-encryption}) - the client's {@code PUT} must replay every one of these
 *     exactly, or the underlying store rejects the request with a signature mismatch
 * @param expiresAt when {@link #url} stops being valid
 */
public record PresignedUpload(URL url, Map<String, String> requiredHeaders, Instant expiresAt) {

    /**
     * @throws NullPointerException if any component is {@code null}
     */
    public PresignedUpload {
        Asserts.requireNonNull(url, "@PresignedUpload: url cannot be null");
        Asserts.requireNonNull(requiredHeaders, "@PresignedUpload: requiredHeaders cannot be null");
        Asserts.requireNonNull(expiresAt, "@PresignedUpload: expiresAt cannot be null");
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}
