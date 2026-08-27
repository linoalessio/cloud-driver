package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link JwtSigner#verify(String)} when a token's signature is
 * invalid, the token is malformed, or it has expired.
 */
public class InvalidJwtException extends RuntimeException {

    public InvalidJwtException(final String message) {
        super(message);
    }

    public InvalidJwtException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
