package de.lino.cloud.auth.pending;

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
 * A freshly generated TOTP secret, not yet promoted to {@link AuthUser#getTotpSecretBase32()} -
 * the intermediate state {@link AuthService#beginTwoFactorSetup} leaves behind while the caller
 * proves (via {@link AuthService#confirmTwoFactorSetup}) that it can actually produce a valid code
 * from the secret, before this codebase ever trusts it as that account's live second factor. The
 * same "don't commit a not-yet-proven secret" shape {@link PendingRegistration} already uses for a
 * not-yet-verified password.
 *
 * <p>Keyed on {@code authUserId} (an already-authenticated account's own id), the same way {@link
 * PendingEmailChange} is - the caller is already identified via their bearer token by the time
 * {@link AuthService#beginTwoFactorSetup} runs. A repeated setup attempt for the same account
 * simply overwrites the previous one (a fresh secret/expiry) via {@code
 * EntityDatabaseClient#store}'s insert-then-update-on-collision fallback, the same shape every
 * other {@code Pending*} entity in this package already uses.
 *
 * <p>Envelope-encrypted like every other {@link Serialized} entity, so the still-unconfirmed
 * secret sitting here for up to {@link AuthService}'s configured TTL is protected at rest the same
 * way any other sensitive field in this codebase is.
 */
@Getter @ToString(exclude = {"secretBase32"})
@EqualsAndHashCode(callSuper = false)
public final class PendingTwoFactorSetup extends Serialized {

    /** The {@link AuthUser#getId()} this pending setup is for; also this entity's primary key. */
    private final String authUserId;

    /** The freshly generated TOTP shared secret, base32-encoded - not yet live until {@link AuthService#confirmTwoFactorSetup} succeeds. */
    private final String secretBase32;

    /** The instant (epoch millis) after which {@link #isExpired()} reports {@code true}. */
    private final long expiresAtEpochMillis;

    /**
     * @param authUserId the account this pending setup is for, also its {@link #primaryKey()}
     * @param secretBase32 the freshly generated TOTP shared secret, base32-encoded
     * @param expiresAtEpochMillis the instant (epoch millis) after which {@link #isExpired()} reports {@code true}
     */
    public PendingTwoFactorSetup(@NotNull final String authUserId, @NotNull final String secretBase32, final long expiresAtEpochMillis) {
        this.authUserId = Objects.requireNonNull(authUserId, "@PendingTwoFactorSetup.init: authUserId cannot be null");
        this.secretBase32 = Objects.requireNonNull(secretBase32, "@PendingTwoFactorSetup.init: secretBase32 cannot be null");
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    /** @return {@code true} if {@link #expiresAtEpochMillis} is in the past */
    public boolean isExpired() {
        return System.currentTimeMillis() > this.expiresAtEpochMillis;
    }

    /** @return this entity's primary key, {@link #authUserId} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.authUserId);
    }

}
