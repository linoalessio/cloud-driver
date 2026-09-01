package de.lino.cloud.platform.rest.api.session;

/** Thrown when reading/writing/clearing the persisted session token fails. */
public final class TokenStoreException extends Exception {

    /** @param message a human-readable description of the failure */
    public TokenStoreException(final String message) {
        super(message);
    }

    /**
     * @param message a human-readable description of the failure
     * @param cause   the underlying exception (e.g. an {@link java.io.IOException} or a failed helper-process exit code)
     */
    public TokenStoreException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
