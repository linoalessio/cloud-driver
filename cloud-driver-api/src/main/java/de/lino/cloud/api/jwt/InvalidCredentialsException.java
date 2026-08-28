package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link de.lino.cloud.api.jwt.auth.IAuthService#login} when a username doesn't exist or its
 * password doesn't match - deliberately the same exception (and the same
 * message, at the call site) for both cases, so a caller can never use this
 * to enumerate valid usernames.
 */
public final class InvalidCredentialsException extends RuntimeException {

    /**
     * Constructs the exception with a detail message.
     *
     * @param message the detail message, deliberately identical whether the username doesn't exist or the password is wrong
     */
    public InvalidCredentialsException(final String message) {
        super(message);
    }
}
