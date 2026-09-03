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
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import de.lino.cloud.plugin.DefaultCloudDriver;
import de.lino.cloud.plugin.extension.ExtensionFolderScanner;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.file.PendingUploadScheduler;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.AwsKmsKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
            initDefaultFile();

            final Runnable[] runnable = new Runnable[] {

                    startTerminalBootstrap()

                    , startPendingUploadScheduler()

                    , startEventScheduler(DatabaseWatchEvent.class, ExtensionRegisterEvent.class, ExtensionUnregisterEvent.class)

                    , startExtensionsBootstrapScheduler(args)

                    , warmFileListingCache()

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
     * {@code public} - only {@link #main} in this same class calls it today, but a
     * package-private visibility leaves room for another entry point living alongside {@code
     * CloudBootstrap} in this same package to reuse the exact same Postgres/key-service wiring
     * without pulling in the rest of {@link #main}'s subsystem startup; not a general-purpose
     * public API.
     *
     * @return the installed {@link CloudDriver}, wrapped in an {@link Optional}
     */
    static Optional<CloudDriver> initiateCloudDriver() {

        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(false);

        final Credentials credentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).orElseThrow();

        final DatabaseProvider databaseProvider = Asserts.requireNonNull(
                DatabaseRepository.getInstance(), "@CloudBootstrap.main: Database repository must not be null"
        ).registerDatabaseProviderAsync(0, DatabaseType.POSTGRES_SQL, credentials).join();

        final JsonDocument configuration = JsonDocument.load(Constraints.CONFIGURATION_PATH.resolve("configuration.json"));
        final String encryptionKeyAlias = configuration.getString("aws-kms-key-id");
        final Region region = Region.of(configuration.getString("aws-kms-region"));
        final KeyEncryptionService keyEncryptionService = new AwsKmsKeyEncryptionService(region, encryptionKeyAlias);

        // TODO: remove --> final KeyEncryptionService keyEncryptionService = new DatabaseKeyEncryptionService(databaseSection);
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);

        DefaultCloudDriver.setInstance(databaseProvider, envelopeEncryptionService, ALWAYS_AVAILABLE_CONNECTIVITY_CHECKER);

        return Optional.of(CloudDriver.getInstance());
    }

    /**
     * This deployment's own {@link ConnectivityChecker}, passed to {@link DefaultCloudDriver#setInstance}
     * instead of the default {@code InternetConnectivityChecker} - always reports available.
     *
     * <p>{@code InternetConnectivityChecker} probes outbound DNS resolvers (Cloudflare/Google, port
     * 53) to answer "is there a network connection at all" - the right question for a genuinely
     * mobile/offline-capable client (see {@code CloudBootstrapSample}, wired against a local
     * JSON-file database with no real network dependency at all), but the wrong one for this
     * process: {@code CloudBootstrap} always runs on the same machine as the Postgres instance it
     * persists to (see {@code postgres-database.json}'s own {@code address}), so "can I reach two
     * arbitrary public IPs" has nothing to do with whether a local database write can succeed.
     *
     * <p><b>Fixed a real bug (2026-09-01):</b> {@link DefaultFileFactory#upload} calls {@link
     * ConnectivityChecker#isAvailable()} - a fresh, up-to-4-second (two 2-second probes, tried in
     * order) outbound socket-connect check - on <em>every single upload</em>, synchronously,
     * before persisting. Under a burst of many concurrent uploads (e.g. the desktop app's
     * "double-click a ZIP archive to extract it" feature, which can fire off dozens of {@code
     * POST /files} calls at once), several of these probes racing for outbound sockets/bandwidth
     * on a small VPS spuriously timed out, making {@code isAvailable()} report "offline" even
     * though the server's own database connection never wavered. When that happened, {@link
     * DefaultFileFactory#upload} silently queued the file into its {@code PendingUploadCache}
     * instead of persisting it - but {@code CloudUserService#uploadFile} has no way to tell the
     * difference and proceeded as if the upload had fully succeeded (created the {@code
     * StoredFileOwnership} row, updated the account's usage total, returned a {@code 201} to the
     * caller). The file only became readable again once {@link PendingUploadScheduler}'s next
     * tick (every minute) actually persisted it - in the meantime, opening/downloading/previewing
     * it 500'd with {@code IllegalStateException: owned file not found}, exactly what happened
     * opening a PDF right after extracting a ZIP. Wiring an always-available checker here removes
     * the false signal at its root, for this specific deployment topology (database co-located
     * with the app server) - the general offline-queueing machinery itself is untouched, and
     * still fully exercised by {@code CloudBootstrapSample}'s own, genuinely-disconnected scenario.
     */
    private static final ConnectivityChecker ALWAYS_AVAILABLE_CONNECTIVITY_CHECKER = () -> true;

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
     * Fires a fire-and-forget {@link DataFactory#getEntitiesAsync} scan of {@link
     * StoredFileOwnership} right after boot, so {@code EntityDatabaseClient}'s list-cache (see
     * {@code FactoryContainer#ENTITY_LIST_CACHE_TTL}) is already warm by the time a real client
     * makes its first {@code GET /files}/{@code GET /folders} call - every desktop-app file/folder
     * listing scans this exact type. Without this, the very first listing after every restart
     * still pays the full scan-and-decrypt cost (unavoidable - nothing has been read yet); this
     * just moves that one-time cost from "whoever happens to open the app first" to "boot itself",
     * where nobody is waiting on it. Not a subsystem with any real lifecycle of its own - fires
     * once and returns a no-op shutdown action, the same shape {@link #startEventScheduler} uses.
     *
     * @return a no-op shutdown action
     */
    private static Runnable warmFileListingCache() {
        CLOUD_DRIVER.getFactoryContainer().getDataFactory().getEntitiesAsync(StoredFileOwnership.class);
        return () -> {};
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
     * Uploads a {@link StoredFile} default file under a
     * fixed id, if not already present.
     *
     * @throws RuntimeException wrapping any I/O, database, or encryption failure
     */
    private static void initDefaultFile() {

        try {

            CLOUD_DRIVER.getFactoryContainer().getFileFactory().findById(Constraints.REQUIREMENTS_UUID.toString()).orElseGet(() -> {


                final StoredFile newStoredFile = new StoredFile(Constraints.REQUIREMENTS_UUID.toString(), "init.txt", new byte[0]);
                CLOUD_DRIVER.getFactoryContainer().getFileFactory().uploadAsync(newStoredFile).join();
                return newStoredFile;

            });

        } catch (DatabaseClientException | FileIntegrityException | AuthenticationFailedException |
                 KeyWrapException e) {
            throw new RuntimeException(e);
        }

    }

}
