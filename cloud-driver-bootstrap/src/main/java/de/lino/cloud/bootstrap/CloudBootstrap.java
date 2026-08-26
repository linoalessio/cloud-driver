package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.event.DatabaseWatchEvent;
import de.lino.cloud.api.event.Event;
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
import de.lino.cloud.bootstrap.event.ExtensionRegisterEvent;
import de.lino.cloud.plugin.DefaultCloudAPI;
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
import java.util.stream.Stream;

public final class CloudBootstrap {

    private static volatile CloudAPI CLOUD_API;

    public static void main(String[] args) throws IOException {

        CLOUD_API = initiateCloudAPI().orElseThrow();

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

            System.out.println(Constraints.CLOUD_DRIVER_BANNER);

            loadSecurityRequirements();

            final Runnable[] runnable = new Runnable[] {

                    startPendingUploadScheduler()

                    , startEventScheduler(DatabaseWatchEvent.class, ExtensionRegisterEvent.class)

                    , startExtensionsBootstrapScheduler(args)

            };

            final CountDownLatch shutdownLatch = prepareShutdownLatch(runnable).orElseThrow();
            try {
                shutdownLatch.await();
            } catch (final InterruptedException exception) {
                throw new RuntimeException(exception);
            }

        });

    }

    private static Optional<CloudAPI> initiateCloudAPI() throws IOException {

        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(false);

        final Credentials credentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).orElseThrow();

        final DatabaseProvider databaseProvider = Asserts.requireNonNull(
                DatabaseRepository.getInstance(), "@CloudBootstrap.main: Database repository must not be null"
        ).registerDatabaseProviderAsync(0, DatabaseType.POSTGRES_SQL, credentials).join();
        final DatabaseSection databaseSection = databaseProvider.createSectionAsync("kek").join();

        final KeyEncryptionService keyEncryptionService = new DatabaseKeyEncryptionService(databaseSection);
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);

        DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);

        return Optional.ofNullable(CloudAPI.getInstance());
    }

    /**
     * Starts every background task this process runs and wires one shutdown hook that
     * stops all of them (in registration order) before releasing the returned latch.
     * {@code main} only ever awaits this single latch - add a new concurrent task by
     * starting it in its own method here and appending its shutdown action to {@code
     * shutdownActions}, not by adding another blocking loop to {@code main} itself.
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
     * A periodic worker: its own thread, ticking on a real timer via {@link
     * PendingUploadScheduler#start}, never a busy {@code while(true)} loop. Returns its
     * shutdown action for {@link #prepareShutdownLatch(Runnable...)} to run on JVM shutdown.
     */
    private static Runnable startPendingUploadScheduler() {

        final FileFactory fileFactory = CLOUD_API.getFileFactory();
        final DataFactory dataFactory = CLOUD_API.getDataFactory();

        final PendingUploadScheduler pendingUploadScheduler = new PendingUploadScheduler(
                dataFactory, ((DefaultFileFactory) fileFactory).getPendingUploadCache(), CLOUD_API.getConnectivityChecker()
        );
        pendingUploadScheduler.start(Duration.ofMinutes(1));

        return pendingUploadScheduler::shutdown;
    }

    /**
     * Registers every {@link Extension} found by scanning two folders via {@link
     * ExtensionFolderScanner} - a jar is picked up purely by declaring a concrete {@code
     * Extension} subclass and shipping an {@code extension.json}: first {@code user.dir}
     * (the process's own working directory - where the packaged {@code
     * cloud-driver-bootstrap} jar itself sits when run via {@code java -jar}, which is how
     * {@link CloudBootstrapExtension} gets registered now that nothing here constructs it
     * directly), then {@link Constraints#EXTENSIONS_PATH} (the dedicated folder for
     * third-party extension jars). Fires {@link ExtensionRegisterEvent} once per registered
     * extension - in registration order, since {@code ExtensionFactory#getExtensions()} is
     * backed by a {@code LinkedHashMap} - then starts all of them via {@link
     * ExtensionFactory#startAll}, deliberately <em>not</em> {@link
     * ExtensionFactory#startAllAsync}: this call runs synchronously, blocking the calling
     * thread until every extension has been driven through {@code onLoading()}/{@code
     * onRunning()}. Returns {@link ExtensionFactory#stopAll} as the shutdown action.
     *
     * <p>Only the folder scans happen here - the database/{@code CloudAPI} bootstrap in
     * {@link #initiateCloudAPI()} above cannot itself be pulled into a scanned extension,
     * since {@link ExtensionFactory} (needed to register anything at all) only exists
     * once {@code CloudAPI} does.
     */
    private static Runnable startExtensionsBootstrapScheduler(@NonNull final String[] args) {

        final ExtensionFactory extensionFactory = CLOUD_API.getExtensionFactory();

        ExtensionFolderScanner.scan(Constraints.WORKING_DIRECTORY).forEach(extensionFactory::register);
        ExtensionFolderScanner.scan(Constraints.EXTENSIONS_PATH).forEach(extensionFactory::register);

        extensionFactory.getExtensions().forEach(extension -> CLOUD_API.getEventFactory().callEvent(ExtensionRegisterEvent.class, new JsonDocument().append("extensionName", extension.getExtensionProperties().getExtensionName())));
        extensionFactory.startAll(args);

        return extensionFactory::stopAll;
    }

    /**
     * Registers {@code events} via {@link EventFactory#registerEvent}, synchronously, so
     * every event is live for the whole run, not just during shutdown - construction still
     * happens on {@code DefaultEventFactory}'s own {@code Cache}-backed loader (dispatched
     * onto a virtual thread internally), but this call itself blocks until that completes.
     * Returns a shutdown action that unregisters all of them.
     */
    @SafeVarargs
    private static Runnable startEventScheduler(@NonNull final Class<? extends Event>... events) {
        final EventFactory eventFactory = CLOUD_API.getEventFactory();
        final Stream<Class<? extends Event>> stream = Arrays.stream(events);
        stream.forEach(eventFactory::registerEvent);
        return () -> stream.forEach(eventFactory::unregisterEvent);
    }

    private static void loadSecurityRequirements() {

        try {

            CLOUD_API.getFileFactory().findById(Constraints.REQUIREMENTS_UUID.toString()).orElseGet(() -> {

                final File file = Path.of("SECURITY_REQUIREMENTS.md").toFile();
                try {

                    final StoredFile newStoredFile = new StoredFile(Constraints.REQUIREMENTS_UUID.toString(), file.getName(), Files.readAllBytes(file.toPath()));
                    CLOUD_API.getFileFactory().uploadAsync(newStoredFile).join();
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

}
