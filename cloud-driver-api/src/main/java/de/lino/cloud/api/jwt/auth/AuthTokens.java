package de.lino.cloud.api.jwt.auth;

/**
 * The pair of tokens issued by {@link IAuthService#login}/{@link IAuthService#confirmRegistration}/
 * {@link IAuthService#confirmPasswordReset}/{@link IAuthService#refresh}.
 *
 * <p>{@code accessToken} is the short-lived (12h) signed JWT every bearer-gated route validates via
 * {@link IAuthService#validate} - completely unchanged by the introduction of this record. {@code
 * refreshToken} is a longer-lived, opaque, single-use token (see {@code RefreshToken} in {@code
 * cloud-driver-auth}) that {@link IAuthService#refresh} exchanges for a fresh {@code AuthTokens} pair
 * once the access token expires, without requiring the caller to log in again with a password.
 *
 * <p>Rotated on every {@link IAuthService#refresh} call: the {@code refreshToken} returned there
 * always differs from the one passed in, and the one passed in is invalidated as part of that same
 * call - so a client must always persist the freshly returned {@code refreshToken}, never reuse the
 * one it just presented.
 *
 * @param accessToken the short-lived signed JWT
 * @param refreshToken the longer-lived, single-use, rotate-on-every-use refresh token
 */
public record AuthTokens(String accessToken, String refreshToken) {
}
