package de.lino.cloud.api.s3storage;

/**
 * Signals that an {@link ObjectStorageService} operation failed - the underlying store rejected
 * or failed a write, a read, a delete, or an existence check. Unchecked (unlike {@code
 * DatabaseClientException}, which this otherwise mirrors field-for-field): a caller on the file
 * upload/download path already has to handle the checked {@code DatabaseClientException}/{@code
 * KeyWrapException} pair {@code DataFactory}/{@code EnvelopeEncryptionService} throw, and this
 * exception is meant to propagate the same way an object-store failure would surface from any
 * other unchecked infrastructure failure in this codebase (e.g. {@code UploadQuotaExceededException}).
 */
public final class ObjectStorageException extends RuntimeException {

    /**
     * @param message the detail message describing the object-storage failure
     */
    public ObjectStorageException(final String message) {
        super(message);
    }

    /**
     * @param message the detail message describing the object-storage failure
     * @param cause the underlying cause, if any
     */
    public ObjectStorageException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
