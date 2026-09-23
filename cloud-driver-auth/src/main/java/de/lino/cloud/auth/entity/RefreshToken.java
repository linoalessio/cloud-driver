package de.lino.cloud.auth.entity;

import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.hash.LookupKeyDigest;
import de.lino.cloud.auth.AuthService;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A single, opaque, long-lived refresh token, exchanged by {@link AuthService#refresh} for a
 * fresh {@link de.lino.cloud.api.jwt.auth.AuthTokens} access/refresh pair - see that method's own
 * Javadoc for the rotate-on-every-use contract this entity backs.
 *
 * <p><b>The row is keyed on the token's digest, not the token.</b> Envelope encryption covers a
 * row's payload, not its {@code id} column, which the persistence layer writes as plain text - so
 * keying on the raw value would leave a live, self-renewing credential for every signed-in account
 * readable in the database and in every backup, without any need for the KMS-held key that
 * protects the rest. See {@link #keyOf(String)}. The lookup stays a single O(1) point read
 * regardless, since the presenter supplies the token and the server hashes it before looking it
 * up. The token itself stays inside the encrypted payload so a revocation listing can still name a
 * session; the digest is what the {@code id} column - and, through it, the payload's authenticated
 * data - carries. An account's own sessions are found through {@link #INDEX_AUTH_USER_ID}, never a
 * full scan.
 */
@Getter @ToString(exclude = {"token"})
@EqualsAndHashCode(callSuper = false)
public final class RefreshToken extends Serialized implements de.lino.cloud.api.factory.SecondaryIndexed {

    /**
     * Secondary-index name for lookups by {@link #getAuthUserId()} - how every session belonging
     * to one account is found, so a credential change can end all of them without a full scan.
     */
    public static final String INDEX_AUTH_USER_ID = "authUserId";

    /**
     * {@inheritDoc} Hand-declared, never reflective. Defensive against {@code null} fields, since
     * Gson rehydration bypasses the constructor's own null checks.
     */
    @NotNull
    @Override
    public java.util.Map<String, String> secondaryIndexKeys() {
        return this.authUserId == null ? java.util.Map.of() : java.util.Map.of(INDEX_AUTH_USER_ID, this.authUserId);
    }


    /** Length, in bytes, of the random token material generated for a fresh {@link RefreshToken}. */
    public static final int RAW_TOKEN_LENGTH_BYTES = 48;

    /**
     * The opaque token value itself, held inside the encrypted payload. Never this entity's
     * primary key - see {@link #keyOf(String)}. Excluded from {@link #toString()}.
     */
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

    /**
     * @return this entity's primary key: the SHA-256 digest of {@link #token}, never the token
     * itself - see {@link #keyOf(String)}
     */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(keyOf(this.token));
    }

    /**
     * The primary key a given raw refresh token is stored under - its lowercase hex SHA-256.
     *
     * <p>An entity's primary key lands in the row's {@code id} column verbatim, and that column
     * is plain text: envelope encryption covers the payload, not the key. Storing the token there
     * put a ready-to-use, self-renewing credential for every signed-in account in the clear in the
     * database and in every backup archive, recoverable without touching the KMS-held key that
     * protects everything else.
     *
     * <p>A digest keeps the lookup an O(1) point read - the presenter supplies the token and the
     * server hashes it before looking it up - while the stored value is useless on its own.
     * SHA-256 rather than a password hash deliberately: the input is {@link
     * #RAW_TOKEN_LENGTH_BYTES} bytes of {@code SecureRandom} output, so there is nothing to
     * brute-force and no reason to make every refresh pay a work factor. Unsalted deliberately
     * too: the lookup needs to be deterministic.
     *
     * @param token the raw token value, as held by the client
     * @return the primary key that token's row is stored under
     */
    @NotNull
    public static String keyOf(@NonNull final String token) {
        return LookupKeyDigest.hexOf(token);
    }

}
