package de.lino.cloud.api.security.crypto;

/**
 * Signals that an {@link AeadEncryptionService} could not verify the
 * authentication tag of a payload during decryption. Per section 6
 * (AUTHENTICATED ENCRYPTION AND INTEGRITY), authentication failures SHALL
 * cause the message to be rejected - this is a checked exception so callers
 * cannot silently ignore a failed verification.
 */
public final class AuthenticationFailedException extends Exception {

    /**
     * @param message the detail message describing the verification failure
     * @param cause the underlying cause, if any
     */
    public AuthenticationFailedException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
