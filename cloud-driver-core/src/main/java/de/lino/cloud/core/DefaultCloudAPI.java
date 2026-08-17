package de.lino.cloud.core;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.database.DatabaseClientException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.core.database.EntityDatabaseClient;
import de.lino.cloud.core.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * {@link CloudAPI} implementation backed by an {@link EntityDatabaseClient}:
 * {@link #send} and {@link #receive} are a thin pass-through to it, so every
 * envelope-encryption, concurrency, caching, and persistence-error-handling
 * concern lives in {@code EntityDatabaseClient} rather than being duplicated
 * here. {@link #sendAsync}/{@link #receiveAsync} need no override at all -
 * they are inherited directly from {@link CloudAPI}, which implements them
 * generically in terms of the abstract sync methods this class provides.
 *
 * <p>Construct via {@link #initialize}, which also installs this instance as
 * {@link CloudAPI#getInstance()}.
 */
public final class DefaultCloudAPI extends CloudAPI {

    private final EntityDatabaseClient entityDatabaseClient;

    private DefaultCloudAPI(@NotNull final EntityDatabaseClient entityDatabaseClient) {
        this.entityDatabaseClient = Objects.requireNonNull(entityDatabaseClient, "@DefaultCloudAPI: entityDatabaseClient cannot be null");
    }

    /**
     * Builds a {@link DefaultCloudAPI} backed by {@code databaseSection} and
     * installs it as the shared {@link CloudAPI#getInstance()}. {@code
     * databaseSection} is expected to already be registered against a
     * concrete {@code database-driver-plugin} {@code DatabaseProvider} (e.g.
     * {@code JsonDatabaseProvider}, {@code H2DatabaseProvider}, ...).
     */
    @NotNull
    public static synchronized CloudAPI initialize(@NotNull final DatabaseSection databaseSection,
                                                     @NotNull final EnvelopeEncryptionService envelopeEncryptionService) {
        final DefaultCloudAPI instance = new DefaultCloudAPI(new EntityDatabaseClient(databaseSection, envelopeEncryptionService));
        INSTANCE = instance;
        return instance;
    }

    @Override
    public <T extends Serialized> void send(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        entityDatabaseClient.store(entity);
    }

    @SafeVarargs
    @Override
    public final <T extends Serialized> void send(@NotNull final T... entities) throws DatabaseClientException, KeyWrapException {
        entityDatabaseClient.storeAll(List.of(entities));
    }

    @Override
    public <T extends Serialized> T receive(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return entityDatabaseClient.retrieve(objectId, type);
    }

    @Override
    public <T extends Serialized> List<T> receive(@NotNull final String[] objectIds, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return entityDatabaseClient.retrieveAll(List.of(objectIds), type);
    }
}
