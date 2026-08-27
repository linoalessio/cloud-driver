package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.event.database.DatabaseWatchEvent;
import de.lino.cloud.api.event.extension.ExtensionRegisterEvent;
import de.lino.cloud.api.event.extension.ExtensionUnregisterEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.plugin.DefaultCloudDriver;
import de.lino.cloud.plugin.extension.ExtensionFolderScanner;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.file.PendingUploadScheduler;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.DatabaseKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Real entry point: boots a Postgres-backed {@link CloudDriver} and starts every subsystem
 * (each on its own thread via a {@code startX()} method), then blocks the real main thread on
 * one shared shutdown latch. Postgres change notification lives in {@code
 * cloud-driver-extensions-watcher}'s {@code CloudWatcherExtension}, started like any other
 * extension via {@link #startExtensionsBootstrapScheduler(String[])}.
 */
public final class CloudBootstrap {

    /** The installed {@link CloudDriver} singleton, read by every {@code startX()} method. */
    private static volatile CloudDriver CLOUD_DRIVER;

    /**
     * Boots the {@link CloudDriver}, starts every subsystem, and blocks the real main thread
     * on one shared shutdown latch until the process is told to stop.
     *
     * @param args service-line arguments, forwarded to every started extension
     * @throws IOException if reading local configuration/security-requirement files fails
     */
    public static void main(String[] args) throws IOException {

        CLOUD_DRIVER = initiateCloudDriver().orElseThrow();

        // Blocks the actual main thread (not a disposable virtual thread - see
        // runTaskInMainSafety's Javadoc) indefinitely, no busy-wait, once every startX()
        // call below has returned. PendingUploadScheduler runs on its own ticker thread and
        // the cloud-driver-extensions-watcher extension's PostgresDatabaseNotification blocks
        // its own dedicated listener thread on its own extension thread - all daemon threads,
        // so none alone keeps the JVM alive - but extension startup (ExtensionFactory#startAll)
        // and event registration (EventFactory#registerEvent) run synchronously, on this very
        // thread, before the shutdown latch below is even constructed. None of the background
        // threads is ever joined - only this one latch, on the true main thread, is. Must be
        // main's final action: runTaskInMainSafety shuts the shared executor down once this
        // returns, so nothing here submits further tasks afterward.
        MultiTaskingFactory.getInstance().runTaskInMainSafety(() -> {

            Constraints.CLOUD_START_TIME_STAMP.set(System.currentTimeMillis());
            System.out.println(Constraints.CLOUD_DRIVER_BANNER);
            loadSecurityRequirements();

            final Runnable[] runnable = new Runnable[] {

                    startTerminalBootstrap()

                    , startPendingUploadScheduler()

                    , startEventScheduler(DatabaseWatchEvent.class, ExtensionRegisterEvent.class, ExtensionUnregisterEvent.class)

                    , startExtensionsBootstrapScheduler(args)

                    , stopTerminal()

            };

            final CountDownLatch shutdownLatch = prepareShutdownLatch(runnable).orElseThrow();
            try {
                shutdownLatch.await();
            } catch (final InterruptedException exception) {
                throw new RuntimeException(exception);
            }

        });

    }

    /**
     * Wires a Postgres {@link DatabaseProvider} and envelope encryption service, then installs
     * the {@link CloudDriver} singleton. Package-private (not {@code private}) rather than
     * {@code public} - it's only meant to be reused by other entry points that live alongside
     * {@code CloudBootstrap} in this same package (e.g. {@code CreateUserCli}, which needs the
     * exact same Postgres/key-service wiring to get a real {@code DataFactory} without pulling
     * in the rest of {@link #main}'s subsystem startup), not as a general-purpose public API.
     *
     * @return the installed {@link CloudDriver}, wrapped in an {@link Optional}
     * @throws IOException if {@code postgres-database.json} cannot be read
     */
    static Optional<CloudDriver> initiateCloudDriver() throws IOException {

        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(false);

        final Credentials credentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).orElseThrow();

        final DatabaseProvider databaseProvider = Asserts.requireNonNull(
                DatabaseRepository.getInstance(), "@CloudBootstrap.main: Database repository must not be null"
        ).registerDatabaseProviderAsync(0, DatabaseType.POSTGRES_SQL, credentials).join();
        final DatabaseSection databaseSection = databaseProvider.createSectionAsync("kek").join();

        final KeyEncryptionService keyEncryptionService = new DatabaseKeyEncryptionService(databaseSection);
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);

        DefaultCloudDriver.setInstance(databaseProvider, envelopeEncryptionService);

        return Optional.of(CloudDriver.getInstance());
    }

    /**
     * Wires one shutdown hook that runs every given action, in registration order, before
     * releasing the returned latch.
     *
     * @param tasks shutdown actions to run, one per started subsystem
     * @return the latch {@code main} awaits until shutdown
     */
    private static Optional<CountDownLatch> prepareShutdownLatch(@NonNull final Runnable... tasks) {

        final List<Runnable> shutdownActions = new ArrayList<>(Arrays.asList(tasks));

        final CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            shutdownActions.forEach(Runnable::run);
            shutdownLatch.countDown();

        }, "cloud-bootstrap-shutdown"));

        return Optional.of(shutdownLatch);
    }

    /**
     * Starts a {@link PendingUploadScheduler} on its own ticker thread.
     *
     * @return the scheduler's shutdown action
     */
    private static Runnable startPendingUploadScheduler() {

        final FileFactory fileFactory = CLOUD_DRIVER.getFactoryContainer().getFileFactory();
        final DataFactory dataFactory = CLOUD_DRIVER.getFactoryContainer().getDataFactory();

        final PendingUploadScheduler pendingUploadScheduler = new PendingUploadScheduler(
                dataFactory, ((DefaultFileFactory) fileFactory).getPendingUploadCache(), CLOUD_DRIVER.getConnectivityChecker()
        );
        pendingUploadScheduler.start(Duration.ofMinutes(1));

        return pendingUploadScheduler::shutdown;
    }

    /**
     * Registers every {@link Extension} found under {@code user.dir} (where {@link
     * CloudBootstrapExtension} lives) and {@link Constraints#EXTENSIONS_PATH}, fires an {@link
     * ExtensionRegisterEvent} per registered extension, then starts all of them via {@link
     * ExtensionFactory#startAllAsync}.
     *
     * @param args arguments forwarded to each extension's {@code onRunning}
     * @return {@link ExtensionFactory#stopAll} as the shutdown action
     */
    private static Runnable startExtensionsBootstrapScheduler(@NonNull final String[] args) {

        final ExtensionFactory extensionFactory = CLOUD_DRIVER.getFactoryContainer().getExtensionFactory();

        ExtensionFolderScanner.scan(Constraints.WORKING_DIRECTORY).forEach(extensionFactory::register);
        ExtensionFolderScanner.scan(Constraints.EXTENSIONS_PATH).forEach(extensionFactory::register);

        CloudDriver.getInstance().getTerminal().emptyLine();
        extensionFactory.startAllAsync(args);
        extensionFactory.getExtensions().forEach(extension -> CLOUD_DRIVER.getFactoryContainer().getEventFactory().dispatch(ExtensionRegisterEvent.class, new JsonDocument().append("extensionName", extension.getExtensionProperties().getExtensionName())));

        return extensionFactory::stopAll;
    }

    /**
     * Registers {@code events} via {@link EventFactory#registerEventAsync}.
     *
     * @param events event classes to register at startup
     * @return a no-op shutdown action
     */
    @SafeVarargs
    private static Runnable startEventScheduler(@NonNull final Class<? extends Event>... events) {
        final EventFactory eventFactory = CLOUD_DRIVER.getFactoryContainer().getEventFactory();
        Arrays.stream(events).forEach(eventFactory::registerEventAsync);
        return () -> {};
    }

    /**
     * Starts the terminal's reading loop.
     *
     * @return a no-op shutdown action; see {@link #stopTerminal()}
     */
    private static Runnable startTerminalBootstrap() {
        CloudDriver.getInstance().getTerminal().start();
        return () -> {};
    }

    /**
     * @return a shutdown action that interrupts the terminal's reading thread
     */
    private static Runnable stopTerminal() {
        return () -> CloudDriver.getInstance().getTerminal().readingThread().interrupt();
    }

    /**
     * Uploads {@code architecture/SECURITY_REQUIREMENTS.md} as a {@link StoredFile} under a
     * fixed id, if not already present.
     *
     * @throws RuntimeException wrapping any I/O, database, or encryption failure
     */
    private static void loadSecurityRequirements() {

        try {

            CLOUD_DRIVER.getFactoryContainer().getFileFactory().findById(Constraints.REQUIREMENTS_UUID.toString()).orElseGet(() -> {

                final File file = Constraints.WORKING_DIRECTORY.resolve(Path.of("..", "architecture", "SECURITY_REQUIREMENTS.md")).toFile();
                try {

                    final StoredFile newStoredFile = new StoredFile(Constraints.REQUIREMENTS_UUID.toString(), file.getName(), Files.readAllBytes(file.toPath()));
                    CLOUD_DRIVER.getFactoryContainer().getFileFactory().uploadAsync(newStoredFile).join();
                    return newStoredFile;

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            });

        } catch (DatabaseClientException | FileIntegrityException | AuthenticationFailedException |
                 KeyWrapException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Smoke-test upload of the repo's own root {@code pom.xml} under a fresh random id.
     *
     * @throws RuntimeException wrapping any I/O, database, or encryption failure
     */
    private static void loadDummyFileUpload() {

        try {

            final StoredFile storedFile = new StoredFile(
                    UUID.randomUUID().toString()
                    , "pom.xml"
                    , Files.readAllBytes(Constraints.WORKING_DIRECTORY.resolve(Path.of("..", "pom.xml")))
            );

            CLOUD_DRIVER.getFactoryContainer().getFileFactory().upload(storedFile);

        } catch (IOException | DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(e);
        }

    }

}
