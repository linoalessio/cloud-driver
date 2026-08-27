package de.lino.cloud.extensions.web;

/**
 * Thrown by {@link AuthPanelServer#handleRegister} when {@code emailAddress} already belongs to
 * an existing {@link de.lino.cloud.api.jwt.user.AuthUser} - {@link de.lino.cloud.auth.AuthService#register}
 * itself has no such check (its primary key is a random UUID, not the email address), so this
 * class enforces uniqueness itself before ever calling it.
 */
public final class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(final String message) {
        super(message);
    }
}
