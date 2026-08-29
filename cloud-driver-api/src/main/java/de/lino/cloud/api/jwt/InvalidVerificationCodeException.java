package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link de.lino.cloud.api.jwt.auth.IAuthService#confirmRegistration} when the given
 * e-mail address has no pending registration, its pending registration has expired, or the
 * supplied code doesn't match - deliberately the same exception (and the same message) for all
 * three cases, so a caller can never use this to probe whether a given address was ever
 * registered, the same "don't leak" idiom {@link InvalidCredentialsException} already uses for
 * login.
 */
public final class InvalidVerificationCodeException extends RuntimeException {

    /**
     * @param message the detail message, deliberately identical regardless of which of the three
     *     underlying causes applies
     */
    public InvalidVerificationCodeException(final String message) {
        super(message);
    }

}
