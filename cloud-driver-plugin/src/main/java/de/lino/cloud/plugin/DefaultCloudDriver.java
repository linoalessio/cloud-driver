package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.event.extension.ExtensionUnregisterEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.container.IFactoryContainer;
import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.security.rest.ApiKey;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.prompt.DefaultPromptProvider;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import de.lino.cloud.auth.pending.PendingPasswordReset;
import de.lino.cloud.auth.pending.PendingRegistration;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.factory.*;
import de.lino.cloud.plugin.factory.container.FactoryContainer;
import de.lino.cloud.plugin.factory.container.ServiceContainer;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.json.JsonDocument;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link CloudDriver} implementation wiring together a {@link
 * DefaultDataFactory}, {@link DefaultFileFactory}, {@link
 * DefaultExtensionFactory}, a {@link ConnectivityChecker}, a {@link
 * DefaultEventFactory}, a {@link DefaultRestFactory}, and a {@link Terminal}.
 * Construct via {@link #setInstance}, which also installs this instance as
 * {@link CloudDriver#getInstance()}.
 */
@Getter
public final class DefaultCloudDriver extends CloudDriver {

    /** The container bundling the data/file/extension/event/REST facets this instance was built with. */
    private final IFactoryContainer factoryContainer;
    /** The container bundling higher-level services built on top of {@link #factoryContainer}'s raw facets. */
    private final IServiceContainer serviceContainer;

    /** The outbound-connectivity-reporting facet, shared with {@link #factoryContainer}'s {@code FileFactory}. */
    private final ConnectivityChecker connectivityChecker;
    /** The interactive terminal facet, constructed and log-attached in {@link #setInstance}. */
    private final Terminal terminal;

    /** Guards {@link #shutdown()} so a second (or concurrent) call is a no-op. */
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    /**
     * Assembles a new instance from its already-constructed facets. Only called
     * from {@link #setInstance(DatabaseProvider, EnvelopeEncryptionService, ConnectivityChecker)}.
     *
     * @param connectivityChecker the outbound-connectivity-reporting facet
     * @param terminal the interactive terminal facet
     * @param factoryContainer the container bundling the data/file/extension/event/REST facets
     * @param serviceContainer the container bundling higher-level services
     * @throws NullPointerException if any argument is {@code null}
     */
    private DefaultCloudDriver(@NotNull final ConnectivityChecker connectivityChecker, @NonNull final Terminal terminal, @NonNull final IFactoryContainer factoryContainer, @NonNull final IServiceContainer serviceContainer) {
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@DefaultCloudDriver: connectivityChecker cannot be null");
        this.factoryContainer = factoryContainer;
        this.serviceContainer = serviceContainer;
        this.terminal = terminal;
    }

    /**
     * Builds a {@link DefaultCloudDriver} backed by {@code databaseProvider} and installs it
     * as the shared {@link CloudDriver#getInstance()}. Defaults {@link #getConnectivityChecker()}
     * to a fresh {@link InternetConnectivityChecker}.
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence
     * @return the installed instance
     */
    @NotNull
    public static CloudDriver setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService
    ) {
        return setInstance(databaseProvider, envelopeEncryptionService, new InternetConnectivityChecker());
    }

    /**
     * Same as {@link #setInstance(DatabaseProvider, EnvelopeEncryptionService)}, but with an
     * explicit {@link ConnectivityChecker} backing {@link #getConnectivityChecker()}.
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence
     * @param connectivityChecker backs {@link #getConnectivityChecker()} and {@link DefaultFileFactory}
     * @return the installed instance
     */
    @NotNull
    public static synchronized CloudDriver setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService,
            @NotNull final ConnectivityChecker connectivityChecker
    ) {

        final Terminal terminal = new Terminal(new DefaultPromptProvider());
        final Logger logger = Logger.getLogger(CloudDriver.class.getSimpleName());
        terminal.attachLogging(logger);

        final IFactoryContainer factoryContainer = new FactoryContainer(databaseProvider, envelopeEncryptionService, connectivityChecker);
        final IServiceContainer serviceContainer = new ServiceContainer();

        final DefaultCloudDriver instance = new DefaultCloudDriver(
                connectivityChecker,
                terminal,
                factoryContainer,
                serviceContainer
        );

        INSTANCE = instance;
        return instance;
    }

    /**
     * Tears down every facet this instance owns: stops the REST server,
     * stops every registered extension, unregisters every registered event,
     * shuts down the {@link DataFactory} (also covers {@link FileFactory},
     * which shares its connection), then shuts down the {@link #terminal} if
     * one was constructed. Each step - and each extension/event within its
     * step - is attempted independently and a failure is logged rather than
     * thrown, so one broken facet cannot block the rest from being torn
     * down. Idempotent - a second call is a no-op. Finally calls {@link
     * System#exit(int)} with status {@code 0}, terminating the JVM.
     */
    @Override
    public void shutdown() {

        if (!this.shutdownStarted.compareAndSet(false, true)) return;

        this.runShutdownStep("RestFactory", this.getFactoryContainer().getRestFactory()::stop);

        for (final Extension extension : List.copyOf(this.getFactoryContainer().getExtensionFactory().getExtensions())) {
            this.runShutdownStep(
                    "Extension '" + extension.getExtensionProperties().getExtensionName() + "'",
                    () -> {
                        this.factoryContainer.getEventFactory().dispatch(ExtensionUnregisterEvent.class, new JsonDocument().append("extensionName", extension.getExtensionProperties().getExtensionName()));
                        this.getFactoryContainer().getExtensionFactory().stop(extension);
                    }
            );
        }

        for (final Event event : List.copyOf(this.factoryContainer.getEventFactory().getEvents())) {
            this.runShutdownStep(
                    "Event '" + event.getClass().getSimpleName() + "'",
                    () -> this.factoryContainer.getEventFactory().unregisterEvent(event.getClass())
            );
        }

        this.runShutdownStep("DataFactory", this.factoryContainer.getDataFactory()::shutdown);

        if (this.terminal != null && this.terminal.isActive())
            this.runShutdownStep("Terminal", this.terminal::shutdown);

        System.exit(0);

    }

    /**
     * Wipes every {@link de.lino.database.database.entity.Serialized} entity section this
     * repository defines - every {@link AuthUser}, {@link CloudUser}, {@link Folder}, {@link
     * StoredFile}, {@link StoredFileOwnership}, {@link PendingRegistration}, {@link
     * PendingPasswordReset}, and {@link ApiKey} row, across the whole database. Called by the
     * terminal package's {@code HardResetCommand} (aliased {@code reset}) after its own two-step
     * confirmation - there is no undo.
     *
     * <p>Deliberately does <b>not</b> touch key-encryption-key (KEK) material: KEK rotation state
     * lives in its own raw {@code "kek"} {@link de.lino.database.database.DatabaseSection}
     * (constructed directly by {@code DatabaseKeyEncryptionService}, given that section by {@code
     * CloudBootstrap.initiateCloudDriver()} - see {@code CloudBootstrap.java}), not through a
     * {@link Serialized} entity class reachable via {@link DataFactory#deleteSection}, so it isn't
     * reachable from here without new plumbing (e.g. exposing the raw {@link DatabaseProvider}
     * through {@link IFactoryContainer}). Once every entity above is gone there is nothing left
     * for that KEK to protect anyway, so leaving it in place is safe, not merely an oversight -
     * but it does mean a KEK rotated before this reset stays around after it. An earlier revision
     * of this method tried to reach it via {@code dataFactory.deleteSectionAsync(
     * DatabaseKeyEncryptionService.class)} - that never compiled ({@code
     * DatabaseKeyEncryptionService} implements {@link
     * de.lino.cloud.api.security.keys.KeyEncryptionService}, not {@link Serialized}, so it can't
     * satisfy {@link DataFactory#deleteSectionAsync}'s {@code <T extends Serialized>} bound) and,
     * even had it compiled, would have deleted the wrong section - {@code deleteSection} derives
     * a section name from {@code type.getSimpleName()} ({@code "DatabaseKeyEncryptionService"}),
     * not the {@code "kek"} section the KEK material actually lives in.
     *
     * <p>Every deletion runs concurrently (each {@link DataFactory#deleteSectionAsync} call
     * dispatches its own task); unlike the previous revision, this method now waits for every one
     * to finish (via {@link CompletableFuture#allOf}) before returning, and propagates the first
     * failure encountered once every deletion has been attempted - the same "attempt everything
     * concurrently, don't report until all are attempted" convention {@code
     * EntityDatabaseClient}'s own batch operations use - rather than firing eight requests and
     * discarding every result, which previously left both the caller and {@code HardResetCommand}
     * with no way to know whether the reset actually completed or silently failed partway through.
     */
    @Override
    public void reset() {

        final DataFactory dataFactory = this.getFactoryContainer().getDataFactory();

        final List<CompletableFuture<Void>> deletions = List.of(
                dataFactory.deleteSectionAsync(AuthUser.class),
                dataFactory.deleteSectionAsync(CloudUser.class),
                dataFactory.deleteSectionAsync(Folder.class),
                dataFactory.deleteSectionAsync(PendingRegistration.class),
                dataFactory.deleteSectionAsync(PendingPasswordReset.class),
                dataFactory.deleteSectionAsync(StoredFile.class),
                dataFactory.deleteSectionAsync(StoredFileOwnership.class),
                dataFactory.deleteSectionAsync(ApiKey.class)
        );

        CompletableFuture.allOf(deletions.toArray(new CompletableFuture[0])).join();

    }

    /**
     * Runs one shutdown step, logging rather than propagating a {@link RuntimeException}
     * so a failure never blocks {@link #shutdown()}'s remaining steps.
     *
     * @param stepName label used in the log message on failure
     * @param step the shutdown action to run
     */
    private void runShutdownStep(@NotNull final String stepName, @NotNull final Runnable step) {
        try {
            step.run();
        } catch (final RuntimeException exception) {
            getLogger().log(Level.WARNING, "@DefaultCloudDriver.shutdown: failed to shut down " + stepName, exception);
        }
    }

}
