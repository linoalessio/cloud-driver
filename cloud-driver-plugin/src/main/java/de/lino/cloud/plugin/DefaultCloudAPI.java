package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.event.extension.ExtensionUnregisterEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.prompt.DefaultPromptProvider;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.factory.*;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.json.JsonDocument;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

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
@Getter
public final class DefaultCloudAPI extends CloudAPI {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final ExtensionFactory extensionFactory;
    private final ConnectivityChecker connectivityChecker;
    private final EventFactory eventFactory;
    private final RestFactory restFactory;
    private final Terminal terminal;

    /**
     * Guards {@link #shutdown()} so a second (or concurrent) call is a
     * no-op rather than re-running every step - {@code compareAndSet}
     * makes "have I already started shutting down?" race-free without a
     * {@code synchronized} block.
     */
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    /**
     * @param dataFactory         the meta-persistence facet, backed by an {@link EntityDatabaseClient}
     * @param fileFactory         the file-persistence facet, backed by {@code dataFactory} itself
     * @param extensionFactory    the extension-lifecycle facet
     * @param connectivityChecker the outbound-connectivity-reporting facet
     * @param eventFactory        the event registration/dispatch facet
     * @param restFactory         the REST-exposure facet, backed by {@code dataFactory} itself
     * @throws NullPointerException if any argument is {@code null}
     */
    private DefaultCloudAPI(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory,
                            @NotNull final ExtensionFactory extensionFactory, @NotNull final ConnectivityChecker connectivityChecker,
                            @NotNull final EventFactory eventFactory, @NotNull final RestFactory restFactory, Terminal terminal) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@DefaultCloudAPI: dataFactory cannot be null");
        this.fileFactory = Asserts.requireNonNull(fileFactory, "@DefaultCloudAPI: fileFactory cannot be null");
        this.extensionFactory = Asserts.requireNonNull(extensionFactory, "@DefaultCloudAPI: extensionFactory cannot be null");
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@DefaultCloudAPI: connectivityChecker cannot be null");
        this.eventFactory = Asserts.requireNonNull(eventFactory, "@DefaultCloudAPI: eventFactory cannot be null");
        this.restFactory = Asserts.requireNonNull(restFactory, "@DefaultCloudAPI: restFactory cannot be null");
        this.terminal = terminal;
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

        final Terminal terminal = new Terminal(new DefaultPromptProvider());
        final Logger logger = Logger.getLogger(CloudAPI.class.getSimpleName());
        terminal.attachLogging(logger);

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
                restFactory,
                terminal
        );

        INSTANCE = instance;
        return instance;
    }

    /**
     * Tears down every facet this instance owns, in an order that lets a
     * still-running extension finish cooperatively before the persistence
     * layer it may depend on disappears underneath it:
     *
     * <ol>
     *     <li>{@link #restFactory} - stops accepting new HTTP requests first,
     *     so nothing new starts depending on a facet about to be torn down.</li>
     *     <li>{@link #extensionFactory} - stops every registered extension,
     *     one {@link ExtensionFactory#stop(de.lino.cloud.api.extension.Extension)}
     *     call per extension rather than one {@link ExtensionFactory#stopAll()}
     *     call, so this method's own per-step isolation (see below) applies
     *     per extension, not just per facet.</li>
     *     <li>{@link #eventFactory} - unregisters every registered event, the
     *     same one-call-per-item way.</li>
     *     <li>{@link #dataFactory} - releases the underlying database
     *     connection(s)/pool ({@link DataFactory#shutdown()}); covers {@link
     *     #fileFactory} too, since a {@link StoredFile} is persisted through
     *     this very same factory and {@code FileFactory} owns no separate
     *     connection of its own.</li>
     *     <li>{@link #terminal} - closes the underlying {@code jline}
     *     terminal, if one was constructed.</li>
     * </ol>
     *
     * <p>Every step above runs independently of whether an earlier one
     * failed - a failing step is logged via {@link #getLogger()} rather than
     * thrown, so one broken facet (e.g. a database already unreachable)
     * cannot prevent every other facet from still being torn down. Each
     * currently-registered extension/event is likewise given its own
     * isolated attempt, so one failing extension or event does not stop the
     * rest of its own step from completing. Idempotent - a second call is a
     * no-op.
     */
    @Override
    public void shutdown() {

        if (!this.shutdownStarted.compareAndSet(false, true)) return;

        this.runShutdownStep("RestFactory", this.restFactory::stop);

        for (final Extension extension : List.copyOf(this.extensionFactory.getExtensions())) {
            this.runShutdownStep(
                    "Extension '" + extension.getExtensionProperties().getExtensionName() + "'",
                    () -> {
                        this.eventFactory.callEvent(ExtensionUnregisterEvent.class, new JsonDocument().append("extensionName", extension.getExtensionProperties().getExtensionName()));
                        this.extensionFactory.stop(extension);
                    }
            );
        }

        for (final Event event : List.copyOf(this.eventFactory.getEvents())) {
            this.runShutdownStep(
                    "Event '" + event.getClass().getSimpleName() + "'",
                    () -> this.eventFactory.unregisterEvent(event.getClass())
            );
        }

        this.runShutdownStep("DataFactory", this.dataFactory::shutdown);

        if (this.terminal != null || this.terminal.isActive())
            this.runShutdownStep("Terminal", this.terminal::shutdown);

        System.exit(0);

    }

    /**
     * Runs one shutdown step, logging (rather than propagating) a {@link
     * RuntimeException} so a failure in {@code step} never prevents {@link
     * #shutdown()}'s remaining steps from still being attempted.
     */
    private void runShutdownStep(@NotNull final String stepName, @NotNull final Runnable step) {
        try {
            step.run();
        } catch (final RuntimeException exception) {
            getLogger().log(Level.WARNING, "@DefaultCloudAPI.shutdown: failed to shut down " + stepName, exception);
        }
    }

}
