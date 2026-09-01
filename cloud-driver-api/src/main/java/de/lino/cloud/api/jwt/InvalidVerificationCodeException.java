package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link de.lino.cloud.api.jwt.auth.IAuthService#confirmRegistration}, {@link
 * de.lino.cloud.api.jwt.auth.IAuthService#confirmPasswordReset}, and {@link
 * de.lino.cloud.api.jwt.auth.IAuthService#confirmEmailChange} when the relevant pending
 * registration/reset/change doesn't exist, has expired, or the supplied code doesn't match -
 * deliberately the same exception (and the same message) for all three cases in each of those
 * three flows, so a caller can never use this to probe whether a given address/account has a
 * pending operation outstanding, the same "don't leak" idiom {@link InvalidCredentialsException}
 * already uses for login.
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
