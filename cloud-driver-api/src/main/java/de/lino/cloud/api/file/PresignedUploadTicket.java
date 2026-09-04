package de.lino.cloud.api.file;

import de.lino.cloud.api.storage.object.PresignedUpload;
import de.lino.cloud.api.utility.Asserts;

/**
 * Pairs a freshly generated {@link StoredFile#fileId()} with the {@link PresignedUpload} the
 * client should upload its content to - returned by {@code ICloudUserService#beginPresignedUpload}.
 * The caller reports {@code fileId} back verbatim to {@code ICloudUserService#completePresignedUpload}
 * once the upload has actually finished.
 *
 * @param fileId the id the eventual {@link StoredFile} will be created under
 * @param upload where and how to upload the file's content
 */
public record PresignedUploadTicket(String fileId, PresignedUpload upload) {

    /**
     * @throws NullPointerException if {@code fileId} or {@code upload} is {@code null}
     */
    public PresignedUploadTicket {
        Asserts.requireNonNull(fileId, "@PresignedUploadTicket: fileId cannot be null");
        Asserts.requireNonNull(upload, "@PresignedUploadTicket: upload cannot be null");
    }
}
