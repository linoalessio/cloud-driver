package de.lino.cloud.api.security.database;

/**
 * Signals that a persistence operation against the configured database
 * failed - the meta's id could not be found, an insert collided with an
 * existing id, or the stored record was malformed. Checked, so callers on
 * the data path cannot silently ignore a failed persistence operation.
 */
public final class DatabaseClientException extends Exception {

    /**
     * @param message the detail message describing the persistence failure
     */
    public DatabaseClientException(final String message) {
        super(message);
    }

    /**
     * @param message the detail message describing the persistence failure
     * @param cause the underlying cause, if any
     */
    public DatabaseClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
