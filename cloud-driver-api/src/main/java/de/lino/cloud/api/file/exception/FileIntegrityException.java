package de.lino.cloud.api.file.exception;

import de.lino.cloud.api.file.meta.FileChecksum;
import de.lino.cloud.api.file.StoredFile;

/**
 * Signals that a downloaded {@link StoredFile}'s decrypted plaintext
 * content does not match its recorded {@link FileChecksum}, even though the
 * ciphertext's authentication tag checked out.
 */
public final class FileIntegrityException extends Exception {

    /**
     * @param message the detail message describing the checksum mismatch
     */
    public FileIntegrityException(final String message) {
        super(message);
    }

    /**
     * @param message the detail message describing the checksum mismatch
     * @param cause the underlying cause, if any
     */
    public FileIntegrityException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
