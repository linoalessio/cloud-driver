package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link JwtSigner#verify(String)} when a token's signature is
 * invalid, the token is malformed, or it has expired.
 */
public class InvalidJwtException extends RuntimeException {

    /**
     * Constructs the exception with a detail message.
     *
     * @param message the detail message describing why the token was rejected
     */
    public InvalidJwtException(final String message) {
        super(message);
    }

    /**
     * Constructs the exception with a detail message and an underlying cause.
     *
     * @param message the detail message describing why the token was rejected
     * @param cause the underlying cause, if any
     */
    public InvalidJwtException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
