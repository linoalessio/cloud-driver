package de.lino.cloud.extensions.scan;

/** Thrown when {@code clamd} accepted a connection but reported a scan-level error (not a clean/flagged verdict) - e.g. its own {@code StreamMaxLength} exceeded, or an unrecognized response. */
final class ClamAvScanException extends Exception {

    ClamAvScanException(final String message) {
        super(message);
    }

}
