package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.plugin.database.EntityDatabaseClient;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link DataFactory} implementation backed by an {@link EntityDatabaseClient}:
 * {@link #register}, {@link #update}, {@link #fetch}, {@link #findById} and
 * {@link #delete} are a thin pass-through to it, so every
 * envelope-encryption, concurrency, caching, and persistence-error-handling
 * concern lives in {@code EntityDatabaseClient} rather than being duplicated
 * here. {@code *Async} variants need no override at all - they are inherited
 * directly from {@link DataFactory}, which implements them generically in
 * terms of the abstract sync methods this class provides.
 */
public final class DefaultDataFactory extends DataFactory {

    private final EntityDatabaseClient entityDatabaseClient;

    public DefaultDataFactory(@NotNull final EntityDatabaseClient entityDatabaseClient) {
        this.entityDatabaseClient = Objects.requireNonNull(entityDatabaseClient, "@DefaultDataFactory: entityDatabaseClient cannot be null");
    }

    @Override
    public <T extends Serialized> void register(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.store(entity);
    }

    @SafeVarargs
    @Override
    public final <T extends Serialized> void register(@NotNull final T... entities) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.storeAll(List.of(entities));
    }

    @Override
    public <T extends Serialized> void update(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.update(entity);
    }

    @SafeVarargs
    @Override
    public final <T extends Serialized> void update(@NotNull final T... entities) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.updateAll(List.of(entities));
    }

    @NotNull
    @Override
    public <T extends Serialized> T fetch(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.entityDatabaseClient.retrieve(objectId, type);
    }

    @NotNull
    @Override
    public <T extends Serialized> List<T> fetch(@NotNull final String[] objectIds, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.entityDatabaseClient.retrieveAll(List.of(objectIds), type);
    }

    @NotNull
    @Override
    public <T extends Serialized> Optional<T> findById(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.entityDatabaseClient.findById(objectId, type);
    }

    @Override
    public <T extends Serialized> void delete(@NotNull final String objectId, @NotNull final Class<T> type) throws DatabaseClientException {
        this.entityDatabaseClient.delete(objectId, type);
    }

    @Override
    public <T extends Serialized> void delete(@NotNull final String[] objectIds, @NotNull final Class<T> type) throws DatabaseClientException {
        this.entityDatabaseClient.deleteAll(List.of(objectIds), type);
    }

}
