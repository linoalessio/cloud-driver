package de.lino.cloud.auth.entity;

import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.auth.AuthService;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A single, opaque, long-lived refresh token, exchanged by {@link AuthService#refresh} for a
 * fresh {@link de.lino.cloud.api.jwt.auth.AuthTokens} access/refresh pair - see that method's own
 * Javadoc for the rotate-on-every-use contract this entity backs.
 *
 * <p><b>Stored as its own primary key, not hashed like {@code
 * de.lino.cloud.api.security.rest.ApiKey}'s raw+hash pair.</b> Unlike an {@code ApiKey} (whose raw
 * value must be handed back to an operator at least once, so it's kept in a separate field from
 * its digest), a refresh token is only ever generated for, and later presented back by, the same
 * client - it is never redisplayed to an operator, so there is no separate "display" need driving
 * a raw+hash split here. Storing the token itself as {@link #token} (this entity's own primary
 * key) instead lets {@link AuthService#refresh} resolve it via a single {@code
 * DataFactory#findById} lookup - the same O(1) shape {@code ApiKey}'s own digest-based verification
 * exists specifically to avoid needing, since {@code DataFactory} has no secondary-index query
 * other than a full-table scan (the shape {@link AuthService#login} already accepts for its own
 * non-primary-key {@code emailAddress} lookup, which would be a real cost paid on every single
 * token refresh here). This matches the precedent already set by {@code
 * de.lino.cloud.auth.pending.PendingRegistration}/{@code PendingPasswordReset}/{@code
 * PendingEmailChange}, which likewise store their own plaintext verification codes as a field
 * (one of them, {@code PendingRegistration}, even as part of its own primary key) - every one of
 * these rows relies on this codebase's envelope encryption at rest (AES-256-GCM under a wrapped
 * DEK, applied to every {@link Serialized} entity uniformly) as its actual security boundary,
 * rather than an additional application-level hash. A raw token is only ever "self-authenticating"
 * in the sense that presenting it back is itself the proof of possession {@link
 * AuthService#refresh} checks for - there is no verification step this design weakens, since
 * {@link AuthService#refresh} never accepts anything less specific than the exact token value.
 */
@Getter @ToString(exclude = {"token"})
@EqualsAndHashCode(callSuper = false)
public final class RefreshToken extends Serialized {

    /** Length, in bytes, of the random token material generated for a fresh {@link RefreshToken}. */
    public static final int RAW_TOKEN_LENGTH_BYTES = 48;

    /** The opaque token value itself - also this entity's primary key. Excluded from {@link #toString()}. */
    private final String token;

    /** The {@link AuthUser#getId()} this token was issued for. */
    private final String authUserId;

    /** The instant (epoch millis) after which {@link #isExpired()} reports {@code true}. */
    private final long expiresAtEpochMillis;

    /**
     * Whether this token has already been consumed by a successful {@link AuthService#refresh}
     * call (rotated away) or otherwise explicitly invalidated. A rotated-away token's row is kept
     * (rather than deleted) purely so a second, racing presentation of the same now-stale token
     * is rejected with the same {@link de.lino.cloud.api.jwt.InvalidRefreshTokenException} every
     * other rejection reason produces, instead of looking like "never existed" - {@link
     * AuthService#refresh} still deletes the old row as its actual atomicity mechanism (see that
     * method's own Javadoc), so in practice a revoked-but-not-yet-deleted row is only ever
     * observable within the same in-process check-then-delete sequence, not as standing state.
     */
    private final boolean revoked;

    /**
     * Generates a fresh, random refresh token (48 bytes via {@link java.security.SecureRandom},
     * base64url-encoded - the same generation style {@code ApiKey}'s own constructor uses, just a
     * longer value since this token is presented far less often and over a longer lifetime than a
     * request-scoped access JWT), not yet revoked.
     *
     * @param authUserId the {@link AuthUser#getId()} this token is issued for
     * @param expiresAtEpochMillis the instant (epoch millis) after which {@link #isExpired()} reports {@code true}
     */
    public RefreshToken(@NotNull final String authUserId, final long expiresAtEpochMillis) {
        this(generateToken(), authUserId, expiresAtEpochMillis, false);
    }

    /**
     * Full constructor, used internally by {@link #revoked()} to produce a copy with {@link
     * #revoked} flipped - a real caller only ever uses the single-argument-plus-expiry constructor
     * above to mint a brand-new token.
     */
    private RefreshToken(@NotNull final String token, @NotNull final String authUserId,
                          final long expiresAtEpochMillis, final boolean revoked) {
        this.token = Objects.requireNonNull(token, "@RefreshToken.init: token cannot be null");
        this.authUserId = Objects.requireNonNull(authUserId, "@RefreshToken.init: authUserId cannot be null");
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.revoked = revoked;
    }

    /** @return a fresh, random, base64url-encoded token of {@link #RAW_TOKEN_LENGTH_BYTES} bytes */
    private static String generateToken() {
        final byte[] raw = new byte[RAW_TOKEN_LENGTH_BYTES];
        new java.security.SecureRandom().nextBytes(raw);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @return {@code true} if {@link #expiresAtEpochMillis} is in the past */
    public boolean isExpired() {
        return System.currentTimeMillis() > this.expiresAtEpochMillis;
    }

    /**
     * Returns a copy of this token with {@link #revoked} set to {@code true} - see that field's
     * own Javadoc for why a rotated-away token is marked rather than deleted at the point this is
     * called. The immutable "return a new instance" convention this codebase's other entities
     * ({@code Folder#renamedTo}/{@code #movedTo}) already use.
     *
     * @return a copy of this token, revoked
     */
    @NotNull
    public RefreshToken revoked() {
        return new RefreshToken(this.token, this.authUserId, this.expiresAtEpochMillis, true);
    }

    /** @return this entity's primary key, {@link #token} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.token);
    }

}
