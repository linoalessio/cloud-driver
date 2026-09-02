package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link de.lino.cloud.api.jwt.auth.IAuthService#refresh} when the supplied refresh token
 * doesn't exist, has expired, has been revoked, or points at an account that no longer exists -
 * deliberately the same exception (and the same message) for all four cases, so a caller can never
 * use this to distinguish "stolen/guessed token" from "token I once had, now stale" - the same
 * "don't leak which" idiom {@link InvalidVerificationCodeException} already uses for a bad
 * registration/reset code.
 */
public final class InvalidRefreshTokenException extends RuntimeException {

    /**
     * @param message the detail message, deliberately identical regardless of which of the four
     *     underlying causes applies
     */
    public InvalidRefreshTokenException(final String message) {
        super(message);
    }

}
