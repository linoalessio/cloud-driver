package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.plugin.database.EntityDatabaseClient;
import de.lino.cloud.plugin.factory.DefaultExtensionFactory;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import org.jetbrains.annotations.NotNull;

import de.lino.cloud.api.utility.Asserts;

/**
 * {@link CloudAPI} implementation tying a {@link DefaultDataFactory} (backed
 * by an {@link EntityDatabaseClient}) and a {@link DefaultExtensionFactory}
 * together as the two facets {@link CloudAPI} exposes - persistence via
 * {@link #getDataFactory()} and extension lifecycle management via {@link
 * #getExtensionFactory()}. Neither facet holds any logic of its own beyond
 * what it delegates to: {@link DefaultDataFactory} passes through to {@link
 * EntityDatabaseClient}, and every lifecycle-driving method on {@link
 * ExtensionFactory} is implemented generically on the abstract class
 * itself.
 *
 * <p>Construct via {@link #setInstance}, which also installs this instance as
 * {@link CloudAPI#getInstance()}.
 */
public final class DefaultCloudAPI extends CloudAPI {

    private final DataFactory dataFactory;
    private final ExtensionFactory extensionFactory;

    private DefaultCloudAPI(@NotNull final DataFactory dataFactory, @NotNull final ExtensionFactory extensionFactory) {
        this.dataFactory = Asserts.assertNotNull(dataFactory, "@DefaultCloudAPI: dataFactory cannot be null");
        this.extensionFactory = Asserts.assertNotNull(extensionFactory, "@DefaultCloudAPI: extensionFactory cannot be null");
    }

    /**
     * Builds a {@link DefaultCloudAPI} backed by {@code databaseProvider} and
     * installs it as the shared {@link CloudAPI#getInstance()}. {@code
     * databaseProvider} should be a concrete {@code database-driver-plugin}
     * {@code DatabaseProvider} (e.g. {@code JsonDatabaseProvider}, {@code
     * H2DatabaseProvider}, ...) - unlike a single {@code DatabaseSection},
     * a provider lets {@link EntityDatabaseClient} create and use one section
     * per entity type on demand, so every entity type does not have to share
     * one section.
     */
    @NotNull
    public static synchronized CloudAPI setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService
    ) {
        final DefaultCloudAPI instance = new DefaultCloudAPI(
                new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService)),
                new DefaultExtensionFactory()
        );
        INSTANCE = instance;
        return instance;
    }

    @Override
    public DataFactory getDataFactory() {
        return dataFactory;
    }

    @Override
    public ExtensionFactory getExtensionFactory() {
        return extensionFactory;
    }

}
