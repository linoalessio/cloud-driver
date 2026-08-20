package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.factory.DefaultEventFactory;
import de.lino.cloud.plugin.file.pending.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.factory.DefaultExtensionFactory;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.terminal.DefaultTerminal;
import de.lino.database.database.DatabaseProvider;
import org.jetbrains.annotations.NotNull;

import de.lino.cloud.api.utility.Asserts;

/**
 * {@link CloudAPI} implementation tying a {@link DefaultDataFactory} (backed
 * by an {@link EntityDatabaseClient}), a {@link DefaultFileFactory} (backed
 * by that very same {@link DefaultDataFactory}), a {@link
 * DefaultExtensionFactory}, a {@link ConnectivityChecker}, a {@link
 * DefaultEventFactory}, and a {@link DefaultTerminal} together as the six
 * facets {@link CloudAPI} exposes - persistence via {@link #getDataFactory()},
 * file upload/download via {@link #getFileFactory()}, extension lifecycle
 * management via {@link #getExtensionFactory()}, outbound-connectivity
 * reporting via {@link #getConnectivityChecker()}, event
 * registration/dispatch via {@link #getEventFactory()}, and an interactive
 * admin console via {@link #getTerminal()}. None of the six facets holds any
 * logic of its own beyond what it delegates to: {@link DefaultDataFactory}
 * passes through to {@link EntityDatabaseClient}; {@link DefaultFileFactory}
 * passes through to {@link DefaultDataFactory} itself (a {@link StoredFile}
 * is itself a {@code Serialized} meta, so no separate persistence path
 * exists for files) and additionally defers an upload into its own {@code
 * PendingUploadCache} rather than failing it outright when {@link
 * #getConnectivityChecker()} reports no connectivity (see {@link
 * DefaultFileFactory}'s class Javadoc); every lifecycle-driving method on
 * {@link ExtensionFactory} is implemented generically on the abstract class
 * itself; every {@code *Async} method on {@link EventFactory} is implemented
 * the same way, generically, on top of {@link DefaultEventFactory}'s
 * abstract primitives; {@link DefaultTerminal} only supplies raw stdin/stdout
 * I/O - every command's dispatch logic is implemented generically on {@code
 * Terminal} itself, in terms of {@code extensionFactory}/{@code
 * eventFactory}/{@code fileFactory}/{@code connectivityChecker}. {@link
 * #setInstance} constructs {@link DefaultFileFactory} and {@link
 * DefaultTerminal} with the very same {@link ConnectivityChecker} instance
 * {@link #getConnectivityChecker()} exposes, so every facet agrees on the
 * same answer to "is there a connection right now?".
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
    private final Terminal terminal;

    /**
     * @param dataFactory the meta-persistence facet, backed by an {@link EntityDatabaseClient}
     * @param fileFactory the file-persistence facet, backed by {@code dataFactory} itself
     * @param extensionFactory the extension-lifecycle facet
     * @param connectivityChecker the outbound-connectivity-reporting facet
     * @param eventFactory the event registration/dispatch facet
     * @param terminal the interactive admin console facet
     * @throws NullPointerException if any argument is {@code null}
     */
    private DefaultCloudAPI(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory,
                             @NotNull final ExtensionFactory extensionFactory, @NotNull final ConnectivityChecker connectivityChecker,
                             @NotNull final EventFactory eventFactory, @NotNull final Terminal terminal) {
        this.dataFactory = Asserts.assertNotNull(dataFactory, "@DefaultCloudAPI: dataFactory cannot be null");
        this.fileFactory = Asserts.assertNotNull(fileFactory, "@DefaultCloudAPI: fileFactory cannot be null");
        this.extensionFactory = Asserts.assertNotNull(extensionFactory, "@DefaultCloudAPI: extensionFactory cannot be null");
        this.connectivityChecker = Asserts.assertNotNull(connectivityChecker, "@DefaultCloudAPI: connectivityChecker cannot be null");
        this.eventFactory = Asserts.assertNotNull(eventFactory, "@DefaultCloudAPI: eventFactory cannot be null");
        this.terminal = Asserts.assertNotNull(terminal, "@DefaultCloudAPI: terminal cannot be null");
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
    ) {
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
    ) {
        final DataFactory dataFactory = new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService));
        final FileFactory fileFactory = new DefaultFileFactory(dataFactory, new InMemoryPendingUploadCache(), connectivityChecker);
        final ExtensionFactory extensionFactory = new DefaultExtensionFactory();
        final EventFactory eventFactory = new DefaultEventFactory();
        final DefaultCloudAPI instance = new DefaultCloudAPI(
                dataFactory,
                fileFactory,
                extensionFactory,
                connectivityChecker,
                eventFactory,
                new DefaultTerminal()
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
    public Terminal getTerminal() {
        return this.terminal;
    }

}
