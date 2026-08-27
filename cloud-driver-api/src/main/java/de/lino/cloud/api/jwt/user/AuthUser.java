package de.lino.cloud.api.jwt.user;

import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A persisted end-user account, envelope-encrypted like every other {@link
 * Serialized} entity. {@code passwordHash} is a PHC-style Argon2id string
 * produced by {@code PasswordHasher#hash} - never the raw password, which
 * this class never retains a field for at all (unlike {@code
 * de.lino.cloud.api.security.rest.ApiKey}, which does keep its raw value,
 * since a machine-generated API key must be handed back once; a user-chosen
 * password never needs to be).
 *
 * <p>Named {@code AuthUser} rather than plain {@code User} deliberately -
 * {@code EntityDatabaseClient} derives the underlying SQL table name from
 * {@code getSimpleName()}, and {@code USER} is a reserved keyword in
 * PostgreSQL (and standard SQL generally); an entity actually named {@code
 * User} produces an unquoted {@code CREATE TABLE User (...)}/{@code INSERT
 * INTO User ...} that Postgres rejects with a syntax error - and {@code
 * SQLExecution} logs that failure rather than throwing, so the failure is
 * silent unless you're watching stderr. This name sidesteps the collision
 * entirely instead of relying on identifier quoting this driver stack
 * doesn't do.
 *
 * <p>{@code id} is the primary key this entity is stored/looked up under -
 * see {@link de.lino.cloud.api.jwt.auth.IAuthService} for how login/registration constructs it.
 */
@Getter @ToString(exclude = {"passwordHash"})
@EqualsAndHashCode(callSuper = false)
public final class AuthUser extends Serialized {

    private final String id;
    private final String emailAddress;
    private final String passwordHash;

    public AuthUser(@NotNull final String id, @NotNull final String emailAddress, @NotNull final String passwordHash) {
        this.id = Objects.requireNonNull(id, "@AuthUser.init: id cannot be null");
        this.emailAddress = Objects.requireNonNull(emailAddress, "@AuthUser.init: username cannot be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "@AuthUser.init: passwordHash cannot be null");
    }

    /** @return this entity's primary key, {@link #id} */
    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

}
