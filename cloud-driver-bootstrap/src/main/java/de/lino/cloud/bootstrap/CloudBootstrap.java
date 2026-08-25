package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.plugin.DefaultCloudAPI;
import de.lino.cloud.plugin.extension.ExtensionFolderScanner;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.file.PendingUploadScheduler;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.DatabaseKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;
import lombok.NonNull;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public final class CloudBootstrap {

    private static volatile CloudAPI CLOUD_API;

    public static void main(String[] args) throws IOException {

        CLOUD_API = initiateCloudAPI().orElseThrow();

        // Blocks the actual main thread (not a disposable virtual thread - see
        // runTaskInMainSafety's Javadoc) indefinitely, no busy-wait. Every background task
        // runs on its own thread (PendingUploadScheduler's own ticker thread; the virtual
        // threads extension/event startup dispatch onto), and every one of those threads is
        // a daemon thread, so none of them alone keeps the JVM alive, and none of them is
        // ever joined - only this one latch, on the true main thread, is. Must be main's
        // final action: runTaskInMainSafety shuts the shared executor down once this
        // returns, so nothing here submits further tasks afterward.
        MultiTaskingFactory.getInstance().runTaskInMainSafety(() -> {

            final Runnable[] runnable = new Runnable[] {
                    startPendingUploadScheduler()
                    , startExtensionsBootstrapScheduler(args)
                    , startEventScheduler()
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

        final DatabaseProvider databaseProvider = Asserts.assertNotNull(
                DatabaseRepository.getInstance(), "@CloudBootstrap.main: Database repository must not be null"
        ).registerDatabaseProviderAsync(0, DatabaseType.POSTGRES_SQL, credentials).join();

        final KeyEncryptionService keyEncryptionService = new DatabaseKeyEncryptionService(databaseProvider.createSection("kek"));
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
     * Registers {@code extensions} plus every {@link Extension} found by scanning {@link
     * Constraints#EXTENSIONS_PATH} via {@link ExtensionFolderScanner} - a jar dropped
     * into that folder is picked up here purely by declaring a concrete {@code
     * Extension} subclass and shipping an {@code extension.json}, the same way any
     * extension explicitly passed in is - then starts all of them via {@link
     * ExtensionFactory#startAllAsync}, dispatched onto {@link MultiTaskingFactory}'s
     * shared virtual-thread executor, its own thread rather than the caller's, and never
     * joined here. Returns {@link ExtensionFactory#stopAll} as the shutdown action.
     *
     * <p>Only the folder scan happens here - the database/{@code CloudAPI} bootstrap in
     * {@link #initiateCloudAPI()} above cannot itself be pulled into a scanned extension,
     * since {@link ExtensionFactory} (needed to register anything at all) only exists
     * once {@code CloudAPI} does.
     */
    private static Runnable startExtensionsBootstrapScheduler(@NonNull final String[] args, @NonNull final Extension... extensions) {
        final ExtensionFactory extensionFactory = CLOUD_API.getExtensionFactory();
        Arrays.stream(extensions).forEach(extensionFactory::register);
        ExtensionFolderScanner.scan(Constraints.EXTENSIONS_PATH).forEach(extensionFactory::register);
        extensionFactory.startAllAsync(args);
        return extensionFactory::stopAll;
    }

    /**
     * Registers {@code events} via {@link EventFactory#registerEventAsync} - each
     * dispatched onto its own virtual thread, never joined here - so every event is live
     * for the whole run, not just during shutdown. Returns a shutdown action that
     * unregisters all of them.
     */
    @SafeVarargs
    private static Runnable startEventScheduler(@NonNull final Class<? extends Event>... events) {
        final EventFactory eventFactory = CLOUD_API.getEventFactory();
        Arrays.stream(events).forEach(eventFactory::registerEventAsync);
        return () -> Arrays.stream(events).forEach(eventFactory::unregisterEvent);
    }

}
