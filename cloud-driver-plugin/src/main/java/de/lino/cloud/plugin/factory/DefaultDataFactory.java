package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import de.lino.cloud.api.utility.Asserts;
import java.util.Optional;

/**
 * {@link DataFactory} implementation that is a thin pass-through to an
 * {@link EntityDatabaseClient}. {@code *Async} variants need no override -
 * they're inherited from {@link DataFactory}, implemented generically on
 * top of the sync methods here.
 */
public final class DefaultDataFactory extends DataFactory {

    /** The client every operation on this factory delegates to. */
    private final EntityDatabaseClient entityDatabaseClient;

    /**
     * @param entityDatabaseClient the client every operation on this factory delegates to
     * @throws NullPointerException if {@code entityDatabaseClient} is {@code null}
     */
    public DefaultDataFactory(@NotNull final EntityDatabaseClient entityDatabaseClient) {
        this.entityDatabaseClient = Asserts.requireNonNull(entityDatabaseClient, "@DefaultDataFactory: entityDatabaseClient cannot be null");
    }

    /** Delegates to {@link EntityDatabaseClient#store}. */
    @Override
    public <T extends Serialized> void register(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.store(entity);
    }

    /** Delegates to {@link EntityDatabaseClient#storeAll}. */
    @SafeVarargs
    @Override
    public final <T extends Serialized> void register(@NotNull final T... entities) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.storeAll(List.of(entities));
    }

    /** Delegates to {@link EntityDatabaseClient#update}. */
    @Override
    public <T extends Serialized> void update(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.update(entity);
    }

    /** Delegates to {@link EntityDatabaseClient#updateAll}. */
    @SafeVarargs
    @Override
    public final <T extends Serialized> void update(@NotNull final T... entities) throws DatabaseClientException, KeyWrapException {
        this.entityDatabaseClient.updateAll(List.of(entities));
    }

    /** Delegates to {@link EntityDatabaseClient#retrieve}. */
    @NotNull
    @Override
    public <T extends Serialized> T fetch(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.entityDatabaseClient.retrieve(objectId, type);
    }

    /** Delegates to {@link EntityDatabaseClient#retrieveAll}. */
    @NotNull
    @Override
    public <T extends Serialized> List<T> fetch(@NotNull final String[] objectIds, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.entityDatabaseClient.retrieveAll(List.of(objectIds), type);
    }

    /** Delegates to {@link EntityDatabaseClient#findById}. */
    @NotNull
    @Override
    public <T extends Serialized> Optional<T> findById(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.entityDatabaseClient.findById(objectId, type);
    }

    /** Delegates to {@link EntityDatabaseClient#getEntities}. */
    @NotNull
    @Override
    public <T extends Serialized> List<T> getEntities(@NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.entityDatabaseClient.getEntities(type);
    }

    /** Delegates to {@link EntityDatabaseClient#delete(String, Class)}. */
    @Override
    public <T extends Serialized> void delete(@NotNull final String objectId, @NotNull final Class<T> type) throws DatabaseClientException {
        this.entityDatabaseClient.delete(objectId, type);
    }

    /** Delegates to {@link EntityDatabaseClient#deleteAll}. */
    @Override
    public <T extends Serialized> void delete(@NotNull final String[] objectIds, @NotNull final Class<T> type) throws DatabaseClientException {
        this.entityDatabaseClient.deleteAll(List.of(objectIds), type);
    }

    /** Delegates to {@link EntityDatabaseClient#clear}. */
    @Override
    public <T extends Serialized> void clear(@NotNull final Class<T> type) {
        this.entityDatabaseClient.clear(type);
    }

    /** Delegates to {@link EntityDatabaseClient#deleteSection}. */
    @Override
    public <T extends Serialized> void deleteSection(@NotNull final Class<T> type) {
        this.entityDatabaseClient.deleteSection(type);
    }

    /** Delegates to {@link EntityDatabaseClient#reload}. */
    @Override
    public <T extends Serialized> void reload(@NotNull final Class<T> type) {
        this.entityDatabaseClient.reload(type);
    }

    /** Delegates to {@link EntityDatabaseClient#shutdown()}. */
    @Override
    public void shutdown() {
        this.entityDatabaseClient.shutdown();
    }

}
