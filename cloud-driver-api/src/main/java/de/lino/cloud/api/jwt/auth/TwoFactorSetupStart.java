package de.lino.cloud.api.jwt.auth;

/**
 * The result of {@link IAuthService#beginTwoFactorSetup}: a freshly generated TOTP secret, not yet
 * live on the account (see {@code de.lino.cloud.auth.pending.PendingTwoFactorSetup}'s own Javadoc
 * for why), plus a ready-to-render {@code otpauth://} URI a client can turn into a QR code for an
 * authenticator app (Google Authenticator, Authy, etc.) to scan.
 *
 * @param secretBase32 the freshly generated TOTP shared secret, base32-encoded - also shown as
 *     plain text so a caller without a camera-based scanner can still enter it manually
 * @param otpauthUri an {@code otpauth://totp/...} URI embedding {@link #secretBase32} plus an
 *     issuer/account label, suitable for rendering as a QR code
 */
public record TwoFactorSetupStart(String secretBase32, String otpauthUri) {
}
