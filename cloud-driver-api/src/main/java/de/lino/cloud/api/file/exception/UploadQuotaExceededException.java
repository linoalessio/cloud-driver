package de.lino.cloud.api.file.exception;

import de.lino.cloud.api.user.ICloudUser;

/**
 * Thrown by {@code CloudUserService#uploadFile} when accepting an upload would push its owner
 * past {@link ICloudUser#getMaxBytesToUpload()} (checked via {@link
 * ICloudUser#isUploadLimitReached(long)}) - translated to a {@code 413} response by {@code
 * DefaultRestFactory}, the same status family Javalin's own {@code maxRequestSize} cap already
 * uses for "this upload is too large", just scoped per-account instead of per-request.
 */
public final class UploadQuotaExceededException extends RuntimeException {

    /**
     * @param authUserId the account whose quota would be exceeded
     * @param currentUploadedBytes how many bytes {@code authUserId} already has stored
     * @param bytesToUpload the size of the upload that was rejected
     * @param maxBytesToUpload {@code authUserId}'s configured upload quota
     */
    public UploadQuotaExceededException(final String authUserId, final long currentUploadedBytes,
                                         final long bytesToUpload, final long maxBytesToUpload) {
        super("Upload quota exceeded for " + authUserId + ": " + currentUploadedBytes + " bytes already stored + "
                + bytesToUpload + " bytes uploaded would exceed the " + maxBytesToUpload + " byte limit");
    }
}
