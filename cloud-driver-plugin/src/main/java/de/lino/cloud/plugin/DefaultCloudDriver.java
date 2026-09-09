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
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.rest.ApiKey;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.redis.RedisSupport;
import de.lino.cloud.api.s3storage.ObjectStorageService;
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
import org.jetbrains.annotations.Nullable;

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
     * explicit {@link ConnectivityChecker} backing {@link #getConnectivityChecker()}. S3-backed
     * {@code StoredFile} content is not configured - see {@link #setInstance(DatabaseProvider,
     * EnvelopeEncryptionService, ConnectivityChecker, ObjectStorageService)} to opt into it.
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence
     * @param connectivityChecker backs {@link #getConnectivityChecker()} and {@link DefaultFileFactory}
     * @return the installed instance
     */
    @NotNull
    public static CloudDriver setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService,
            @NotNull final ConnectivityChecker connectivityChecker
    ) {
        return setInstance(databaseProvider, envelopeEncryptionService, connectivityChecker, null);
    }

    /**
     * Same as {@link #setInstance(DatabaseProvider, EnvelopeEncryptionService, ConnectivityChecker)},
     * with an explicit {@link ObjectStorageService} backing {@link DefaultFileFactory}'s optional
     * S3-backed {@code StoredFile} content path - {@code null} keeps every file's content inline,
     * exactly as the three-argument overload does.
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence, and - if
     *     {@code objectStorageService} is non-{@code null} - a file's content independently before
     *     it's handed to that object store
     * @param connectivityChecker backs {@link #getConnectivityChecker()} and {@link DefaultFileFactory}
     * @param objectStorageService backs {@link DefaultFileFactory}'s optional S3-backed content
     *     path, or {@code null} to keep every file inline (this deployment's default)
     * @return the installed instance
     */
    @NotNull
    public static CloudDriver setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService,
            @NotNull final ConnectivityChecker connectivityChecker,
            @Nullable final ObjectStorageService objectStorageService
    ) {
        return setInstance(databaseProvider, envelopeEncryptionService, connectivityChecker, objectStorageService, null);
    }

    /**
     * Same as {@link #setInstance(DatabaseProvider, EnvelopeEncryptionService, ConnectivityChecker,
     * ObjectStorageService)}, with an explicit {@link RedisSupport} backing {@link
     * IFactoryContainer#getRedisSupport()} - {@code null} (every other overload's default) leaves
     * every Redis-backed behavior falling back to its in-process equivalent.
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence, and - if
     *     {@code objectStorageService} is non-{@code null} - a file's content independently before
     *     it's handed to that object store
     * @param connectivityChecker backs {@link #getConnectivityChecker()} and {@link DefaultFileFactory}
     * @param objectStorageService backs {@link DefaultFileFactory}'s optional S3-backed content
     *     path, or {@code null} to keep every file inline (this deployment's default)
     * @param redisSupport the optional Redis facet, or {@code null} if this deployment has none
     * @return the installed instance
     */
    @NotNull
    public static synchronized CloudDriver setInstance(
            @NotNull final DatabaseProvider databaseProvider,
            @NotNull final EnvelopeEncryptionService envelopeEncryptionService,
            @NotNull final ConnectivityChecker connectivityChecker,
            @Nullable final ObjectStorageService objectStorageService,
            @Nullable final RedisSupport redisSupport
    ) {

        final Terminal terminal = new Terminal(new DefaultPromptProvider());
        final Logger logger = Logger.getLogger(CloudDriver.class.getSimpleName());
        terminal.attachLogging(logger);

        final IFactoryContainer factoryContainer = new FactoryContainer(databaseProvider, envelopeEncryptionService, connectivityChecker, objectStorageService, redisSupport);
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

        // Released after the DataFactory rather than alongside it: Redis backs no entity
        // persistence at all (see RedisSupport's own Javadoc), so nothing torn down above can
        // still need it, and closing it earlier would only widen the window where an in-flight
        // request's rate-limit check hits an already-closed pool and falls back to in-process
        // counting for no reason.
        final RedisSupport redisSupport = this.factoryContainer.getRedisSupport();
        if (redisSupport != null) this.runShutdownStep("RedisSupport", redisSupport::shutdown);

        if (this.terminal != null && this.terminal.isActive())
            this.runShutdownStep("Terminal", this.terminal::shutdown);

        System.exit(0);

    }

    /**
     * Wipes every {@link de.lino.database.database.entity.Serialized} entity section this
     * repository defines - every {@link AuthUser}, {@link CloudUser}, {@link Folder}, {@link
     * StoredFile}, {@link StoredFileOwnership}, {@link PendingRegistration}, {@link
     * PendingPasswordReset}, and {@link ApiKey} row, across the whole database - and, if S3-backed
     * content storage is configured ({@link IFactoryContainer#getObjectStorageService()}
     * non-{@code null}), every S3 object any {@link StoredFile} row currently points at. Called by
     * the terminal package's {@code HardResetCommand} (aliased {@code reset}) after its own
     * two-step confirmation - there is no undo.
     *
     * <p><b>S3 purge added 2026-09-08, closing a real, previously-documented gap.</b> {@link
     * de.lino.cloud.plugin.factory.DefaultFileFactory#clear()}/{@code #deleteSection()} never
     * purged S3 objects - an accepted trade-off for those two methods on their own, since an
     * orphaned object left behind by a routine {@code clear()}/{@code deleteSection()} call is
     * cheap to clean up later - but this method bypasses {@link FileFactory} entirely and calls
     * {@link DataFactory#deleteSectionAsync} directly, so it never benefited from {@link
     * de.lino.cloud.plugin.factory.DefaultFileFactory#delete(String)}'s own S3 cleanup either, and
     * a full, deliberate, operator-confirmed account wipe leaving every S3 object behind is a much
     * larger gap than either of those two routine-operation ones. {@link #purgeS3BackedContent}
     * enumerates every {@link StoredFile} row (via {@link DataFactory#getEntities}, <b>before</b>
     * the {@code StoredFile} section itself is wiped below - the object keys live only on those
     * rows, so they must be read first) and best-effort deletes each {@link
     * StoredFile#isS3Backed()} row's own object; a dedup alias ({@link StoredFile#isDedupAlias()})
     * is naturally skipped, since an alias is never itself {@code isS3Backed()} (it carries no
     * {@code objectStorageKey} of its own), so each real S3 object is still purged exactly once
     * regardless of how many aliases point at it. Runs synchronously, before the concurrent
     * section wipe below starts - deliberately not folded into the same {@link
     * CompletableFuture#allOf} batch, since the {@code StoredFile} row list must be read in full
     * before {@code deleteSectionAsync(StoredFile.class)} can be allowed to remove it; racing the
     * two would risk the section wipe reaching a row before its object was ever purged.
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
        final ObjectStorageService objectStorageService = this.getFactoryContainer().getObjectStorageService();

        if (objectStorageService != null) {
            this.purgeS3BackedContent(dataFactory, objectStorageService);
        }

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
     * Enumerates every currently-stored {@link StoredFile} and best-effort deletes each {@link
     * StoredFile#isS3Backed()} row's own object from {@code objectStorageService} - see {@link
     * #reset()}'s own Javadoc for why this must run, synchronously, before that method's own
     * {@code StoredFile} section wipe. A dedup alias never carries an {@link
     * StoredFile#objectStorageKey()} of its own, so {@link StoredFile#isS3Backed()} alone is
     * enough to skip it without a separate {@link StoredFile#isDedupAlias()} check.
     *
     * <p>Never throws - an enumeration failure or a single object's delete failure is logged (via
     * {@link #getLogger()}) and the rest of {@link #reset()} proceeds regardless, the same "an S3
     * cleanup problem must never block a more important operation" reasoning {@code
     * DefaultFileFactory#deleteObjectQuietly} already applies per-object, extended here to the
     * enumeration step too - an orphaned S3 object left behind by a failed enumeration is a cheap
     * problem to clean up later by hand; a {@code reset()} that refused to wipe the database
     * because S3 was unreachable would not be.
     *
     * @param dataFactory the facet {@link StoredFile} rows are read through
     * @param objectStorageService the facet each S3-backed row's object is deleted through
     */
    private void purgeS3BackedContent(@NotNull final DataFactory dataFactory, @NotNull final ObjectStorageService objectStorageService) {
        final List<StoredFile> files;
        try {
            files = dataFactory.getEntities(StoredFile.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException enumerationFailed) {
            this.getLogger().log(Level.WARNING,
                    "@DefaultCloudDriver.reset: failed to enumerate StoredFile rows for the S3 purge - leaving any S3 objects behind", enumerationFailed);
            return;
        }
        for (final StoredFile file : files) {
            if (!file.isS3Backed()) continue;
            try {
                objectStorageService.deleteObject(file.objectStorageKey());
            } catch (final ObjectStorageException deleteFailed) {
                this.getLogger().log(Level.WARNING,
                        "@DefaultCloudDriver.reset: failed to delete S3 object '" + file.objectStorageKey() + "' for file '" + file.fileId() + "'", deleteFailed);
            }
        }
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
