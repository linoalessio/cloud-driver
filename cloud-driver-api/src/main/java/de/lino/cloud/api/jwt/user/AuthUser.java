package de.lino.cloud.api.jwt.user;

import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
     * This account's TOTP (RFC 6238) shared secret, base32-encoded - {@code null} means two-factor
     * authentication is disabled for this account, matching this codebase's existing "nullable
     * field = feature not opted into" convention (e.g. {@code Folder#parentFolderId}). Never
     * written directly to this field via {@link #login} - a fresh secret is first held in a
     * short-lived {@code de.lino.cloud.auth.pending.PendingTwoFactorSetup} row (see {@code
     * AuthService#beginTwoFactorSetup}) and only promoted here once {@code
     * AuthService#confirmTwoFactorSetup} has verified the caller can actually produce a valid code
     * from it - the same "don't commit a not-yet-proven secret" shape {@link
     * de.lino.cloud.auth.pending.PendingRegistration} already uses for a not-yet-verified password.
     * Settable only via {@link #withTotpSecret(String)}, from {@code AuthService#confirmTwoFactorSetup}
     * (sets it) and {@code AuthService#disableTwoFactor} (clears it back to {@code null}) - never
     * reachable from any REST route body, the same "server decides, client only triggers" shape
     * {@link #isAdmin} uses for its own writer.
     */
    @Nullable
    private final String totpSecretBase32;

    /**
     * Constructs an account record for an already-hashed password, with {@link #isAdmin} fixed
     * at {@code false} and two-factor authentication disabled - every account created through
     * registration starts this way; use {@link #withAdmin(boolean)}/{@link
     * #withTotpSecret(String)} to change either afterward.
     *
     * @param id this account's unique id, its {@link #primaryKey()}
     * @param emailAddress this account's identifying email address
     * @param passwordHash a PHC-style Argon2id string produced by {@code PasswordHasher#hash} - never the raw password
     * @throws NullPointerException if any argument is {@code null}
     */
    public AuthUser(@NotNull final String id, @NotNull final String emailAddress, @NotNull final String passwordHash) {
        this(id, emailAddress, passwordHash, false, null);
    }

    /**
     * Constructs an account record for an already-hashed password with an explicit {@link
     * #isAdmin} value and two-factor authentication disabled - used by {@link
     * #withAdmin(boolean)} and by any caller reconstructing an existing account that must carry
     * the admin flag forward but has no TOTP secret to carry (or is deliberately not changing it -
     * see {@link #withTotpSecret(String)}'s own Javadoc for why every caller reconstructing an
     * existing account must instead go through the 5-argument constructor once that account may
     * already have two-factor authentication enabled).
     *
     * @param id this account's unique id, its {@link #primaryKey()}
     * @param emailAddress this account's identifying email address
     * @param passwordHash a PHC-style Argon2id string produced by {@code PasswordHasher#hash} - never the raw password
     * @param isAdmin this account's admin flag
     * @throws NullPointerException if {@code id}/{@code emailAddress}/{@code passwordHash} is {@code null}
     */
    public AuthUser(@NotNull final String id, @NotNull final String emailAddress, @NotNull final String passwordHash, final boolean isAdmin) {
        this(id, emailAddress, passwordHash, isAdmin, null);
    }

    /**
     * Full constructor, used by {@link #withAdmin(boolean)}/{@link #withTotpSecret(String)} and by
     * any caller reconstructing an existing account (e.g. after a password/email change) that must
     * carry both flags/fields forward rather than silently resetting either.
     *
     * @param id this account's unique id, its {@link #primaryKey()}
     * @param emailAddress this account's identifying email address
     * @param passwordHash a PHC-style Argon2id string produced by {@code PasswordHasher#hash} - never the raw password
     * @param isAdmin this account's admin flag
     * @param totpSecretBase32 this account's TOTP shared secret, or {@code null} if two-factor authentication is disabled
     * @throws NullPointerException if {@code id}/{@code emailAddress}/{@code passwordHash} is {@code null}
     */
    public AuthUser(@NotNull final String id, @NotNull final String emailAddress, @NotNull final String passwordHash,
                     final boolean isAdmin, @Nullable final String totpSecretBase32) {
        this.id = Objects.requireNonNull(id, "@AuthUser.init: id cannot be null");
        this.emailAddress = Objects.requireNonNull(emailAddress, "@AuthUser.init: username cannot be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "@AuthUser.init: passwordHash cannot be null");
        this.isAdmin = isAdmin;
        this.totpSecretBase32 = totpSecretBase32;
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
        return new AuthUser(this.id, this.emailAddress, this.passwordHash, isAdmin, this.totpSecretBase32);
    }

    /**
     * Returns a copy of this account with {@link #totpSecretBase32} set to {@code totpSecretBase32} -
     * the same immutable "return a new instance" convention {@link #withAdmin(boolean)} uses. Pass a
     * real secret to enable two-factor authentication ({@code AuthService#confirmTwoFactorSetup}) or
     * {@code null} to disable it ({@code AuthService#disableTwoFactor}). The caller persists the
     * returned copy via {@code DataFactory#update}.
     *
     * @param totpSecretBase32 the new TOTP secret, or {@code null} to disable two-factor authentication
     * @return a copy of this account with {@link #totpSecretBase32} changed
     */
    @NotNull
    public AuthUser withTotpSecret(@Nullable final String totpSecretBase32) {
        return new AuthUser(this.id, this.emailAddress, this.passwordHash, this.isAdmin, totpSecretBase32);
    }

    /** @return {@code true} if this account has two-factor authentication enabled (i.e. {@link #totpSecretBase32} is non-{@code null}) */
    public boolean isTwoFactorEnabled() {
        return this.totpSecretBase32 != null;
    }

    /** @return this entity's primary key, {@link #id} */
    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

}
