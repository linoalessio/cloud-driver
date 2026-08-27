package de.lino.cloud.auth.jwt;

import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.JwtSigner;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * {@link JwtSigner} backed by HMAC-SHA256 (jjwt). The signing key comes from
 * {@code JWT_SIGNING_KEY} (read by the caller, e.g. {@code CloudBootstrap})
 * - never hardcoded, same requirement as the Postgres credentials. Requires
 * at least 32 bytes/256 bits of entropy; generate e.g. via {@code openssl
 * rand -base64 32}.
 */
public final class JjwtSigner implements JwtSigner {

    private final SecretKey key;

    public JjwtSigner(@NonNull final String signingKeySecret) {
        if (signingKeySecret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("@JjwtSigner.init: signing key must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(signingKeySecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @NotNull
    public String sign(@NotNull final String subject, final long ttlSeconds) {
        final Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(this.key)
                .compact();
    }

    @Override
    @NotNull
    public String verify(@NotNull final String token) {
        try {
            return Jwts.parser()
                    .verifyWith(this.key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (final JwtException e) {
            throw new InvalidJwtException("@JjwtSigner.verify: invalid or expired token", e);
        }
    }

}
