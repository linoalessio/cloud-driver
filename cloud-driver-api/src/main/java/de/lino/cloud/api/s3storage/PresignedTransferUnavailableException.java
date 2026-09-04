package de.lino.cloud.api.s3storage;

/**
 * Thrown by {@code CloudUserService#beginPresignedUpload}/{@code #completePresignedUpload}/{@code
 * #beginPresignedDownload} when no {@link PresignedTransferService} is configured on this
 * deployment - unchecked, mirroring {@code UploadQuotaExceededException}'s shape. Translated by
 * {@code DefaultRestFactory} into a {@code 503 ServiceUnavailableResponse}, so a client can
 * transparently fall back to the server-mediated {@code POST /files}/{@code GET
 * /files/{id}/content} routes instead of failing outright.
 */
public final class PresignedTransferUnavailableException extends RuntimeException {

    public PresignedTransferUnavailableException() {
        super("Presigned direct-to-client transfer is not configured on this deployment");
    }
}
