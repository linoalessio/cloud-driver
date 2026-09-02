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

    /** This account's unique id, its {@link #primaryKey()}. */
    private final String id;

    /** This account's identifying email address. */
    private final String emailAddress;

    /** A PHC-style Argon2id string produced by {@code PasswordHasher#hash} - never the raw password. */
    private final String passwordHash;

    /**
     * Whether this account carries the (single, boolean) admin flag - {@code false} for every
     * account by default, including one deserialized from a database row written before this
     * field existed (Gson leaves an absent JSON property at its type's default, and a freshly
     * registered account only ever goes through the 3-arg {@link #AuthUser(String, String,
     * String)} constructor below, which fixes it at {@code false} too). Deliberately a single
     * boolean rather than a roles/permissions system - see {@code architecture/SERVICES.md}
     * item 5's own reasoning for why a fuller RBAC model isn't warranted at this codebase's
     * scale. Settable only via {@link #withAdmin(boolean)} (never a REST route - see {@code
     * DefaultRestFactory}'s {@code /admin/authUsers} routes, which only ever read this field);
     * the only writer is a new terminal {@code Command}, granting/revoking it from the operator
     * console.
     */
    private final boolean isAdmin;

    /**
     * Constructs an account record for an already-hashed password, with {@link #isAdmin} fixed
     * at {@code false} - every account created through registration starts as a non-admin; use
     * {@link #withAdmin(boolean)} to change that afterward.
     *
     * @param id this account's unique id, its {@link #primaryKey()}
     * @param emailAddress this account's identifying email address
     * @param passwordHash a PHC-style Argon2id string produced by {@code PasswordHasher#hash} - never the raw password
     * @throws NullPointerException if any argument is {@code null}
     */
    public AuthUser(@NotNull final String id, @NotNull final String emailAddress, @NotNull final String passwordHash) {
        this(id, emailAddress, passwordHash, false);
    }

    /**
     * Constructs an account record for an already-hashed password with an explicit {@link
     * #isAdmin} value - used by {@link #withAdmin(boolean)} and by any caller reconstructing an
     * existing account (e.g. after a password/email change) that must carry the flag forward
     * rather than silently resetting it to {@code false}.
     *
     * @param id this account's unique id, its {@link #primaryKey()}
     * @param emailAddress this account's identifying email address
     * @param passwordHash a PHC-style Argon2id string produced by {@code PasswordHasher#hash} - never the raw password
     * @param isAdmin this account's admin flag
     * @throws NullPointerException if {@code id}/{@code emailAddress}/{@code passwordHash} is {@code null}
     */
    public AuthUser(@NotNull final String id, @NotNull final String emailAddress, @NotNull final String passwordHash, final boolean isAdmin) {
        this.id = Objects.requireNonNull(id, "@AuthUser.init: id cannot be null");
        this.emailAddress = Objects.requireNonNull(emailAddress, "@AuthUser.init: username cannot be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "@AuthUser.init: passwordHash cannot be null");
        this.isAdmin = isAdmin;
    }

    /**
     * Returns a copy of this account with {@link #isAdmin} set to {@code isAdmin} - the
     * immutable "return a new instance" convention this codebase's other entities ({@code
     * Folder#renamedTo}/{@code #movedTo}) already use, rather than a mutable setter. The caller
     * persists the returned copy via {@code DataFactory#update}.
     *
     * @param isAdmin the new admin flag value
     * @return a copy of this account with {@link #isAdmin} changed to {@code isAdmin}
     */
    @NotNull
    public AuthUser withAdmin(final boolean isAdmin) {
        return new AuthUser(this.id, this.emailAddress, this.passwordHash, isAdmin);
    }

    /** @return this entity's primary key, {@link #id} */
    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

}
