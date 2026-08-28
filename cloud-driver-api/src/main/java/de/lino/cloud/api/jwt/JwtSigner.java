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
     * Issues a signed JWT asserting {@code subject}, expiring after {@code ttlSeconds}.
     *
     * @param subject the identity to embed (e.g. {@code AuthUser#getId()})
     * @param ttlSeconds how many seconds from now the token expires
     * @return the signed, encoded JWT
     */
    @NotNull
    String sign(@NotNull String subject, long ttlSeconds);

    /**
     * Verifies {@code token}'s signature and expiry, returning its subject.
     *
     * @param token the encoded JWT to verify
     * @return the subject embedded in {@code token} at signing time
     * @throws InvalidJwtException if the signature is invalid, the token is malformed, or it has expired
     */
    @NotNull
    String verify(@NotNull String token) throws InvalidJwtException;
}
