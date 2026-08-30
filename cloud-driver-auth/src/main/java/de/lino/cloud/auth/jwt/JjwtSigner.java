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
 * {@link JwtSigner} backed by HMAC-SHA256 (jjwt). The signing key is read by the caller (e.g.
 * {@code cloud-driver-extensions-rest}'s {@code CloudRestExtension}) from the {@code
 * "jwt-signing-key"} field of {@code configuration.json} under {@code
 * Constraints.CONFIGURATION_PATH} - never hardcoded, same requirement as the Postgres
 * credentials. Requires at least 32 bytes/256 bits of entropy; generate e.g. via {@code
 * openssl rand -base64 32}.
 */
public final class JjwtSigner implements JwtSigner {

    /** The HMAC-SHA256 key derived from the signing key material passed to the constructor. */
    private final SecretKey key;

    /**
     * Creates a {@code JjwtSigner} keyed by {@code signingKeySecret}.
     *
     * @param signingKeySecret the raw HMAC-SHA256 signing key material, UTF-8 encoded; must be
     *     at least 32 bytes (256 bits) of entropy, e.g. generated via {@code openssl rand -base64 32}
     * @throws IllegalArgumentException if {@code signingKeySecret} is shorter than 32 bytes
     */
    public JjwtSigner(@NonNull final String signingKeySecret) {
        if (signingKeySecret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("@JjwtSigner.init: signing key must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(signingKeySecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Issues a JWT asserting {@code subject}, signed with this instance's HMAC-SHA256 key and
     * carrying {@code issuedAt}/{@code expiration} claims computed from the current instant.
     *
     * @param subject the identity to embed (e.g. {@link de.lino.cloud.api.jwt.user.AuthUser#getId()})
     * @param ttlSeconds how many seconds from now the token expires
     * @return the signed, compact JWT string
     */
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

    /**
     * Verifies {@code token}'s signature and expiry against this instance's key, returning its subject.
     *
     * @param token the compact JWT string to verify
     * @return the subject embedded in {@code token}
     * @throws InvalidJwtException if jjwt reports a bad signature, malformed token, or expiry
     *     (wraps the underlying {@link JwtException})
     */
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
