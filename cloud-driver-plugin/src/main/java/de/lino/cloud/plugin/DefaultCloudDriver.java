package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.event.extension.ExtensionUnregisterEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.IFactoryContainer;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.prompt.DefaultPromptProvider;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.factory.*;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.json.JsonDocument;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
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

    private final IFactoryContainer factoryContainer;
    private final ConnectivityChecker connectivityChecker;
    private final Terminal terminal;

    /** Guards {@link #shutdown()} so a second (or concurrent) call is a no-op. */
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    /**
     * @param connectivityChecker the outbound-connectivity-reporting facet
     * @throws NullPointerException if any argument is {@code null}
     */
    private DefaultCloudDriver(@NotNull final ConnectivityChecker connectivityChecker, @NonNull final Terminal terminal, @NonNull final IFactoryContainer factoryContainer) {
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@DefaultCloudDriver: connectivityChecker cannot be null");
        this.factoryContainer = factoryContainer;
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
     * @throws IOException if the extensions folder cannot be created
     */
    @NotNull
    public static CloudDriver setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService
    ) throws IOException {
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

        final IFactoryContainer container = new FactoryContainer(databaseProvider, envelopeEncryptionService, connectivityChecker);

        final DefaultCloudDriver instance = new DefaultCloudDriver(
                connectivityChecker,
                terminal,
                container
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
     * down. Idempotent - a second call is a no-op.
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
