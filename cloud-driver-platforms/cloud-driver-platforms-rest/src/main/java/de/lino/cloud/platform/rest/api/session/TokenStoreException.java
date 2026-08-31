package de.lino.cloud.platform.rest.api.session;

/** Thrown when reading/writing/clearing the persisted session token fails. */
public final class TokenStoreException extends Exception {

    public TokenStoreException(final String message) {
        super(message);
    }

    public TokenStoreException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
