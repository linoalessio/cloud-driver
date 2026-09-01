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
 * A not-yet-confirmed password reset waiting on e-mail verification - the intermediate state
 * {@link AuthService#requestPasswordReset} leaves behind. {@code emailAddress} doubles as this
 * entity's own primary key, so a repeated {@code POST /auth/reset-password} for the same address
 * simply overwrites the previous attempt (a fresh code/expiry) via {@code
 * EntityDatabaseClient#store}'s insert-then-update-on-collision fallback, the same shape {@link
 * PendingRegistration} already uses.
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

    /** The address this pending reset is for; also this entity's primary key. */
    private final String emailAddress;

    /** The code e-mailed to {@link #emailAddress}, expected back verbatim at {@link AuthService#confirmPasswordReset}. */
    private final String verificationCode;

    /** The instant (epoch millis) after which {@link #isExpired()} reports {@code true}. */
    private final long expiresAtEpochMillis;

    /**
     * @param emailAddress the address this pending reset is for, also its {@link #primaryKey()}
     * @param verificationCode the code sent to {@code emailAddress}, expected back verbatim at
     *     {@link AuthService#confirmPasswordReset}
     * @param expiresAtEpochMillis the instant (epoch millis) after which {@link #isExpired()}
     *     reports {@code true}
     */
    public PendingPasswordReset(@NotNull final String emailAddress, @NotNull final String verificationCode,
                                 final long expiresAtEpochMillis) {
        this.emailAddress = Objects.requireNonNull(emailAddress, "@PendingPasswordReset.init: emailAddress cannot be null");
        this.verificationCode = Objects.requireNonNull(verificationCode, "@PendingPasswordReset.init: verificationCode cannot be null");
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    /** @return {@code true} if {@link #expiresAtEpochMillis} is in the past */
    public boolean isExpired() {
        return System.currentTimeMillis() > this.expiresAtEpochMillis;
    }

    /** @return this entity's primary key, {@link #emailAddress} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.emailAddress);
    }

}
