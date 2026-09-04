package de.lino.cloud.api.s3storage;

import de.lino.cloud.api.utility.Asserts;

import java.net.URL;
import java.time.Instant;

/**
 * A short-lived, signed URL a client can {@code GET} a file's raw bytes directly from (no
 * {@link de.lino.cloud.api.CloudDriver} credentials involved), produced by {@link
 * PresignedTransferService#presignDownload}.
 *
 * @param url the presigned URL to {@code GET} the object's bytes from
 * @param expiresAt when {@link #url} stops being valid
 */
public record PresignedDownload(URL url, Instant expiresAt) {

    /**
     * @throws NullPointerException if any component is {@code null}
     */
    public PresignedDownload {
        Asserts.requireNonNull(url, "@PresignedDownload: url cannot be null");
        Asserts.requireNonNull(expiresAt, "@PresignedDownload: expiresAt cannot be null");
    }
}
