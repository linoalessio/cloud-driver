package de.lino.cloud.plugin;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.audit.AuditEvent;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.event.extension.ExtensionUnregisterEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.container.IFactoryContainer;
import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.FileChunkManifest;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.search.SearchIndexService;
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
import de.lino.cloud.api.thumbnail.ThumbnailService;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.versioning.FileVersioningService;
import de.lino.cloud.api.webhook.WebhookService;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.entity.PublicShareLink;
import de.lino.cloud.auth.entity.RefreshToken;
import de.lino.cloud.auth.entity.SharedFileGrant;
import de.lino.cloud.auth.entity.SharedFolderGrant;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import de.lino.cloud.auth.pending.PendingEmailChange;
import de.lino.cloud.auth.pending.PendingPasswordReset;
import de.lino.cloud.auth.pending.PendingPresignedUpload;
import de.lino.cloud.auth.pending.PendingRegistration;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.factory.*;
import de.lino.cloud.plugin.factory.container.FactoryContainer;
import de.lino.cloud.plugin.factory.container.ServiceContainer;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.entity.Serialized;
import de.lino.database.json.JsonDocument;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
     * Every entity section {@link #reset()} clears, in the order it clears them - the one list
     * both the wipe and {@link #resetScope()} read, so what an operator is shown before
     * confirming cannot drift from what is actually deleted. A new entity type is added here and
     * nowhere else.
     */
    private static final List<Class<? extends Serialized>> RESET_SECTIONS = List.of(
            AuthUser.class, CloudUser.class, Folder.class,
            PendingRegistration.class, PendingPasswordReset.class, PendingEmailChange.class,
            PendingPresignedUpload.class, StoredFile.class, StoredFileOwnership.class,
            SharedFileGrant.class, SharedFolderGrant.class, PublicShareLink.class,
            RefreshToken.class, FileChunkManifest.class, AuditEvent.class, ApiKey.class);

    /**
     * {@inheritDoc}
     *
     * <p>Runs in three stages. First, if S3-backed content storage is configured ({@link
     * IFactoryContainer#getObjectStorageService()} non-{@code null}), {@link
     * #purgeS3BackedContent} deletes every object a {@link StoredFile} row currently points at.
     * That has to happen before the section wipe rather than alongside it: the object keys live
     * only on those rows, so a racing {@code deleteSectionAsync(StoredFile.class)} could remove a
     * row before its object was ever read. A dedup alias ({@link StoredFile#isDedupAlias()})
     * carries no {@code objectStorageKey} of its own and is skipped naturally, so each real object
     * is purged exactly once however many aliases point at it.
     *
     * <p>Second, every section in {@link #RESET_SECTIONS} is deleted concurrently (each {@link
     * DataFactory#deleteSectionAsync} call dispatches its own task) and this method waits for all
     * of them via {@link CompletableFuture#allOf}, propagating the first failure once every
     * deletion has been attempted - the same "attempt everything concurrently, don't report until
     * all are attempted" convention {@code EntityDatabaseClient}'s own batch operations use. A
     * wipe that failed part-way must never read as a wipe that succeeded.
     *
     * <p>Third, {@link #clearExtensionOwnedData()} clears what the optional extensions own. Unlike
     * the section wipe, that stage is best-effort: one dead optional subsystem must not leave the
     * rest of the wipe undone.
     *
     * <p>Key-encryption-key material is deliberately not touched: KEK rotation state lives in its
     * own raw {@code "kek"} {@link de.lino.database.database.DatabaseSection}, constructed directly
     * by {@code DatabaseKeyEncryptionService} rather than as a {@link Serialized} entity type, so
     * it is not reachable through {@link DataFactory#deleteSection} at all. Once every entity above
     * is gone there is nothing left for that key to protect, so leaving it in place is safe - but
     * it does mean a KEK rotated before this reset stays around after it.
     *
     * @see #resetScope()
     */
    @Override
    public void reset() {

        final DataFactory dataFactory = this.getFactoryContainer().getDataFactory();
        final ObjectStorageService objectStorageService = this.getFactoryContainer().getObjectStorageService();

        if (objectStorageService != null) {
            final int purgedObjects = this.purgeS3BackedContent(dataFactory, objectStorageService);
            this.getLogger().log(Level.INFO, "@DefaultCloudDriver.reset: purged " + purgedObjects + " object storage object(s)");
        }

        // Driven off RESET_SECTIONS rather than an inline list, so the wipe and resetScope() can
        // never describe different things.
        final List<CompletableFuture<Void>> deletions = RESET_SECTIONS.stream()
                .map(type -> dataFactory.deleteSectionAsync(type))
                .toList();

        CompletableFuture.allOf(deletions.toArray(new CompletableFuture[0])).join();

        this.getLogger().log(Level.WARNING, "@DefaultCloudDriver.reset: cleared " + RESET_SECTIONS.size()
                + " entity section(s): " + String.join(", ", RESET_SECTIONS.stream().map(Class::getSimpleName).toList()));

        // The remaining data belongs to extensions, whose entity types this module cannot name.
        // Each optional facet clears its own; an absent extension has nothing to clear.
        this.clearExtensionOwnedData();

    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads no rows: it names targets and asks each optional facet only whether it is
     * published. That is deliberate - the {@code StoredFile} section is pinned to the {@code NONE}
     * cache mode precisely because enumerating it in full is this codebase's known
     * out-of-memory shape, and a diagnostic printed before a wipe must never be the thing that
     * kills the process.
     *
     * <p>Object storage is named first, then every {@link #RESET_SECTIONS} entry, then each
     * published extension's own data - the order {@link #reset()} actually clears them in.
     */
    @NotNull
    @Override
    public List<String> resetScope() {

        final List<String> scope = new ArrayList<>();

        if (this.getFactoryContainer().getObjectStorageService() != null) {
            scope.add("object storage: every object a file row references");
        }

        for (final Class<? extends Serialized> type : RESET_SECTIONS) {
            scope.add("entity section: " + type.getSimpleName());
        }

        final IServiceContainer services = this.getServiceContainer();
        if (services.getFileVersioningService() != null) scope.add("extension data: file versions");
        if (services.getThumbnailService() != null) scope.add("extension data: thumbnails");
        if (services.getWebhookService() != null) scope.add("extension data: webhook subscriptions and delivery history");
        if (services.getSearchIndexService() != null) scope.add("extension data: keyword search index");

        return List.copyOf(scope);

    }

    /**
     * Clears the data owned by optional extensions - file versions, thumbnails, webhook
     * subscriptions together with their recorded delivery history, and the keyword search index.
     *
     * <p>Their entity types and stores live inside those extensions, so {@link #reset()} cannot
     * name them directly without inverting this project's dependency direction. Each service
     * clears its own instead, and an extension that is not running simply has nothing to clear.
     *
     * <p>The keyword search index belongs here for a second reason beyond ownership: it is the one
     * store in this system that holds plaintext - file names and lexemes derived from file content
     * - so a wipe that left it behind would leave exactly the readable material it was run to
     * destroy. Everything in it is derived from the files being wiped and is rebuildable from
     * them, so there is nothing in it to preserve once they are gone.
     *
     * <p>Best-effort throughout: a wipe must not stop half-way because one optional subsystem
     * failed.
     */
    private void clearExtensionOwnedData() {

        final IServiceContainer services = this.getServiceContainer();

        final FileVersioningService versioning = services.getFileVersioningService();
        this.clearBestEffort("file versions", versioning, () -> versioning.clearAllData());

        final ThumbnailService thumbnails = services.getThumbnailService();
        this.clearBestEffort("thumbnails", thumbnails, () -> thumbnails.clearAllData());

        final WebhookService webhooks = services.getWebhookService();
        this.clearBestEffort("webhook subscriptions and delivery history", webhooks, () -> webhooks.clearAllData());

        final SearchIndexService searchIndex = services.getSearchIndexService();
        this.clearBestEffort("the keyword search index", searchIndex, () -> searchIndex.clearAllData());

    }

    /**
     * Runs one optional facet's own clear step, logging what happened rather than propagating it -
     * the per-facet half of {@link #clearExtensionOwnedData()}'s best-effort contract. An absent
     * facet is logged as absent rather than skipped silently, so the process log left behind by a
     * wipe says which subsystems were and were not reached.
     *
     * @param label what is being cleared, as it appears in the log
     * @param facet the optional service, or {@code null} if this deployment never published one
     * @param action the clear call to run when {@code facet} is present
     */
    private void clearBestEffort(@NonNull final String label, final Object facet, @NonNull final Runnable action) {

        if (facet == null) {
            this.getLogger().log(Level.INFO, "@DefaultCloudDriver.reset: " + label + " is not published - nothing to clear");
            return;
        }

        try {
            action.run();
            this.getLogger().log(Level.INFO, "@DefaultCloudDriver.reset: cleared " + label);
        } catch (final RuntimeException clearFailed) {
            this.getLogger().log(Level.WARNING, "@DefaultCloudDriver.reset: failed to clear " + label, clearFailed);
        }

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
     * @return how many objects were actually deleted - {@code 0} if the row enumeration itself
     * failed, since nothing could then be identified to delete
     */
    private int purgeS3BackedContent(@NotNull final DataFactory dataFactory, @NotNull final ObjectStorageService objectStorageService) {
        final List<StoredFile> files;
        try {
            files = dataFactory.getEntities(StoredFile.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException enumerationFailed) {
            this.getLogger().log(Level.WARNING,
                    "@DefaultCloudDriver.reset: failed to enumerate StoredFile rows for the S3 purge - leaving any S3 objects behind", enumerationFailed);
            return 0;
        }
        int deleted = 0;
        for (final StoredFile file : files) {
            if (!file.isS3Backed()) continue;
            try {
                objectStorageService.deleteObject(file.objectStorageKey());
                deleted++;
            } catch (final ObjectStorageException deleteFailed) {
                this.getLogger().log(Level.WARNING,
                        "@DefaultCloudDriver.reset: failed to delete S3 object '" + file.objectStorageKey() + "' for file '" + file.fileId() + "'", deleteFailed);
            }
        }
        return deleted;
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
