package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link de.lino.cloud.api.jwt.auth.IAuthService#register} when an {@code AuthUser}
 * already exists under the given email address. Unlike {@link InvalidCredentialsException},
 * this is deliberately allowed to confirm an account exists - registration is where "is this
 * email already taken" is expected, normal, user-facing feedback (the same way any signup form
 * behaves), not an enumeration risk the way login's own error handling has to guard against.
 */
public final class EmailAlreadyRegisteredException extends RuntimeException {

    /**
     * @param emailAddress the email address an account already exists under
     */
    public EmailAlreadyRegisteredException(final String emailAddress) {
        super("An account already exists for " + emailAddress);
    }
}
