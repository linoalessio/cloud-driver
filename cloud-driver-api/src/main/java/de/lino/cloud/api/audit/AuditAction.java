package de.lino.cloud.api.audit;

/**
 * A security-relevant action worth recording in an {@link AuditEvent} - see that class's Javadoc
 * and {@code architecture/SERVICES.md} item 11 for the full audit-log design. Deliberately only
 * lists actions this codebase actually calls {@link AuditLogService#record} for today (see {@code
 * de.lino.cloud.auth.AuthService}/{@code de.lino.cloud.auth.CloudUserService}'s call sites) rather
 * than every action the original brainstorm named - extend this enum alongside a real new call
 * site, not speculatively ahead of one.
 */
public enum AuditAction {

    /** A successful {@code AuthService#login} call. */
    LOGIN_SUCCESS,

    /** A failed {@code AuthService#login} call - wrong password or no such account (never distinguished, matching {@code login}'s own "don't leak" contract). */
    LOGIN_FAILURE,

    /** A completed {@code AuthService#register}/{@code #confirmRegistration} flow (the account was actually created). */
    REGISTER,

    /** A completed {@code AuthService#confirmPasswordReset} call. */
    PASSWORD_RESET,

    /** A completed {@code AuthService#confirmEmailChange} call. */
    EMAIL_CHANGE,

    /** A {@code CloudUserService#deleteFile} call (moves a file to the trash - see the recycle-bin feature). */
    FILE_DELETE,

    /** A {@code CloudUserService#deleteCloudUser} call (permanently empties and removes an account). */
    ACCOUNT_DELETE,

    /** A completed {@code AuthService#confirmTwoFactorSetup} call (two-factor authentication is now enabled on this account). */
    TWO_FACTOR_ENABLED,

    /** A completed {@code AuthService#disableTwoFactor} call (two-factor authentication is now disabled on this account). */
    TWO_FACTOR_DISABLED

}
