package de.lino.cloud.extensions.intelligence;

import java.io.IOException;

/**
 * Thrown by {@link IntelligenceHttpClient} when the Python service accepted the request but
 * answered with something other than a usable result - a non-2xx status, or a body that doesn't
 * parse into the expected shape.
 *
 * <p>Deliberately distinct from a plain {@link IOException} (connection refused, timeout,
 * reset), mirroring the same split {@code ClamAvScanException} draws against {@code clamd}
 * transport failures: both are retried identically by {@link DefaultIntelligenceService}, but
 * keeping them apart makes a log line say whether the service was unreachable or merely unhappy.
 */
final class IntelligenceServiceException extends Exception {

    IntelligenceServiceException(final String message) {
        super(message);
    }

}
