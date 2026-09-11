package de.lino.cloud.api.file;

import de.lino.cloud.api.s3storage.PresignedUpload;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.Nullable;

/**
 * Pairs a freshly generated {@link StoredFile#fileId()} with the {@link PresignedUpload} the
 * client should upload its content to - returned by {@code ICloudUserService#beginPresignedUpload}.
 * The caller reports {@code fileId} back verbatim to {@code ICloudUserService#completePresignedUpload}
 * once the upload has actually finished.
 *
 * @param fileId the id the eventual {@link StoredFile} will be created under
 * @param upload where and how to upload the file's content
 * @param encryption how the client must encrypt the content before uploading it, or {@code null}
 *     on a deployment without a {@code ContentKeyService} - only then is the content stored as
 *     the client sent it, protected by the object store's server-side encryption alone
 */
public record PresignedUploadTicket(String fileId, PresignedUpload upload, @Nullable PresignedUploadEncryption encryption) {

    /**
     * @throws NullPointerException if {@code fileId} or {@code upload} is {@code null}
     */
    public PresignedUploadTicket {
        Asserts.requireNonNull(fileId, "@PresignedUploadTicket: fileId cannot be null");
        Asserts.requireNonNull(upload, "@PresignedUploadTicket: upload cannot be null");
    }

    /**
     * Convenience constructor for the unencrypted (no {@code ContentKeyService}) case - {@link
     * #encryption} stays {@code null}.
     *
     * @param fileId the id the eventual {@link StoredFile} will be created under
     * @param upload where and how to upload the file's content
     * @throws NullPointerException if {@code fileId} or {@code upload} is {@code null}
     */
    public PresignedUploadTicket(final String fileId, final PresignedUpload upload) {
        this(fileId, upload, null);
    }
}
