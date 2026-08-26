package de.lino.cloud.api.security.crypto;

/**
 * Signals that an {@link AeadEncryptionService} could not verify the
 * authentication tag of a payload during decryption; the payload must be
 * treated as rejected/untrusted.
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
