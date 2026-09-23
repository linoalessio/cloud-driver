package de.lino.cloud.auth.jwt;

import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.jwt.VerifiedAccessToken;
import io.jsonwebtoken.Claims;
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

    /**
     * Name of the claim carrying the account's session generation. Lives in the signed payload,
     * never a header, so it cannot be stripped or rewritten without breaking the signature.
     */
    public static final String TOKEN_VERSION_CLAIM = "tv";

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
     * Issues a JWT asserting {@code subject} at session generation {@code tokenVersion}, signed
     * with this instance's HMAC-SHA256 key and carrying {@code issuedAt}/{@code expiration}
     * claims computed from the current instant.
     *
     * @param subject the identity to embed (e.g. {@link de.lino.cloud.api.jwt.user.AuthUser#getId()})
     * @param tokenVersion the account's session generation at signing time, carried as {@link
     *     #TOKEN_VERSION_CLAIM}
     * @param ttlSeconds how many seconds from now the token expires
     * @return the signed, compact JWT string
     */
    @Override
    @NotNull
    public String sign(@NotNull final String subject, final int tokenVersion, final long ttlSeconds) {
        final Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(this.key)
                .compact();
    }

    /**
     * Verifies {@code token}'s signature and expiry against this instance's key, returning what it
     * asserts.
     *
     * @param token the compact JWT string to verify
     * @return the subject and session generation embedded in {@code token}
     * @throws InvalidJwtException if jjwt reports a bad signature, malformed token, or expiry
     *     (wraps the underlying {@link JwtException}), or the token carries no subject
     */
    @Override
    @NotNull
    public VerifiedAccessToken verify(@NotNull final String token) {
        try {
            final Claims claims = Jwts.parser()
                    .verifyWith(this.key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            final String subject = claims.getSubject();
            if (subject == null) {
                throw new InvalidJwtException("@JjwtSigner.verify: invalid or expired token");
            }

            final Object rawVersion = claims.get(TOKEN_VERSION_CLAIM);
            // Absent on a token signed before this claim existed - read as 0, the version every
            // account starts at, so a token already in a client's hands keeps working until it
            // expires. Read as a Number rather than a fixed type: the deserializer may hand back
            // either an Integer or a Long.
            final int tokenVersion = rawVersion instanceof Number number ? number.intValue() : 0;
            return new VerifiedAccessToken(subject, tokenVersion);
        } catch (final JwtException e) {
            throw new InvalidJwtException("@JjwtSigner.verify: invalid or expired token", e);
        }
    }

}
