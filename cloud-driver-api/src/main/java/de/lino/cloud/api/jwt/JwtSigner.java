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
     * @param subject the identity to embed (e.g. {@code User#getId()})
     * @param ttlSeconds how many seconds from now the token expires
     */
    @NotNull
    String sign(@NotNull String subject, long ttlSeconds);

    /**
     * Verifies {@code token}'s signature and expiry, returning its subject.
     *
     * @throws InvalidJwtException if the signature is invalid, malformed, or the token has expired
     */
    @NotNull
    String verify(@NotNull String token) throws InvalidJwtException;
}
