package de.lino.cloud.api.jwt;

import org.jetbrains.annotations.NotNull;

/**
 * Signs and verifies stateless JWTs used to authenticate end-user clients
 * (iOS/rest/macOS) after a successful login. Unlike {@code
 * de.lino.cloud.api.security.rest.ApiKey} (static, long-lived,
 * server-to-server), a JWT is short-lived and carries a user identity.
 */
public interface JwtSigner {

    /**
     * Issues a signed JWT asserting {@code subject} at session generation {@code tokenVersion},
     * expiring after {@code ttlSeconds}.
     *
     * <p>The version is part of the signed payload, never a header, so it cannot be stripped or
     * rewritten without breaking the signature.
     *
     * @param subject the identity to embed (e.g. {@code AuthUser#getId()})
     * @param tokenVersion the account's session generation at signing time
     * @param ttlSeconds how many seconds from now the token expires
     * @return the signed, encoded JWT
     */
    @NotNull
    String sign(@NotNull String subject, int tokenVersion, long ttlSeconds);

    /**
     * Verifies {@code token}'s signature and expiry, returning what it asserts.
     *
     * <p>A token carrying no version claim - one signed before the claim existed - reports {@code
     * 0}, the generation every account starts at.
     *
     * @param token the encoded JWT to verify
     * @return the subject and session generation embedded in {@code token} at signing time
     * @throws InvalidJwtException if the signature is invalid, the token is malformed, or it has expired
     */
    @NotNull
    VerifiedAccessToken verify(@NotNull String token) throws InvalidJwtException;
}
