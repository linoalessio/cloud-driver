package de.lino.cloud.api.file;

import de.lino.cloud.api.s3storage.PresignedDownload;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.Nullable;

/**
 * Pairs a {@link PresignedDownload} with the client-side decryption material the file needs, if
 * any - returned by {@code ICloudUserService#beginPresignedDownload}. {@link #encryption} is
 * non-{@code null} for a file uploaded client-encrypted (see {@link PresignedUploadEncryption}):
 * the client fetches the ciphertext directly from {@link PresignedDownload#url()} and decrypts it
 * locally. {@code null} for a legacy direct-transfer file stored plaintext-in-S3 before
 * client-side encryption existed - such a file's bytes come back usable as-is.
 *
 * @param download where to fetch the object's bytes directly from
 * @param encryption how to decrypt them locally, or {@code null} if they are plaintext
 */
public record PresignedDownloadTicket(PresignedDownload download, @Nullable PresignedDownloadEncryption encryption) {

    /**
     * @throws NullPointerException if {@code download} is {@code null}
     */
    public PresignedDownloadTicket {
        Asserts.requireNonNull(download, "@PresignedDownloadTicket: download cannot be null");
    }
}
