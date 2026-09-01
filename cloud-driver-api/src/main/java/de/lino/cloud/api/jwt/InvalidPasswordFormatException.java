package de.lino.cloud.api.jwt;

/**
 * Thrown by {@link de.lino.cloud.api.jwt.auth.IAuthService#register} and
 * {@link de.lino.cloud.api.jwt.auth.IAuthService#confirmPasswordReset} when a caller-chosen
 * password doesn't meet the format requirement: at least 8 characters, containing at least one
 * digit, one lowercase letter, one uppercase letter, and one symbol, and containing none of the
 * forbidden characters ({@code ;}, {@code ,}, {@code :}, {@code `}).
 */
public final class InvalidPasswordFormatException extends RuntimeException {

    /**
     * Constructs the exception with a detail message.
     *
     * @param message the detail message describing which requirement was not met - never includes the password itself
     */
    public InvalidPasswordFormatException(final String message) {
        super(message);
    }
}
