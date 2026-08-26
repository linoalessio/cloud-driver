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
 * reached through {@code CloudDriver#getDataFactory()}. Every entity is
 * envelope-encrypted (AES-256-GCM) before being written, so the database
 * only ever holds ciphertext.
 *
 * <p>{@link #register}, {@link #update}, {@link #fetch}, {@link #findById},
 * {@link #delete}, {@link #getEntities}, {@link #clear}, {@link
 * #deleteSection}, {@link #reload}, and {@link #shutdown} are abstract;
 * every {@code *Async} variant below is implemented here generically in
 * terms of those.
 */
public abstract class DataFactory {

    /**
     * Encrypts and stores {@code entity} in the configured database,
     * inserting it if no record exists yet or overwriting it otherwise.
     *
     * @param entity the domain entity to store
     * @param <T> the entity type
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the entity's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void register(@NotNull T entity) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts and stores every entity in {@code entities}, concurrently.
     *
     * @param entities the domain entities to store
     * @param <T> the entity type
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any entity's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void register(@NotNull T... entities) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts {@code entity} and overwrites its existing database record.
     * Unlike {@link #register(Serialized)}, this fails if no such record
     * exists yet.
     *
     * @param entity the domain entity to overwrite the existing record with
     * @param <T> the entity type
     * @throws DatabaseClientException if no entity exists under its primary key, or persistence otherwise fails
     * @throws KeyWrapException if the entity's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void update(@NotNull T entity) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts and overwrites the existing record of every entity in {@code
     * entities}, concurrently.
     *
     * @param entities the domain entities to overwrite the existing records with
     * @param <T> the entity type
     * @throws DatabaseClientException if no entity exists under any primary key, or persistence otherwise fails
     * @throws KeyWrapException if any entity's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void update(@NotNull T... entities) throws DatabaseClientException, KeyWrapException;

    /**
     * Retrieves the entity stored under {@code objectId} and decrypts it,
     * verifying its authentication tag first.
     *
     * @param objectId the entity's {@link Serialized#primaryKey() primary key}
     * @param type the concrete entity type to decrypt into
     * @param <T> the entity type
     * @return the decrypted entity
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the entity's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> T fetch(@NotNull String objectId, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Retrieves and decrypts every entity in {@code objectIds}, in the same order.
     *
     * @param objectIds the entities' {@link Serialized#primaryKey() primary keys}
     * @param type the concrete entity type to decrypt into
     * @param <T> the entity type
     * @return the decrypted entities, in the same order as {@code objectIds}
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any entity's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> List<T> fetch(@NotNull String[] objectIds, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Looks up the entity stored under {@code objectId}, returning {@link
     * Optional#empty()} instead of throwing when it doesn't exist. A
     * corrupted record, an unwrappable key, or a failed authentication
     * check still throw, since those are real failures, not absence.
     *
     * @param objectId the entity's {@link Serialized#primaryKey() primary key}
     * @param type the concrete entity type to decrypt into
     * @param <T> the entity type
     * @return the decrypted entity, or {@link Optional#empty()} if none exists under {@code objectId}
     * @throws DatabaseClientException if the entity exists but its record is corrupted
     * @throws KeyWrapException if the entity's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> Optional<T> findById(@NotNull String objectId, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Retrieves and decrypts every entity of {@code type} currently stored.
     *
     * @param type the concrete entity type to decrypt into
     * @param <T> the entity type
     * @return every decrypted entity of {@code type}, in no particular order
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if any entity's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     */
    @NotNull
    public abstract <T extends Serialized> List<T> getEntities(@NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Deletes the entity stored under {@code objectId}.
     *
     * @param objectId the entity's {@link Serialized#primaryKey() primary key}
     * @param type the entity type stored under {@code objectId}
     * @param <T> the entity type
     * @throws DatabaseClientException if no entity exists under {@code objectId}, or persistence otherwise fails
     */
    public abstract <T extends Serialized> void delete(@NotNull String objectId, @NotNull Class<T> type) throws DatabaseClientException;

    /**
     * Deletes every entity in {@code objectIds}, concurrently.
     *
     * @param objectIds the entities' {@link Serialized#primaryKey() primary keys}
     * @param type the entity type stored under every id in {@code objectIds}
     * @param <T> the entity type
     * @throws DatabaseClientException if no entity exists under any of {@code objectIds}, or persistence otherwise fails
     */
    public abstract <T extends Serialized> void delete(@NotNull String[] objectIds, @NotNull Class<T> type) throws DatabaseClientException;

    /**
     * Clears every entity of {@code type}, leaving the underlying database
     * section itself intact. Use {@link #deleteSection} to remove the
     * section too.
     *
     * @param type the entity type whose section to clear
     * @param <T> the entity type
     */
    public abstract <T extends Serialized> void clear(@NotNull Class<T> type);

    /**
     * Deletes the database section {@code type} is stored in entirely,
     * section included. A later {@link #register} of an entity of {@code
     * type} lazily recreates it.
     *
     * @param type the entity type whose section to delete
     * @param <T> the entity type
     */
    public abstract <T extends Serialized> void deleteSection(@NotNull Class<T> type);

    /**
     * Re-reads {@code type}'s underlying database section from the database
     * and evicts every cached decrypted entity of {@code type}, so a write
     * made by another process (or another {@code DataFactory} instance in
     * this process) becomes visible. Underlying section implementations
     * mirror their entries in process-local memory and never fall back to
     * the database on read, so without this call a section loaded before
     * another process's write stays stale indefinitely.
     *
     * @param type the entity type whose section to reload
     * @param <T> the entity type
     */
    public abstract <T extends Serialized> void reload(@NotNull Class<T> type);

    /**
     * Releases the configured database's connection(s)/pool. {@link
     * FileFactory} shares this same connection ({@link
     * de.lino.cloud.api.file.StoredFile} is itself persisted through this
     * factory), so shutting this down covers both facets. No further call
     * on this factory is expected to succeed once this returns.
     */
    public abstract void shutdown();

    /** Async counterpart of {@link #register(Serialized)}. */
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

    /** Async counterpart of {@link #register(Serialized[])}. */
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

    /** Async counterpart of {@link #update(Serialized)}. */
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

    /** Async counterpart of {@link #update(Serialized[])}. */
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

    /** Async counterpart of {@link #fetch(String, Class)}. */
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

    /** Async counterpart of {@link #fetch(String[], Class)}. */
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

    /** Async counterpart of {@link #findById(String, Class)}. */
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

    /** Async counterpart of {@link #getEntities(Class)}. */
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

    /** Async counterpart of {@link #delete(String, Class)}. */
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

    /** Async counterpart of {@link #delete(String[], Class)}. */
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

    /** Async counterpart of {@link #clear(Class)}. */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> clearAsync(@NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.clear(type));
    }

    /** Async counterpart of {@link #deleteSection(Class)}. */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> deleteSectionAsync(@NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.deleteSection(type));
    }

    /** Async counterpart of {@link #reload(Class)}. */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> reloadAsync(@NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.reload(type));
    }

    /** Async counterpart of {@link #shutdown()}. */
    @NotNull
    public CompletableFuture<Void> shutdownAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::shutdown);
    }

}
