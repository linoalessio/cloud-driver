package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.factory.DefaultExtensionFactory;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import org.jetbrains.annotations.NotNull;

import de.lino.cloud.api.utility.Asserts;

/**
 * {@link CloudAPI} implementation tying a {@link DefaultDataFactory} (backed
 * by an {@link EntityDatabaseClient}), a {@link DefaultFileFactory} (backed
 * by that very same {@link DefaultDataFactory}), and a {@link
 * DefaultExtensionFactory} together as the three facets {@link CloudAPI}
 * exposes - persistence via {@link #getDataFactory()}, file upload/download
 * via {@link #getFileFactory()}, and extension lifecycle management via
 * {@link #getExtensionFactory()}. None of the three facets holds any logic
 * of its own beyond what it delegates to: {@link DefaultDataFactory} passes
 * through to {@link EntityDatabaseClient}; {@link DefaultFileFactory} passes
 * through to {@link DefaultDataFactory} itself (a {@link StoredFile} is
 * itself a {@code Serialized} entity, so no separate persistence path
 * exists for files); and every lifecycle-driving method on {@link
 * ExtensionFactory} is implemented generically on the abstract class
 * itself.
 *
 * <p>Construct via {@link #setInstance}, which also installs this instance as
 * {@link CloudAPI#getInstance()}.
 */
public final class DefaultCloudAPI extends CloudAPI {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final ExtensionFactory extensionFactory;

    private DefaultCloudAPI(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory,
                             @NotNull final ExtensionFactory extensionFactory) {
        this.dataFactory = Asserts.assertNotNull(dataFactory, "@DefaultCloudAPI: dataFactory cannot be null");
        this.fileFactory = Asserts.assertNotNull(fileFactory, "@DefaultCloudAPI: fileFactory cannot be null");
        this.extensionFactory = Asserts.assertNotNull(extensionFactory, "@DefaultCloudAPI: extensionFactory cannot be null");
    }

    /**
     * Builds a {@link DefaultCloudAPI} backed by {@code databaseProvider} and
     * installs it as the shared {@link CloudAPI#getInstance()}. {@code
     * databaseProvider} should be a concrete {@code database-driver-plugin}
     * {@code DatabaseProvider} (e.g. {@code JsonDatabaseProvider}, {@code
     * H2DatabaseProvider}, ...) - unlike a single {@code DatabaseSection},
     * a provider lets {@link EntityDatabaseClient} create and use one section
     * per entity type on demand, so every entity type (including {@link
     * StoredFile}) does not have to share one section.
     */
    @NotNull
    public static synchronized CloudAPI setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService
    ) {
        final DataFactory dataFactory = new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService));
        final DefaultCloudAPI instance = new DefaultCloudAPI(
                dataFactory,
                new DefaultFileFactory(dataFactory),
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
    public FileFactory getFileFactory() {
        return fileFactory;
    }

    @Override
    public ExtensionFactory getExtensionFactory() {
        return extensionFactory;
    }

}
