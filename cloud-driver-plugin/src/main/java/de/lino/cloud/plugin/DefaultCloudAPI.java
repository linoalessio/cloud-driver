package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.factory.DefaultEventFactory;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.factory.DefaultExtensionFactory;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import org.jetbrains.annotations.NotNull;

import de.lino.cloud.api.utility.Asserts;

import java.io.IOException;

/**
 * {@link CloudAPI} implementation tying a {@link DefaultDataFactory} (backed
 * by an {@link EntityDatabaseClient}), a {@link DefaultFileFactory} (backed
 * by that very same {@link DefaultDataFactory}), a {@link
 * DefaultExtensionFactory}, a {@link ConnectivityChecker}, a {@link
 * DefaultEventFactory}, and a {@link DefaultRestFactory} together as the six
 * facets {@link CloudAPI} exposes - persistence via {@link #getDataFactory()},
 * file upload/download via {@link #getFileFactory()}, extension lifecycle
 * management via {@link #getExtensionFactory()}, outbound-connectivity
 * reporting via {@link #getConnectivityChecker()}, event registration/dispatch
 * via {@link #getEventFactory()}, and REST exposure via {@link
 * #getRestFactory()} (unauthenticated by default - see that method's
 * Javadoc). None of the six facets holds any logic of its own beyond what
 * it delegates to: {@link DefaultDataFactory} passes through to
 * {@link EntityDatabaseClient}; {@link DefaultFileFactory} passes through to
 * {@link DefaultDataFactory} itself (a {@link StoredFile} is itself a {@code
 * Serialized} meta, so no separate persistence path exists for files) and
 * additionally defers an upload into its own {@code PendingUploadCache}
 * rather than failing it outright when {@link #getConnectivityChecker()}
 * reports no connectivity (see {@link DefaultFileFactory}'s class Javadoc);
 * every lifecycle-driving method on {@link ExtensionFactory} is implemented
 * generically on the abstract class itself; every {@code *Async} method on
 * {@link EventFactory} is implemented the same way, generically, on top of
 * {@link DefaultEventFactory}'s abstract primitives; {@link
 * DefaultRestFactory} passes every operation straight through to {@code
 * dataFactory} itself, the same way {@link DefaultFileFactory} does. {@link
 * #setInstance} constructs {@link DefaultFileFactory} with the very same
 * {@link ConnectivityChecker} instance {@link #getConnectivityChecker()}
 * exposes, so every facet agrees on the same answer to "is there a
 * connection right now?".
 *
 * <p>Construct via {@link #setInstance}, which also installs this instance as
 * {@link CloudAPI#getInstance()}.
 */
public final class DefaultCloudAPI extends CloudAPI {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final ExtensionFactory extensionFactory;
    private final ConnectivityChecker connectivityChecker;
    private final EventFactory eventFactory;
    private final RestFactory restFactory;

    /**
     * @param dataFactory the meta-persistence facet, backed by an {@link EntityDatabaseClient}
     * @param fileFactory the file-persistence facet, backed by {@code dataFactory} itself
     * @param extensionFactory the extension-lifecycle facet
     * @param connectivityChecker the outbound-connectivity-reporting facet
     * @param eventFactory the event registration/dispatch facet
     * @param restFactory the REST-exposure facet, backed by {@code dataFactory} itself
     * @throws NullPointerException if any argument is {@code null}
     */
    private DefaultCloudAPI(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory,
                             @NotNull final ExtensionFactory extensionFactory, @NotNull final ConnectivityChecker connectivityChecker,
                             @NotNull final EventFactory eventFactory, @NotNull final RestFactory restFactory) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@DefaultCloudAPI: dataFactory cannot be null");
        this.fileFactory = Asserts.requireNonNull(fileFactory, "@DefaultCloudAPI: fileFactory cannot be null");
        this.extensionFactory = Asserts.requireNonNull(extensionFactory, "@DefaultCloudAPI: extensionFactory cannot be null");
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@DefaultCloudAPI: connectivityChecker cannot be null");
        this.eventFactory = Asserts.requireNonNull(eventFactory, "@DefaultCloudAPI: eventFactory cannot be null");
        this.restFactory = Asserts.requireNonNull(restFactory, "@DefaultCloudAPI: restFactory cannot be null");
    }

    /**
     * Builds a {@link DefaultCloudAPI} backed by {@code databaseProvider} and
     * installs it as the shared {@link CloudAPI#getInstance()}. {@code
     * databaseProvider} should be a concrete {@code database-driver-plugin}
     * {@code DatabaseProvider} (e.g. {@code JsonDatabaseProvider}, {@code
     * H2DatabaseProvider}, ...) - unlike a single {@code DatabaseSection},
     * a provider lets {@link EntityDatabaseClient} create and use one section
     * per meta type on demand, so every meta type (including {@link
     * StoredFile}) does not have to share one section. {@link
     * #getConnectivityChecker()} defaults to an {@link
     * InternetConnectivityChecker} - use the other {@link #setInstance}
     * overload to supply a different one (e.g. a fake for tests).
     */
    @NotNull
    public static CloudAPI setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService
    ) throws IOException {
        return setInstance(databaseProvider, envelopeEncryptionService, new InternetConnectivityChecker());
    }

    /**
     * {@link #setInstance(DatabaseProvider, EnvelopeEncryptionService)}, with an explicit {@link ConnectivityChecker}
     * backing {@link #getConnectivityChecker()} instead of the default {@link InternetConnectivityChecker}.
     */
    @NotNull
    public static synchronized CloudAPI setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService,
            @NotNull final ConnectivityChecker connectivityChecker
    ) throws IOException {
        final DataFactory dataFactory = new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService));
        final FileFactory fileFactory = new DefaultFileFactory(dataFactory, new InMemoryPendingUploadCache(), connectivityChecker);
        final ExtensionFactory extensionFactory = new DefaultExtensionFactory();
        final EventFactory eventFactory = new DefaultEventFactory();
        final RestFactory restFactory = new DefaultRestFactory(dataFactory);
        final DefaultCloudAPI instance = new DefaultCloudAPI(
                dataFactory,
                fileFactory,
                extensionFactory,
                connectivityChecker,
                eventFactory,
                restFactory
        );
        INSTANCE = instance;
        return instance;
    }

    @Override
    public DataFactory getDataFactory() {
        return this.dataFactory;
    }

    @Override
    public FileFactory getFileFactory() {
        return this.fileFactory;
    }

    @Override
    public ExtensionFactory getExtensionFactory() {
        return this.extensionFactory;
    }

    @Override
    public ConnectivityChecker getConnectivityChecker() {
        return this.connectivityChecker;
    }

    @Override
    public EventFactory getEventFactory() {
        return this.eventFactory;
    }

    @Override
    public RestFactory getRestFactory() {
        return this.restFactory;
    }

}
