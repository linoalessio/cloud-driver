package de.lino.cloud.api.jwt.auth;

import org.jetbrains.annotations.Nullable;

/**
 * The outcome of {@link IAuthService#login}: either a real, freshly issued {@link AuthTokens} pair
 * (the matched account has two-factor authentication disabled), or a signal that a second factor is
 * still required (a {@code pendingTwoFactorToken} the caller must present, together with a TOTP
 * code, to {@link IAuthService#completeTwoFactorLogin}).
 *
 * <p>Exactly one of {@link #tokens()}/{@link #pendingTwoFactorToken()} is non-{@code null} on any
 * given instance - constructed exclusively via {@link #success(AuthTokens)}/{@link
 * #requiresTwoFactor(String)} rather than this record's canonical constructor directly, so that
 * invariant can never be violated from outside this class. {@link #requiresTwoFactor()} is the
 * convenience a caller (e.g. {@code DefaultRestFactory#handleLogin}) should branch on, rather than
 * null-checking either field directly.
 *
 * @param tokens the freshly issued access/refresh pair, or {@code null} if a second factor is still required
 * @param pendingTwoFactorToken the opaque, short-lived token to present to {@link
 *     IAuthService#completeTwoFactorLogin} alongside a TOTP code, or {@code null} if login already
 *     completed without one
 */
public record LoginResult(@Nullable AuthTokens tokens, @Nullable String pendingTwoFactorToken) {

    /**
     * Builds a completed-login result - the password (and, since two-factor authentication is
     * disabled for this account, nothing further) was enough to authenticate.
     *
     * @param tokens the freshly issued access/refresh pair
     * @return a {@link LoginResult} carrying {@code tokens} and no pending second-factor token
     */
    public static LoginResult success(final AuthTokens tokens) {
        return new LoginResult(tokens, null);
    }

    /**
     * Builds a needs-second-factor result - the password matched, but the account has two-factor
     * authentication enabled, so a real {@link AuthTokens} pair is not issued yet.
     *
     * @param pendingTwoFactorToken the opaque, short-lived token {@link
     *     IAuthService#completeTwoFactorLogin} expects back alongside a valid TOTP code
     * @return a {@link LoginResult} carrying no tokens and the given pending second-factor token
     */
    public static LoginResult requiresTwoFactor(final String pendingTwoFactorToken) {
        return new LoginResult(null, pendingTwoFactorToken);
    }

    /** @return {@code true} if this result still requires a TOTP code via {@link IAuthService#completeTwoFactorLogin} before real tokens are issued */
    public boolean requiresTwoFactor() {
        return this.pendingTwoFactorToken != null;
    }

}
