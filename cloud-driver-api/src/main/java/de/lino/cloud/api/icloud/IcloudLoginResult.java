package de.lino.cloud.api.icloud;

/**
 * The outcome of an {@link IcloudBridge#login} attempt.
 *
 * @param requiresTwoFactor {@code true} if Apple demands a two-factor code (via {@link
 *                          IcloudBridge#confirmTwoFactorCode}) before the login is actually
 *                          complete; {@code false} if the session is already fully authenticated
 */
public record IcloudLoginResult(boolean requiresTwoFactor) {
}
