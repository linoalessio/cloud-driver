package de.lino.cloud.plugin.factory.container;

import de.lino.cloud.api.event.database.FileChangeListenerRegistry;
import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.factory.container.IFactoryContainer;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.redis.RedisSupport;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.s3storage.ContentKeyService;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.plugin.event.database.DefaultFileChangeListenerRegistry;
import de.lino.cloud.plugin.factory.*;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.s3storage.StreamingContentKeyService;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.SectionConfig;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * Default {@link IFactoryContainer} implementation: builds one mutually
 * consistent set of {@link CloudDriver} facets - a {@link DefaultDataFactory}
 * (backed by a fresh {@link EntityDatabaseClient}), a {@link
 * DefaultFileFactory} sharing that same {@link DataFactory}, a {@link
 * DefaultExtensionFactory}, a {@link DefaultEventFactory}, and an
 * unauthenticated {@link DefaultRestFactory} - all wired together in one
 * constructor call so a {@code CloudDriver} implementation never ends up with
 * facets backed by different underlying data.
 */
@Getter
public class FactoryContainer implements IFactoryContainer {

    /**
     * How long a {@link EntityDatabaseClient#getEntities} scan result stays cached, independent
     * of that same client's much shorter (30s default) per-entity cache TTL - see {@link
     * EntityDatabaseClient}'s own {@code listCacheTtl} Javadoc for why these two are deliberately
     * decoupled. 5 minutes: long enough that a normal GUI browsing/pagination session (repeated
     * folder navigation, "Load more" clicks) essentially always hits this cache after its first
     * call, short enough that a rarely-run, full-content scan (e.g. the terminal's {@code stats}
     * command, which calls {@code getEntities(StoredFile.class)} - content included, unlike the
     * {@code StoredFileOwnership} scan the GUI's own listing calls) doesn't linger in memory
     * indefinitely.
     */
    private static final Duration ENTITY_LIST_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Per-type override of {@link #ENTITY_LIST_CACHE_TTL}, passed to {@link EntityDatabaseClient}'s
     * {@code listCacheTtlOverrides} constructor argument. {@code StoredFile} is disabled outright
     * ({@link Duration#ZERO}) rather than merely shortened: unlike every other type this container
     * scans (e.g. {@code StoredFileOwnership}, a small metadata row), a {@code StoredFile}'s {@code
     * getEntities} result carries full decrypted file content - caching that at the list level for
     * any nonzero window, however short, still pins an account's entire file corpus in heap for that
     * window on every call (e.g. the terminal's {@code stats} command). Disabling it means that
     * content is never held any longer than the single call that produced it.
     */
    private static final Map<Class<?>, Duration> ENTITY_LIST_CACHE_TTL_OVERRIDES = Map.of(StoredFile.class, Duration.ZERO);

    /**
     * Per-type override of {@link EntityDatabaseClient}'s default {@code
     * SectionConfig.full()} - passed to its {@code sectionConfigOverrides} constructor argument.
     * {@code StoredFile} is the one type overridden, to {@link SectionConfig#none()}: unlike every
     * other type this container persists, a {@code StoredFile} row can still carry a legacy file's
     * entire base64 content inline (see {@link #ENTITY_LIST_CACHE_TTL_OVERRIDES}'s own {@code
     * StoredFile} entry for the matching concern at the list-cache layer), so letting the database
     * layer hold every such row's ciphertext in its own row cache - on top of, and independent of,
     * this container's already-bounded decrypted {@code EntityDatabaseClient} cache - grew the
     * process's heap floor in lockstep with the total size of stored file content, once fully
     * proportional to the whole database (root cause of the 2026-09-09 boot OOM crash loop, see
     * docs/troubleshooting.md). {@code SectionConfig.none()} means every {@code StoredFile}
     * operation is pushed straight to Postgres instead of duplicating rows into a second, unbounded
     * in-memory cache the application never needed - {@code EntityDatabaseClient}'s own cache
     * already serves the hot path.
     */
    private static final Map<Class<?>, SectionConfig> SECTION_CONFIG_OVERRIDES = Map.of(StoredFile.class, SectionConfig.none());

    /** Encrypted entity persistence, backed by a fresh {@link EntityDatabaseClient}. */
    private final DataFactory dataFactory;

    /** File upload/download, backed by {@link #dataFactory} and a fresh {@link InMemoryPendingUploadCache}. */
    private final FileFactory fileFactory;

    /** Registers, starts, and stops {@code Extension}s. */
    private final ExtensionFactory extensionFactory;

    /** Registers, looks up, unregisters, and dispatches {@code Event}s. */
    private final EventFactory eventFactory;

    /** Mounts entities reachable through {@link #dataFactory} onto an unauthenticated HTTP API. */
    private final RestFactory restFactory;

    /** Backs {@link #fileFactory}'s optional S3-backed {@code StoredFile} content path, or {@code null} if this deployment hasn't opted into it. */
    private final ObjectStorageService objectStorageService;

    /** Issues/recovers per-file content keys for client-encrypted presigned transfers - built on the same envelope-encryption service as everything else, always present. */
    private final ContentKeyService contentKeyService;

    /** Fan-out point for {@code DatabaseWatchEvent} notifications - see its own Javadoc. Always constructed, regardless of whether {@code cloud-driver-watcher} ever actually runs. */
    private final FileChangeListenerRegistry fileChangeListenerRegistry;

    /** The optional Redis facet, or {@code null} if this deployment has none reachable - see {@link RedisSupport}'s own Javadoc. */
    private final RedisSupport redisSupport;

    /**
     * Same as {@link #FactoryContainer(DatabaseProvider, EnvelopeEncryptionService,
     * ConnectivityChecker, ObjectStorageService)} with {@code objectStorageService} defaulted to
     * {@code null} - S3-backed content not configured, every file stays inline (this deployment's
     * default).
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider every entity/file is persisted through
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence
     * @param connectivityChecker backs {@link #fileFactory}'s offline-safe upload deferral
     * @throws NullPointerException if any argument is {@code null}
     */
    public FactoryContainer(@NonNull final DatabaseProvider databaseProvider, @NonNull final EnvelopeEncryptionService envelopeEncryptionService, @NonNull final ConnectivityChecker connectivityChecker) {
        this(databaseProvider, envelopeEncryptionService, connectivityChecker, null);
    }

    /**
     * Builds every facet from {@code databaseProvider}/{@code envelopeEncryptionService}/{@code
     * connectivityChecker}/{@code objectStorageService}. Annotated {@link SneakyThrows} because
     * {@link DefaultExtensionFactory}'s no-arg constructor declares a checked {@link
     * java.io.IOException} (if {@code Constraints#EXTENSIONS_PATH} cannot be created) that this
     * constructor has no meaningful way to recover from - it is rethrown unchecked rather than
     * wrapped.
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider every entity/file is persisted through
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence - also what
     *     {@link #fileFactory} uses to encrypt a file's content independently before handing it to
     *     {@code objectStorageService}, if configured
     * @param connectivityChecker backs {@link #fileFactory}'s offline-safe upload deferral
     * @param objectStorageService backs {@link #fileFactory}'s optional S3-backed content path, or
     *     {@code null} to keep every file inline
     * @throws NullPointerException if {@code databaseProvider}/{@code envelopeEncryptionService}/{@code connectivityChecker} is {@code null}
     */
    public FactoryContainer(@NonNull final DatabaseProvider databaseProvider, @NonNull final EnvelopeEncryptionService envelopeEncryptionService,
                             @NonNull final ConnectivityChecker connectivityChecker, @Nullable final ObjectStorageService objectStorageService) {
        this(databaseProvider, envelopeEncryptionService, connectivityChecker, objectStorageService, null);
    }

    /**
     * Same as {@link #FactoryContainer(DatabaseProvider, EnvelopeEncryptionService,
     * ConnectivityChecker, ObjectStorageService)}, with an explicit {@link RedisSupport} backing
     * {@link #getRedisSupport()} - {@code null} (every other overload's default) leaves every
     * Redis-backed behavior falling back to its in-process equivalent, exactly as before Redis
     * support existed.
     *
     * @param databaseProvider the backing {@code database-driver-plugin} provider every entity/file is persisted through
     * @param envelopeEncryptionService encrypts/decrypts entities before persistence - also what
     *     {@link #fileFactory} uses to encrypt a file's content independently before handing it to
     *     {@code objectStorageService}, if configured
     * @param connectivityChecker backs {@link #fileFactory}'s offline-safe upload deferral
     * @param objectStorageService backs {@link #fileFactory}'s optional S3-backed content path, or
     *     {@code null} to keep every file inline
     * @param redisSupport the optional Redis facet, or {@code null} if this deployment has none
     * @throws NullPointerException if {@code databaseProvider}/{@code envelopeEncryptionService}/{@code connectivityChecker} is {@code null}
     */
    @SneakyThrows
    public FactoryContainer(@NonNull final DatabaseProvider databaseProvider, @NonNull final EnvelopeEncryptionService envelopeEncryptionService,
                             @NonNull final ConnectivityChecker connectivityChecker, @Nullable final ObjectStorageService objectStorageService,
                             @Nullable final RedisSupport redisSupport) {

        this.redisSupport = redisSupport;
        this.dataFactory = new DefaultDataFactory(new EntityDatabaseClient(
                databaseProvider, envelopeEncryptionService,
                EntityDatabaseClient.DEFAULT_CACHE_TTL, EntityDatabaseClient.DEFAULT_CACHE_MAX_SIZE, ENTITY_LIST_CACHE_TTL,
                ENTITY_LIST_CACHE_TTL_OVERRIDES, SECTION_CONFIG_OVERRIDES
        ));
        this.objectStorageService = objectStorageService;
        this.fileFactory = new DefaultFileFactory(
                this.dataFactory, new InMemoryPendingUploadCache(), connectivityChecker, objectStorageService, envelopeEncryptionService
        );
        this.contentKeyService = new StreamingContentKeyService(envelopeEncryptionService);
        this.extensionFactory = new DefaultExtensionFactory();
        this.eventFactory = new DefaultEventFactory();
        this.restFactory = new DefaultRestFactory(this.dataFactory);
        this.fileChangeListenerRegistry = new DefaultFileChangeListenerRegistry();

    }

}
