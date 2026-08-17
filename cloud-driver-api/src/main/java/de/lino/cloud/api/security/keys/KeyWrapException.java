package de.lino.cloud.api.security.keys;

/**
 * Signals that a {@link KeyEncryptionService} could not wrap or unwrap a
 * {@link DataEncryptionKey} - e.g. the key-encryption key is unknown, the KMS/
 * HSM rejected the request, or ciphertext integrity failed. Checked, so
 * callers on the data path cannot silently ignore a key-management failure.
 */
public final class KeyWrapException extends Exception {

    public KeyWrapException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
