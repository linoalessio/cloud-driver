package de.lino.cloud.api.file;

/**
 * Signals that a downloaded {@link StoredFile}'s decrypted plaintext content
 * does not match its recorded {@link FileChecksum} - the content has changed
 * since it was uploaded, even though the AES-256-GCM authentication tag over
 * the stored ciphertext itself checked out (see {@link FileChecksum#matches}).
 * Checked, so code that raises it on a failed checksum check cannot have that
 * failure silently swallowed, the same way {@link
 * de.lino.cloud.api.security.crypto.AuthenticationFailedException} cannot be
 * silently ignored on the ciphertext level.
 */
public final class FileIntegrityException extends Exception {

    public FileIntegrityException(final String message) {
        super(message);
    }

    public FileIntegrityException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
