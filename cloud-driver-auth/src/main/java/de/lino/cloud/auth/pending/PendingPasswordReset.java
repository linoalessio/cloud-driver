package de.lino.cloud.auth.pending;

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
 * A not-yet-confirmed password reset waiting on e-mail verification - the intermediate state
 * {@link AuthService#requestPasswordReset} leaves behind. Keyed on the <em>digest</em> of {@code
 * emailAddress} (see {@link #keyOf(String)}), which is still one key per address, so a repeated
 * {@code POST /auth/reset-password} for the same address simply overwrites the previous attempt
 * (a fresh code/expiry) via {@code EntityDatabaseClient#store}'s insert-then-update-on-collision
 * fallback, the same shape {@link PendingRegistration} already uses - while the row's {@code id}
 * column, which the persistence layer writes as plain text, no longer reveals which addresses
 * have a reset in flight.
 *
 * <p>Unlike {@link PendingRegistration}, this row carries no password of its own (hashed or
 * otherwise) - the caller's chosen new password is only ever supplied once, directly to {@link
 * AuthService#confirmPasswordReset}, and is hashed and written straight onto the existing {@link
 * AuthUser} without ever passing through this intermediate row.
 *
 * <p>Envelope-encrypted like every other {@link Serialized} entity, so the still-plaintext
 * verification code sitting here for up to {@link AuthService}'s configured TTL is protected at
 * rest the same way any other sensitive field in this codebase is.
 */
@Getter @ToString
@EqualsAndHashCode(callSuper = false)
public final class PendingPasswordReset extends Serialized {

    /**
     * The address this pending reset is for, held inside the encrypted payload. The primary key
     * is its digest, never the address itself - see {@link #keyOf(String)}.
     */
    private final String emailAddress;

    /** The code e-mailed to {@link #emailAddress}, expected back verbatim at {@link AuthService#confirmPasswordReset}. */
    private final String verificationCode;

    /** The instant (epoch millis) after which {@link #isExpired()} reports {@code true}. */
    private final long expiresAtEpochMillis;

    /**
     * How many wrong verification codes have been presented against this row.
     *
     * <p>A six-digit code is roughly twenty bits, and without a per-code counter a wrong guess
     * costs an attacker nothing: the row stays valid for its whole lifetime however many times it
     * is tried. The address-based rate limiter cannot substitute for this, because it is bound to
     * the caller rather than to the code. {@code 0} for rows written before this field existed.
     */
    private final int failedAttempts;

    /**
     * A copy of this row with one more failed attempt recorded.
     *
     * @return a copy carrying the incremented count; every other field is unchanged
     */
    @NotNull
    public PendingPasswordReset withFailedAttempt() {
        return copyWithFailedAttempts(this.failedAttempts + 1);
    }

    /**
     * @param emailAddress the address this pending reset is for; its digest is this row's
     *     {@link #primaryKey()}
     * @param verificationCode the code sent to {@code emailAddress}, expected back verbatim at
     *     {@link AuthService#confirmPasswordReset}
     * @param expiresAtEpochMillis the instant (epoch millis) after which {@link #isExpired()}
     *     reports {@code true}
     */
    public PendingPasswordReset(@NotNull final String emailAddress, @NotNull final String verificationCode,
                                 final long expiresAtEpochMillis) {
        this(emailAddress, verificationCode, expiresAtEpochMillis, 0);
    }

    /**
     * The full constructor, carrying an explicit failed-attempt count - used by {@link
     * #withFailedAttempt()} and by Gson rehydration.
     *
     * @param emailAddress the address the reset was requested for; its digest is this entity's primary key
     * @param verificationCode the code e-mailed to {@code emailAddress}
     * @param expiresAtEpochMillis when this pending reset stops being usable
     * @param failedAttempts how many wrong codes have been presented against this row
     */
    public PendingPasswordReset(@NotNull final String emailAddress, @NotNull final String verificationCode,
                                 final long expiresAtEpochMillis, final int failedAttempts) {
        this.emailAddress = Objects.requireNonNull(emailAddress, "@PendingPasswordReset.init: emailAddress cannot be null");
        this.verificationCode = Objects.requireNonNull(verificationCode, "@PendingPasswordReset.init: verificationCode cannot be null");
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.failedAttempts = failedAttempts;
    }

    /** @param attempts the new failed-attempt count
     *  @return a copy of this row carrying {@code attempts} */
    @NotNull
    private PendingPasswordReset copyWithFailedAttempts(final int attempts) {
        return new PendingPasswordReset(this.emailAddress, this.verificationCode, this.expiresAtEpochMillis, attempts);
    }

    /** @return {@code true} if {@link #expiresAtEpochMillis} is in the past */
    public boolean isExpired() {
        return System.currentTimeMillis() > this.expiresAtEpochMillis;
    }

    /**
     * @return this entity's primary key: the digest of {@link #emailAddress}, never the address
     * itself - see {@link #keyOf(String)}
     */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(keyOf(this.emailAddress));
    }

    /**
     * The primary key a pending password reset for {@code emailAddress} is stored under - its
     * lowercase-hex SHA-256.
     *
     * <p>An entity's primary key lands in the row's {@code id} column verbatim, and that column
     * is plain text: envelope encryption covers the payload, not the key. Keying on the address
     * itself listed every account with a reset in flight in the clear, in the database and in
     * every backup archive. The digest keeps the lookup a single O(1) point read - the caller
     * supplies the address and the server hashes it - while the stored key says nothing.
     *
     * <p>Digests the address verbatim: no lower-casing and no trimming. {@link
     * AuthService#requestPasswordReset} stores the address exactly as supplied and looks it up
     * case-sensitively, so normalising here would silently change which rows match.
     *
     * @param emailAddress the address, exactly as the caller supplied it
     * @return the primary key that address's pending reset is stored under
     */
    @NotNull
    public static String keyOf(@NonNull final String emailAddress) {
        return LookupKeyDigest.hexOf(emailAddress);
    }

}
