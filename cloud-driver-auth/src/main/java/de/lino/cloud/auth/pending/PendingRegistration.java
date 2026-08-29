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
 * A not-yet-created account waiting on e-mail verification - the intermediate state {@link
 * AuthService#register} now leaves behind instead of persisting an {@link AuthUser} directly.
 * {@code emailAddress} doubles as this entity's own primary key, so a repeated {@code
 * POST /auth/register} for the same address simply overwrites the previous attempt (a fresh
 * code/password/expiry) via {@code EntityDatabaseClient#store}'s insert-then-update-on-collision
 * fallback, rather than piling up stale rows.
 *
 * <p>{@code passwordHash} is already hashed by the time this entity is constructed - {@link
 * AuthService#register} hashes it before ever building this row, so no plaintext password is
 * retained here any more than on {@link AuthUser} itself. {@link
 * AuthService#confirmRegistration} is the only place this row's {@code passwordHash} is read
 * again, to build the real {@link AuthUser} once the code is confirmed; this row is deleted
 * immediately afterward (or lazily, on a later confirm attempt, if it was found expired first).
 *
 * <p>Envelope-encrypted like every other {@link Serialized} entity, so the still-plaintext
 * verification code sitting here for up to {@link AuthService}'s configured TTL is protected at
 * rest the same way any other sensitive field in this codebase is.
 */
@Getter @ToString(exclude = "passwordHash")
@EqualsAndHashCode(callSuper = false)
public final class PendingRegistration extends Serialized {

    private final String emailAddress;
    private final String passwordHash;
    private final String verificationCode;
    private final long expiresAtEpochMillis;

    /**
     * @param emailAddress the address this pending registration is for, also its {@link
     *     #primaryKey()}
     * @param passwordHash a PHC-style Argon2id string produced by {@code PasswordHasher#hash} -
     *     never the raw password
     * @param verificationCode the code sent to {@code emailAddress}, expected back verbatim at
     *     {@link AuthService#confirmRegistration}
     * @param expiresAtEpochMillis the instant (epoch millis) after which {@link #isExpired()}
     *     reports {@code true}
     */
    public PendingRegistration(@NotNull final String emailAddress, @NotNull final String passwordHash,
                                @NotNull final String verificationCode, final long expiresAtEpochMillis) {
        this.emailAddress = Objects.requireNonNull(emailAddress, "@PendingRegistration.init: emailAddress cannot be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "@PendingRegistration.init: passwordHash cannot be null");
        this.verificationCode = Objects.requireNonNull(verificationCode, "@PendingRegistration.init: verificationCode cannot be null");
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
