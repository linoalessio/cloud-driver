package de.lino.cloud.api;

import de.lino.cloud.api.database.DatabaseClientException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.task.MultiTaskingFactory;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public abstract class CloudAPI {

    protected static volatile CloudAPI INSTANCE;

    public synchronized static CloudAPI getInstance()  {
        return INSTANCE;
    }

    /**
     * Encrypts {@code entity} and stores it in the configured database, per
     * the security requirements (section 9, DATA AT REST): envelope-encrypted
     * with AES-256-GCM before it is written, so the database only ever holds
     * ciphertext.
     *
     * @param entity the domain entity to store
     * @param <T> the entity type
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the entity's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void send(@NotNull T entity) throws DatabaseClientException, KeyWrapException;

    /**
     * Encrypts and stores every entity in {@code entities}, each under its
     * own {@link Serialized#primaryKey() primary key}, the same way {@link
     * #send(Serialized)} stores a single entity. Implementations dispatch
     * entities concurrently rather than one at a time - see the concrete
     * implementation's Javadoc for the exact failure semantics of a batch
     * with more than one failing entity.
     *
     * @param entities the domain entities to store
     * @param <T> the entity type
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any entity's data-encryption key cannot be wrapped by the KMS/HSM
     */
    public abstract <T extends Serialized> void send(@NotNull T... entities) throws DatabaseClientException, KeyWrapException;

    /**
     * Retrieves the entity stored under {@code objectId} from the database
     * and decrypts it back into an instance of {@code type}, verifying its
     * authentication tag before returning any plaintext.
     *
     * @param objectId the entity's {@link Serialized#primaryKey() primary key}
     * @param type the concrete entity type to decrypt into
     * @param <T> the entity type
     * @return the decrypted entity
     * @throws DatabaseClientException if the persistence operation fails
     * @throws KeyWrapException if the entity's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if the retrieved payload fails authentication
     */
    public abstract <T extends Serialized> T receive(@NotNull String objectId, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Retrieves every entity stored under {@code objectIds} from the
     * database and decrypts each one back into an instance of {@code type},
     * in the same order as {@code objectIds}, the same way {@link
     * #receive(String, Class)} retrieves a single entity.
     *
     * @param objectIds the entities' {@link Serialized#primaryKey() primary keys}
     * @param type the concrete entity type to decrypt into
     * @param <T> the entity type
     * @return the decrypted entities, in the same order as {@code objectIds}
     * @throws DatabaseClientException if any persistence operation fails
     * @throws KeyWrapException if any entity's data-encryption key cannot be unwrapped by the KMS/HSM
     * @throws AuthenticationFailedException if any retrieved payload fails authentication
     */
    public abstract <T extends Serialized> List<T> receive(@NotNull String[] objectIds, @NotNull Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException;

    /**
     * Async counterpart of {@link #send(Serialized)}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor so the calling
     * thread never blocks on database or KMS I/O. On failure, the returned
     * future completes exceptionally with a {@link CompletionException}
     * wrapping the checked exception {@link #send(Serialized)} would
     * otherwise have thrown.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> sendAsync(@NotNull final T entity) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                send(entity);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #send(Serialized[])}.
     */
    @NotNull
    @SafeVarargs
    public final <T extends Serialized> CompletableFuture<Void> sendAsync(@NotNull final T... entities) {
        return MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                send(entities);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #receive(String, Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<T> receiveAsync(@NotNull final String objectId, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return receive(objectId, type);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Async counterpart of {@link #receive(String[], Class)}.
     */
    @NotNull
    public <T extends Serialized> CompletableFuture<List<T>> receiveAsync(@NotNull final String[] objectIds, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                return receive(objectIds, type);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                throw new CompletionException(e);
            }
        });
    }

}
