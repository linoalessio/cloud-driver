package de.lino.cloud.auth.pending;

import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.auth.AuthService;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * The short-lived, single-use intermediate state {@link AuthService#login} leaves behind instead
 * of a real {@code AuthTokens} pair when the matched {@link AuthUser} has two-factor authentication
 * enabled ({@link AuthUser#isTwoFactorEnabled()}) - the password has already been verified at this
 * point, but the caller must still present a valid TOTP code (via {@link
 * AuthService#completeTwoFactorLogin}) before this codebase issues real tokens.
 *
 * <p><b>Deliberately not a signed JWT.</b> The original design sketch for this feature considered a
 * short-TTL JWT carrying a distinguishing "pending-2fa" claim, but that needs either a second signer
 * (a second key to manage) or a claim {@code AuthService#validate} would have to specifically check
 * for and reject on every single call, just to prevent this intermediate value from ever being
 * usable as a real bearer token. An opaque, randomly generated, persisted token - the same "stored
 * as its own primary key, checked via a single {@code DataFactory#findById}" shape {@link
 * de.lino.cloud.auth.entity.RefreshToken} already uses, see that class's own Javadoc for why a
 * separate hash isn't needed here either - sidesteps the whole question: it isn't a JWT at all, so
 * {@link de.lino.cloud.auth.jwt.JjwtSigner#verify} rejects it immediately (as malformed) if a caller
 * ever tried to present it as a normal {@code Authorization: Bearer} token, with no special-casing
 * needed anywhere in the validation path.
 *
 * <p><b>Single-use, but only on a successful code.</b> {@link AuthService#completeTwoFactorLogin}
 * only deletes this row once the presented TOTP code has actually verified - a caller that
 * mistypes the 6-digit code gets to retry (within {@link #expiresAtEpochMillis}) rather than having
 * to log in with the password again from scratch. This means two near-simultaneous calls
 * presenting the same token and the same (correct) code could theoretically both succeed before
 * either's delete lands - accepted as a low-severity edge case (it requires already knowing both
 * the account's password and a currently-valid TOTP code), unlike {@link
 * de.lino.cloud.auth.entity.RefreshToken#revoked()}'s stricter delete-before-issuing ordering,
 * which guards a token that's the caller's *only* proof of identity.
 *
 * <p>Envelope-encrypted like every other {@link Serialized} entity, so this token is protected at
 * rest the same way any other sensitive field in this codebase is - though, as with {@link
 * de.lino.cloud.auth.entity.RefreshToken}, its real security property is simply never being
 * guessable, not secrecy of the stored row itself.
 */
@Getter @ToString(exclude = {"token"})
@EqualsAndHashCode(callSuper = false)
public final class PendingTwoFactorLogin extends Serialized {

    /** Length, in bytes, of the random token material generated for a fresh {@link PendingTwoFactorLogin}. */
    public static final int RAW_TOKEN_LENGTH_BYTES = 32;

    /** Source of randomness for {@link #generateToken()}. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** The opaque token value itself - also this entity's primary key. Excluded from {@link #toString()}. */
    private final String token;

    /** The {@link AuthUser#getId()} whose password has already been verified, awaiting its TOTP code. */
    private final String authUserId;

    /** The instant (epoch millis) after which {@link #isExpired()} reports {@code true}. */
    private final long expiresAtEpochMillis;

    /**
     * Generates a fresh, random pending-login token (32 bytes via {@link SecureRandom},
     * base64url-encoded - the same generation style {@code ApiKey}/{@code RefreshToken} already use).
     *
     * @param authUserId the account this pending login is for
     * @param expiresAtEpochMillis the instant (epoch millis) after which {@link #isExpired()} reports {@code true}
     */
    public PendingTwoFactorLogin(@NotNull final String authUserId, final long expiresAtEpochMillis) {
        this(generateToken(), authUserId, expiresAtEpochMillis);
    }

    /** Full constructor - a real caller only ever uses the single-argument-plus-expiry constructor above to mint a brand-new token. */
    private PendingTwoFactorLogin(@NotNull final String token, @NotNull final String authUserId, final long expiresAtEpochMillis) {
        this.token = Objects.requireNonNull(token, "@PendingTwoFactorLogin.init: token cannot be null");
        this.authUserId = Objects.requireNonNull(authUserId, "@PendingTwoFactorLogin.init: authUserId cannot be null");
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    /** @return a fresh, random, base64url-encoded token of {@link #RAW_TOKEN_LENGTH_BYTES} bytes */
    private static String generateToken() {
        final byte[] raw = new byte[RAW_TOKEN_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @return {@code true} if {@link #expiresAtEpochMillis} is in the past */
    public boolean isExpired() {
        return System.currentTimeMillis() > this.expiresAtEpochMillis;
    }

    /** @return this entity's primary key, {@link #token} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.token);
    }

}
