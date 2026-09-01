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
 * A not-yet-confirmed e-mail address change waiting on e-mail verification - the intermediate
 * state {@link AuthService#requestEmailChange} leaves behind. Keyed on {@code authUserId} (an
 * already-authenticated account's own id), <b>not</b> {@code newEmailAddress} - the caller is
 * already identified via their bearer token by the time {@link AuthService#requestEmailChange}
 * runs, unlike {@link PendingRegistration}/{@link PendingPasswordReset}, which both key on the
 * address itself since neither has any other identity to key on yet. A repeated {@code
 * POST /auth/change-email} for the same account simply overwrites the previous attempt (a fresh
 * target address/code/expiry) via {@code EntityDatabaseClient#store}'s insert-then-update-on-
 * collision fallback, the same shape {@link PendingRegistration}/{@link PendingPasswordReset}
 * already use.
 *
 * <p>Envelope-encrypted like every other {@link Serialized} entity, so the still-plaintext
 * verification code sitting here for up to {@link AuthService}'s configured TTL is protected at
 * rest the same way any other sensitive field in this codebase is.
 */
@Getter @ToString
@EqualsAndHashCode(callSuper = false)
public final class PendingEmailChange extends Serialized {

    /** The {@link AuthUser#getId()} this pending change is for; also this entity's primary key. */
    private final String authUserId;

    /** The address this account would move to on confirmation - not yet live until {@link AuthService#confirmEmailChange} succeeds. */
    private final String newEmailAddress;

    /** The code e-mailed to {@link #newEmailAddress}, expected back verbatim at {@link AuthService#confirmEmailChange}. */
    private final String verificationCode;

    /** The instant (epoch millis) after which {@link #isExpired()} reports {@code true}. */
    private final long expiresAtEpochMillis;

    /**
     * @param authUserId the account this pending change is for, also its {@link #primaryKey()}
     * @param newEmailAddress the address this account would move to on confirmation
     * @param verificationCode the code sent to {@code newEmailAddress}, expected back verbatim at
     *     {@link AuthService#confirmEmailChange}
     * @param expiresAtEpochMillis the instant (epoch millis) after which {@link #isExpired()}
     *     reports {@code true}
     */
    public PendingEmailChange(@NotNull final String authUserId, @NotNull final String newEmailAddress,
                               @NotNull final String verificationCode, final long expiresAtEpochMillis) {
        this.authUserId = Objects.requireNonNull(authUserId, "@PendingEmailChange.init: authUserId cannot be null");
        this.newEmailAddress = Objects.requireNonNull(newEmailAddress, "@PendingEmailChange.init: newEmailAddress cannot be null");
        this.verificationCode = Objects.requireNonNull(verificationCode, "@PendingEmailChange.init: verificationCode cannot be null");
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
