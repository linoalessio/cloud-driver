package de.lino.cloud.plugin.factory.container;

import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.factory.container.IFactoryContainer;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.plugin.factory.*;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

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
     * indefinitely. See CLAUDE.md's "`EntityDatabaseClient`" section for the incident this fixes.
     */
    private static final Duration ENTITY_LIST_CACHE_TTL = Duration.ofMinutes(5);

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
     *     {@code objectStorageService}, if configured (see {@code architecture/AWS_S3_IMPL.md})
     * @param connectivityChecker backs {@link #fileFactory}'s offline-safe upload deferral
     * @param objectStorageService backs {@link #fileFactory}'s optional S3-backed content path, or
     *     {@code null} to keep every file inline
     * @throws NullPointerException if {@code databaseProvider}/{@code envelopeEncryptionService}/{@code connectivityChecker} is {@code null}
     */
    @SneakyThrows
    public FactoryContainer(@NonNull final DatabaseProvider databaseProvider, @NonNull final EnvelopeEncryptionService envelopeEncryptionService,
                             @NonNull final ConnectivityChecker connectivityChecker, @Nullable final ObjectStorageService objectStorageService) {

        this.dataFactory = new DefaultDataFactory(new EntityDatabaseClient(
                databaseProvider, envelopeEncryptionService,
                EntityDatabaseClient.DEFAULT_CACHE_TTL, EntityDatabaseClient.DEFAULT_CACHE_MAX_SIZE, ENTITY_LIST_CACHE_TTL
        ));
        this.objectStorageService = objectStorageService;
        this.fileFactory = new DefaultFileFactory(
                this.dataFactory, new InMemoryPendingUploadCache(), connectivityChecker, objectStorageService, envelopeEncryptionService
        );
        this.extensionFactory = new DefaultExtensionFactory();
        this.eventFactory = new DefaultEventFactory();
        this.restFactory = new DefaultRestFactory(this.dataFactory);

    }

}
