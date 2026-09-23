package de.lino.cloud.auth;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.auth.entity.RefreshToken;
import de.lino.cloud.auth.pending.PendingPasswordReset;
import de.lino.cloud.auth.pending.PendingRegistration;
import de.lino.database.database.entity.Serialized;
import lombok.NonNull;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Moves rows that are still filed under the value they are looked up by onto the digest of that
 * value.
 *
 * <p>A {@link RefreshToken}, a {@link PendingRegistration} and a {@link PendingPasswordReset} are
 * each found by a value their caller presents - a token, an e-mail address. A row's primary key
 * lands in its {@code id} column verbatim and that column is never encrypted, and the same key is
 * bound into the payload's authenticated data, so a row filed under the presented value carries
 * that value twice in the clear. Such a row cannot be corrected with a plain {@code UPDATE}: the
 * copy inside the authenticated data would survive it. It has to be decrypted and written back,
 * which is only possible from inside this process.
 *
 * <p>Idempotent and safe to re-run on every boot. A row is recognised as still needing the move
 * purely by the presented value still resolving a row - not by guessing at the shape of an id -
 * so a pass that finished leaves nothing to do, and a pass interrupted halfway is completed by
 * the next one. Each row is re-registered under its digest <em>before</em> the old row is
 * deleted, so an interruption leaves the session working under both keys rather than under
 * neither.
 */
public final class LegacyRowKeyMigration {

    /** Not instantiable - a pure namespace for {@link #migrate(DataFactory)}. */
    private LegacyRowKeyMigration() {
    }

    /**
     * Moves every row of the three affected types onto its digest key, discarding rows that are
     * already dead rather than re-encrypting them.
     *
     * <p>Never throws for a row it cannot read or write: one unreadable row must not stop the
     * rest, and a row left behind is still resolvable, because {@link AuthService} retries a
     * presented value against its own key when the digest lookup misses.
     *
     * @param dataFactory the factory holding the three sections
     * @return how many rows were moved or discarded in total
     */
    public static int migrate(@NonNull final DataFactory dataFactory) {

        final int refreshTokens = migrateType(dataFactory, RefreshToken.class, RefreshToken::getToken,
                token -> token.isExpired() || token.isRevoked());
        final int registrations = migrateType(dataFactory, PendingRegistration.class, PendingRegistration::getEmailAddress,
                PendingRegistration::isExpired);
        final int passwordResets = migrateType(dataFactory, PendingPasswordReset.class, PendingPasswordReset::getEmailAddress,
                PendingPasswordReset::isExpired);

        final int total = refreshTokens + registrations + passwordResets;
        if (total > 0) {
            logger().info("@LegacyRowKeyMigration.migrate: re-keyed " + refreshTokens + " refresh token(s), "
                    + registrations + " pending registration(s) and " + passwordResets + " pending password reset(s)");
        }
        return total;
    }

    /**
     * Moves every row of one type whose {@code id} is still the value it is looked up by.
     *
     * @param dataFactory the factory holding the section
     * @param type the entity type to sweep
     * @param legacyKeyOf reads the value a row of this type used to be filed under
     * @param discardable reports a row that is already dead, and so is deleted rather than moved
     * @param <T> the entity type
     * @return how many rows of this type were moved or discarded
     */
    private static <T extends Serialized> int migrateType(final DataFactory dataFactory, final Class<T> type,
                                                           final Function<T, String> legacyKeyOf,
                                                           final Predicate<T> discardable) {
        final List<T> entities;
        try {
            entities = dataFactory.getEntities(type);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | RuntimeException listingFailed) {
            logger().log(Level.WARNING, "@LegacyRowKeyMigration.migrateType: could not list " + type.getSimpleName()
                    + " rows - leaving them for the next boot", listingFailed);
            return 0;
        }

        int handled = 0;
        for (final T entity : entities) {
            try {
                final String legacyKey = legacyKeyOf.apply(entity);
                if (legacyKey == null || legacyKey.equals(entity.primaryKey())) {
                    continue;
                }
                if (dataFactory.findById(legacyKey, type).isEmpty()) {
                    // Nothing is filed under the old key any more, so this row has already moved.
                    // A presence check, rather than a guess at the shape of an id, is what makes
                    // every later pass a no-op.
                    continue;
                }
                if (discardable.test(entity)) {
                    dataFactory.delete(legacyKey, type);
                    handled++;
                    continue;
                }
                // Registered first, always: the store is an upsert, so a repeat is harmless, and
                // an interruption between the two leaves the row resolvable under both keys
                // instead of neither. Re-registering is also the only thing that rewrites the
                // copy of the old key held inside the payload's authenticated data.
                dataFactory.register(entity);
                dataFactory.delete(legacyKey, type);
                handled++;
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | RuntimeException rowFailed) {
                // The type only - never the token, the address, the old key or the digest.
                logger().log(Level.WARNING, "@LegacyRowKeyMigration.migrateType: could not re-key one "
                        + type.getSimpleName() + " row - leaving it for the next boot", rowFailed);
            }
        }
        return handled;
    }

    /** @return the process-wide logger */
    private static java.util.logging.Logger logger() {
        return CloudDriver.getInstance().getLogger();
    }

}
