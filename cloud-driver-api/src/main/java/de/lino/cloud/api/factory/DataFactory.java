package de.lino.cloud.api.factory;

import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Encrypts, persists, and retrieves {@link Serialized} domain entities -
 * reached through {@code CloudAPI#getDataFactory()}. Every meta is
 * envelope-encrypted (AES-256-GCM, section 9, DATA AT REST) before it is
 * written, so the configured database only ever holds ciphertext.
 *
 * <p>Only {@link #register}, {@link #update}, {@link #fetch}, {@link
 * #findById}, {@link #delete}, {@link #getEntities}, {@link #clear}, and
 * {@link #deleteSection} (single and batch variants where applicable) are
 * abstract; every {@code *Async} variant below is implemented here,
 * generically, in terms of those - the same "abstract primitives + generic
 * concrete methods" shape {@link FileFactory} and {@link ExtensionFactory}
 * use.
 */
public abstract class DataFactory {

        /**
     * Encrypts {@code meta} and stores it in the configured database, per
     * the security requirements (section 9, DATA AT REST): envelope-encrypted
     * with AES-256-GCM before it is written, so the database only ever holds
     * ciphertext.
     *
     * @param entity the domain meta to store
     * @param <T> the meta type
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the meta's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void register(@NotNull T entity) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts and stores every meta in {@code entities}, each under its
     * own {@link Serialized#primaryKey() primary key}, the same way {@link
     * #register(Serialized)} stores a single meta. Implementations dispatch
     * entities concurrently rather than one at a time - see the concrete
     * implementation's Javadoc for the exact failure semantics of a batch
     * with more than one failing meta.
     *
     * @param entities the domain entities to store
     * @param <T> the meta type
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any meta's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void register(@NotNull T... entities) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts {@code meta} and overwrites the existing database record
     * stored under its {@link Serialized#primaryKey() primary key}. Unlike
     * {@link #register(Serialized)}, which inserts-or-updates, this fails if no
     * such record exists yet - use it when the caller means "this already
     * exists and I'm changing it", not "store this, however that happens to
     * work out".
     *
     * @param entity the domain meta to overwrite the existing record with
     * @param <T> the meta type
     * @throws DatabaseClientException if no meta exists under {@code meta}'s primary key, or the persistence operation otherwise fails
     * @throws KeyWrapException if the meta's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void update(@NotNull T entity) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts and overwrites the existing database record of every meta in
     * {@code entities}, each under its own {@link Serialized#primaryKey()
     * primary key}, the same way {@link #update(Serialized)} overwrites a
     * single meta. Implementations dispatch entities concurrently rather
     * than one at a time - see the concrete implementation's Javadoc for the
     * exact failure semantics of a batch with more than one failing meta.
     *
     * @param entities the domain entities to overwrite the existing records with
     * @param <T> the meta type
     * @throws DatabaseClientException if no meta exists under any meta's primary key, or any persistence operation otherwise fails
     * @throws KeyWrapException if any meta's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void update(@NotNull T... entities) throws DatabaseClientException, KeyWrapException;

    /**
     * Retrieves the meta stored under {@code objectId} from the database
     * and decrypts it back into an instance of {@code type}, verifying its
     * authentication tag before returning any plaintext.
     *
     * @param objectId the meta's {@link Serialized#primaryKey() primary key}
     * @param type the concrete meta type to decrypt into
     * @param <T> the meta type
     * @return the decrypted meta
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the meta's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> T fetch(@NotNull String objectId, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Retrieves every meta stored under {@code objectIds} from the
     * database and decrypts each one back into an instance of {@code type},
     * in the same order as {@code objectIds}, the same way {@link
     * #fetch(String, Class)} retrieves a single meta.
     *
     * @param objectIds the entities' {@link Serialized#primaryKey() primary keys}
     * @param type the concrete meta type to decrypt into
     * @param <T> the meta type
     * @return the decrypted entities, in the same order as {@code objectIds}
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any meta's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> List<T> fetch(@NotNull String[] objectIds, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Looks up the meta stored under {@code objectId} the same way {@link
     * #fetch(String, Class)} does (cache first, then database), but returns
     * {@link Optional#empty()} instead of throwing when no such meta
     * exists - for callers that mean "does this exist?" rather than "this
     * must exist". Only a confirmed-absent id becomes {@code empty()}; a
     * corrupted record, an unwrappable key, or a failed authentication check
     * still throw exactly like {@link #fetch(String, Class)} does, since
     * those are real failures, not absence.
     *
     * @param objectId the meta's {@link Serialized#primaryKey() primary key}
     * @param type the concrete meta type to decrypt into
     * @param <T> the meta type
     * @return the decrypted meta, or {@link Optional#empty()} if no meta exists under {@code objectId}
     * @throws DatabaseClientException if the meta exists but its record is corrupted
     * @throws KeyWrapException if the meta's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> Optional<T> findById(@NotNull String objectId, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Retrieves and decrypts every meta of {@code type} currently stored
     * in the configured database, the same way {@link #fetch(String, Class)}
     * decrypts a single meta, applied to every meta of {@code type}
     * that currently exists.
     *
     * @param type the concrete meta type to decrypt into
     * @param <T> the meta type
     * @return every decrypted meta of {@code type}, in no particular guaranteed order
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if any meta's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> List<T> getEntities(@NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Deletes the meta stored under {@code objectId} from the configured
     * database. {@code type} identifies which {@link
     * de.lino.database.database.DatabaseSection database section} the meta
     * lives in, the same way {@link #fetch(String, Class)}'s {@code type}
     * does.
     *
     * @param objectId the meta's {@link Serialized#primaryKey() primary key}
     * @param type the meta type stored under {@code objectId}
     * @param <T> the meta type
     * @throws DatabaseClientException if no meta exists under {@code objectId}, or the persistence operation otherwise fails
     */
    public abstract <T extends Serialized> void delete(@NotNull String objectId, @NotNull Class<T> type) throws DatabaseClientException;

    /**
     * Deletes every meta stored under {@code objectIds} from the
     * configured database, the same way {@link #delete(String, Class)}
     * deletes a single meta. Implementations dispatch ids concurrently
     * rather than one at a time - see the concrete implementation's Javadoc
     * for the exact failure semantics of a batch with more than one failing
     * id.
     *
     * @param objectIds the entities' {@link Serialized#primaryKey() primary keys}
     * @param type the meta type stored under every id in {@code objectIds}
     * @param <T> the meta type
     * @throws DatabaseClientException if no meta exists under any of {@code objectIds}, or any persistence operation otherwise fails
     */
    public abstract <T extends Serialized> void delete(@NotNull String[] objectIds, @NotNull Class<T> type) throws DatabaseClientException;

    /**
     * Clears every meta of {@code type} from the configured database,
     * leaving the underlying database section itself intact - the entities
     * are gone, but the section they were stored in still exists and is
     * ready to receive new ones. Use {@link #deleteSection} instead to
     * remove the section itself.
     *
     * @param type the meta type whose section to clear
     * @param <T> the meta type
     */
    public abstract <T extends Serialized> void clear(@NotNull Class<T> type);

    /**
     * Deletes the database section {@code type} is stored in entirely - not
     * just its entries, the section itself. A later {@link #register} of an
     * meta of {@code type} lazily recreates the section, the same way it
     * is lazily created the first time any meta of {@code type} is
     * stored.
     *
     * @param type the meta type whose section to delete
     * @param <T> the meta type
     */
    public abstract <T extends Serialized> void deleteSection(@NotNull Class<T> type);

    /**
     * Async counterpart of {@link #register(Serialized)}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor so the calling
     * thread never blocks on database or KMS I/O. On failure, the returned
     * future completes exceptionally with a {@link CompletionException}
     * wrapping the checked exception {@link #register(Serialized)} would
     * otherwise have thrown.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> registerAsync(@NotNull final T entity) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.register(entity);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #register(Serialized[])}.
     */
    @NotNull
    @SafeVarargs
    public final <T extends Serialized> CompletableFuture<Void> registerAsync(@NotNull final T... entities) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.register(entities);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #update(Serialized)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> updateAsync(@NotNull final T entity) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.update(entity);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #update(Serialized[])}.
     */
    @NotNull
    @SafeVarargs
    public final <T extends Serialized> CompletableFuture<Void> updateAsync(@NotNull final T... entities) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.update(entities);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #fetch(String, Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<T> fetchAsync(@NotNull final String objectId, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.fetch(objectId, type);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #fetch(String[], Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<List<T>> fetchAsync(@NotNull final String[] objectIds, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.fetch(objectIds, type);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #findById(String, Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Optional<T>> findByIdAsync(@NotNull final String objectId, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.findById(objectId, type);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #getEntities(Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<List<T>> getEntitiesAsync(@NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return this.getEntities(type);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #delete(String, Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> deleteAsync(@NotNull final String objectId, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.delete(objectId, type);
            } catch (final DatabaseClientException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #delete(String[], Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> deleteAsync(@NotNull final String[] objectIds, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.delete(objectIds, type);
            } catch (final DatabaseClientException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #clear(Class)}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> clearAsync(@NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.clear(type));
    }

    /**
     * Async counterpart of {@link #deleteSection(Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> deleteSectionAsync(@NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.deleteSection(type));
    }

}
