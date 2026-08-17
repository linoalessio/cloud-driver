package de.lino.cloud.api.database;

/**
 * Signals that a persistence operation against the configured database
 * failed - the entity's id could not be found, an insert collided with an
 * existing id, or the stored record was malformed. Checked, so callers on
 * the data path cannot silently ignore a failed persistence operation.
 */
public final class DatabaseClientException extends Exception {

    public DatabaseClientException(final String message) {
        super(message);
    }

    public DatabaseClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
