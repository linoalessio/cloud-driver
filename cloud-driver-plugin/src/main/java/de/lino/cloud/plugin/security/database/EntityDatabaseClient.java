package de.lino.cloud.plugin.security.database;

import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.database.EncryptedEntityRecord;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.plugin.security.entity.SecureEntityChannel;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.entity.Serialized;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.provider.Caches;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists and retrieves {@link Serialized} domain entities in per-type
 * {@link DatabaseSection}s of a {@link DatabaseProvider}: each entity is
 * envelope-encrypted via {@link SecureEntityChannel} before it is written,
 * then stored as an {@link EncryptedEntityRecord} JSON document under its
 * {@link Serialized#primaryKey() primary key}. The one class in this driver
 * that actually performs database I/O - everything else under {@code
 * security} only prepares data for it.
 *
 * <p><b>Concurrency.</b> Safe to share and call from multiple threads.
 * {@link #store} inserts first and falls back to an update on collision
 * (never an {@code exists()}-then-branch check, which would race). Batch
 * operations dispatch each entity/id concurrently on {@link
 * MultiTaskingFactory}'s shared virtual-thread executor.
 *
 * <p><b>Caching.</b> Each entity type gets its own read-through,
 * write-through {@link Cache}, bounded by default to {@link
 * #DEFAULT_CACHE_TTL}/{@link #DEFAULT_CACHE_MAX_SIZE} since it holds
 * decrypted plaintext in memory. Tune via the second constructor.
 */
public final class EntityDatabaseClient {

    /** The {@link JsonDocument} field name an entity's {@link EncryptedEntityRecord} is stored under. */
    private static final String DATA_KEY = "data";

    /** Default per-type cache time-to-live, used by the single-argument constructor. */
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

    /** Default per-type cache maximum entry count, used by the single-argument constructor. */
    private static final long DEFAULT_CACHE_MAX_SIZE = 1_000;

    /** The provider every entity type's {@link DatabaseSection} is resolved against. */
    private final DatabaseProvider databaseProvider;

    /** Envelope-encrypts/decrypts entities before/after they reach the database. */
    private final SecureEntityChannel secureEntityChannel;

    /** Per-type decrypted-entity cache time-to-live; {@code null} means unbounded. */
    private final Duration cacheTtl;

    /** Per-type decrypted-entity cache maximum entry count; {@code <= 0} means unbounded. */
    private final long cacheMaxSize;

    /** One cache per entity type, created lazily via {@link #cacheFor}. */
    private final Map<Class<? extends Serialized>, Cache<String, ? extends Serialized>> caches = new ConcurrentHashMap<>();

    /** One {@link DatabaseSection} per entity type, created lazily via {@link #sectionFor}. */
    private final Map<Class<?>, DatabaseSection> sections = new ConcurrentHashMap<>();

    /**
     * Constructs a client with the default cache bounds ({@link #DEFAULT_CACHE_TTL}/{@link #DEFAULT_CACHE_MAX_SIZE}).
     *
     * @param databaseProvider the provider meta sections are resolved against
     * @param envelopeEncryptionService the envelope-encryption service backing this client's {@link SecureEntityChannel}
     * @throws NullPointerException if {@code databaseProvider} or {@code envelopeEncryptionService} is {@code null}
     */
    public EntityDatabaseClient(@NotNull final DatabaseProvider databaseProvider,
                                 @NotNull final EnvelopeEncryptionService envelopeEncryptionService) {
        this(databaseProvider, envelopeEncryptionService, DEFAULT_CACHE_TTL, DEFAULT_CACHE_MAX_SIZE);
    }

    /**
     * Constructs a client with explicit cache bounds - see the class Javadoc's "Caching" section.
     *
     * @param databaseProvider the provider meta sections are resolved against
     * @param envelopeEncryptionService the envelope-encryption service backing this client's {@link SecureEntityChannel}
     * @param cacheTtl how long a decrypted meta stays cached; {@code null} for unbounded
     * @param cacheMaxSize maximum cached entries per meta type; {@code <= 0} for unbounded
     * @throws NullPointerException if {@code databaseProvider} or {@code envelopeEncryptionService} is {@code null}
     */
    public EntityDatabaseClient(@NotNull final DatabaseProvider databaseProvider,
                                 @NotNull final EnvelopeEncryptionService envelopeEncryptionService,
                                 final Duration cacheTtl, final long cacheMaxSize) {
        this.databaseProvider = Asserts.requireNonNull(databaseProvider, "@EntityDatabaseClient: databaseProvider cannot be null");
        this.secureEntityChannel = new SecureEntityChannel(
                Asserts.requireNonNull(envelopeEncryptionService, "@EntityDatabaseClient: envelopeEncryptionService cannot be null")
        );
        this.cacheTtl = cacheTtl;
        this.cacheMaxSize = cacheMaxSize;
    }

    /** Returns {@code type}'s {@link DatabaseSection} (named after its simple class name), creating it if needed. */
    private DatabaseSection sectionFor(final Class<?> type) {
        return sections.computeIfAbsent(type, key -> {
            final String sectionName = key.getSimpleName();
            return databaseProvider.getSection(sectionName).orElseGet(() -> databaseProvider.createSection(sectionName));
        });
    }

    /**
     * Encrypts {@code entity} and inserts or updates it under its primary key.
     *
     * @param entity the entity to store
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws DatabaseClientException if the write fails
     * @throws KeyWrapException if wrapping the data-encryption key fails
     */
    public <T extends Serialized> void store(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        Asserts.requireNonNull(entity, "@EntityDatabaseClient.store: meta cannot be null");

        final EnvelopeEncryptedPayload envelope = secureEntityChannel.send(entity);
        final JsonDocument document = new JsonDocument().append(DATA_KEY, EncryptedEntityRecord.from(envelope));
        final DatabaseEntry entry = new DatabaseEntry(entity.primaryKey(), document);
        final DatabaseSection section = sectionFor(entity.getClass());

        try {
            section.insert(entry);
        } catch (final DataAlreadyExist alreadyExists) {
            try {
                section.update(entry);
            } catch (final NoSuchEntryFound raceLost) {
                throw new DatabaseClientException(
                        "@EntityDatabaseClient.store: failed to persist " + entity.getClass().getSimpleName()
                                + " " + entity.primaryKey() + " due to a concurrent modification", raceLost
                );
            }
        }

        // Write-through: the plaintext is already in hand here, so cache it
        // directly instead of waiting for the next retrieve() to decrypt it
        // again from what was just written.
        cachePut(entity);
    }

    /**
     * Write-through helper: puts {@code entity} directly into its type's cache
     * under its primary key, so a just-written entity is served from cache
     * without a redundant round trip back through the database.
     *
     * @param entity the entity to cache
     */
    @SuppressWarnings("unchecked") // safe: meta's own runtime type is always a valid Class<T> for meta itself
    private <T extends Serialized> void cachePut(final T entity) {
        final Class<T> type = (Class<T>) entity.getClass();
        cacheFor(type).put(entity.primaryKey(), entity);
    }

    /**
     * Encrypts {@code entity} and overwrites its existing record. Unlike
     * {@link #store}, fails if no record exists yet under its primary key.
     *
     * @param entity the entity to update
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws DatabaseClientException if no existing record is found, or the write fails
     * @throws KeyWrapException if wrapping the data-encryption key fails
     */
    public <T extends Serialized> void update(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        Asserts.requireNonNull(entity, "@EntityDatabaseClient.update: meta cannot be null");

        final EnvelopeEncryptedPayload envelope = secureEntityChannel.send(entity);
        final JsonDocument document = new JsonDocument().append(DATA_KEY, EncryptedEntityRecord.from(envelope));
        final DatabaseEntry entry = new DatabaseEntry(entity.primaryKey(), document);

        try {
            sectionFor(entity.getClass()).update(entry);
        } catch (final NoSuchEntryFound notFound) {
            throw new DatabaseClientException(
                    "@EntityDatabaseClient.update: no entry found with id '" + entity.primaryKey() + "'", notFound
            );
        }

        // Write-through, same reasoning as store().
        cachePut(entity);
    }

    /**
     * Encrypts and overwrites the existing record of every entity in {@code
     * entities}, dispatched concurrently. Throws the first failure
     * encountered once every entity has been attempted.
     *
     * @param entities the entities to update
     * @throws NullPointerException if {@code entities} is {@code null}
     * @throws DatabaseClientException if any update fails
     * @throws KeyWrapException if wrapping a data-encryption key fails
     */
    public <T extends Serialized> void updateAll(@NotNull final List<T> entities) throws DatabaseClientException, KeyWrapException {
        Asserts.requireNonNull(entities, "@EntityDatabaseClient.updateAll: entities cannot be null");

        final List<CompletableFuture<Void>> futures = entities.stream()
                .map(entity -> MultiTaskingFactory.getInstance().runAsync(() -> updateUnchecked(entity)))
                .toList();

        // update() never decrypts anything, so it can never actually fail
        // authentication - joinAllStore keeps that reflected in this
        // method's throws clause instead of claiming AuthenticationFailedException.
        joinAllStore(futures);
    }

    /**
     * {@link #update} wrapper for dispatch on {@link MultiTaskingFactory}'s
     * executor: rethrows a checked failure wrapped in a {@link CompletionException}.
     *
     * @param entity the entity to update
     */
    private <T extends Serialized> void updateUnchecked(final T entity) {
        try {
            update(entity);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Encrypts and stores every entity in {@code entities}, dispatched
     * concurrently. Throws the first failure encountered once every entity
     * has been attempted.
     *
     * @param entities the entities to store
     * @throws NullPointerException if {@code entities} is {@code null}
     * @throws DatabaseClientException if any store fails
     * @throws KeyWrapException if wrapping a data-encryption key fails
     */
    public <T extends Serialized> void storeAll(@NotNull final List<T> entities) throws DatabaseClientException, KeyWrapException {
        Asserts.requireNonNull(entities, "@EntityDatabaseClient.storeAll: entities cannot be null");

        final List<CompletableFuture<Void>> futures = entities.stream()
                .map(entity -> MultiTaskingFactory.getInstance().runAsync(() -> storeUnchecked(entity)))
                .toList();

        // store() never decrypts anything, so it can never actually fail
        // authentication - joinAllStore keeps that reflected in this
        // method's throws clause instead of claiming AuthenticationFailedException.
        joinAllStore(futures);
    }

    /**
     * {@link #store} wrapper for dispatch on {@link MultiTaskingFactory}'s
     * executor: rethrows a checked failure wrapped in a {@link CompletionException}.
     *
     * @param entity the entity to store
     */
    private <T extends Serialized> void storeUnchecked(final T entity) {
        try {
            store(entity);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Fetches and decrypts the entity stored under {@code objectId} - from
     * cache if present, otherwise from the database, verifying the
     * authentication tag before returning any plaintext.
     *
     * @param objectId the primary key to look up
     * @param type the entity type
     * @return the decrypted entity
     * @throws NullPointerException if {@code objectId} or {@code type} is {@code null}
     * @throws DatabaseClientException if no such entity exists or the record is corrupted
     * @throws KeyWrapException if unwrapping the data-encryption key fails
     * @throws AuthenticationFailedException if authentication tag verification fails
     */
    @NotNull
    public <T extends Serialized> T retrieve(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(objectId, "@EntityDatabaseClient.retrieve: objectId cannot be null");
        Asserts.requireNonNull(type, "@EntityDatabaseClient.retrieve: type cannot be null");

        return unwrap(cacheFor(type).get(objectId));
    }

    /**
     * Fetches and decrypts every entity stored under {@code objectIds}, in
     * the same order, dispatched concurrently.
     *
     * @param objectIds the primary keys to look up
     * @param type the entity type
     * @return the decrypted entities, in the same order as {@code objectIds}
     * @throws NullPointerException if {@code objectIds} or {@code type} is {@code null}
     * @throws DatabaseClientException if any lookup fails
     * @throws KeyWrapException if unwrapping a data-encryption key fails
     * @throws AuthenticationFailedException if any authentication tag verification fails
     */
    @NotNull
    public <T extends Serialized> List<T> retrieveAll(@NotNull final List<String> objectIds, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(objectIds, "@EntityDatabaseClient.retrieveAll: objectIds cannot be null");
        Asserts.requireNonNull(type, "@EntityDatabaseClient.retrieveAll: type cannot be null");

        final Cache<String, T> cache = cacheFor(type);
        final List<CompletableFuture<T>> futures = objectIds.stream().map(cache::get).toList();

        joinAll(futures);
        // Every future is already complete at this point (joinAll waited on
        // all of them), so these joins return immediately.
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Looks up the entity stored under {@code objectId}, like {@link
     * #retrieve}, but returns {@link Optional#empty()} on a genuine miss
     * instead of throwing - a confirmed-present-but-corrupted record is
     * still rethrown.
     *
     * @param objectId the primary key to look up
     * @param type the entity type
     * @return the decrypted entity, or empty if no such entity exists
     * @throws NullPointerException if {@code objectId} or {@code type} is {@code null}
     * @throws DatabaseClientException if the record exists but is corrupted
     * @throws KeyWrapException if unwrapping the data-encryption key fails
     * @throws AuthenticationFailedException if authentication tag verification fails
     */
    @NotNull
    public <T extends Serialized> Optional<T> findById(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(objectId, "@EntityDatabaseClient.findById: objectId cannot be null");
        Asserts.requireNonNull(type, "@EntityDatabaseClient.findById: type cannot be null");

        try {
            return Optional.of(retrieve(objectId, type));
        } catch (final DatabaseClientException notFoundOrCorrupted) {
            if (sectionFor(type).exists(objectId)) {
                throw notFoundOrCorrupted;
            }
            return Optional.empty();
        }
    }

    /**
     * Retrieves and decrypts every entity of {@code type} currently stored, via {@link #retrieveAll}.
     *
     * @param type the entity type
     * @return every decrypted entity of {@code type}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws DatabaseClientException if any lookup fails
     * @throws KeyWrapException if unwrapping a data-encryption key fails
     * @throws AuthenticationFailedException if any authentication tag verification fails
     */
    @NotNull
    public <T extends Serialized> List<T> getEntities(@NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(type, "@EntityDatabaseClient.getEntities: type cannot be null");

        final List<String> objectIds = sectionFor(type).getEntries().stream().map(DatabaseEntry::getId).toList();
        return retrieveAll(objectIds, type);
    }

    /**
     * Deletes the entity stored under {@code objectId} and evicts it from cache.
     *
     * @param objectId the primary key to delete
     * @param type the entity type
     * @throws NullPointerException if {@code objectId} or {@code type} is {@code null}
     * @throws DatabaseClientException if no such entity exists
     */
    public <T extends Serialized> void delete(@NotNull final String objectId, @NotNull final Class<T> type) throws DatabaseClientException {
        Asserts.requireNonNull(objectId, "@EntityDatabaseClient.delete: objectId cannot be null");
        Asserts.requireNonNull(type, "@EntityDatabaseClient.delete: type cannot be null");

        try {
            sectionFor(type).delete(objectId);
        } catch (final NoSuchEntryFound notFound) {
            throw new DatabaseClientException(
                    "@EntityDatabaseClient.delete: no entry found with id '" + objectId + "'", notFound
            );
        }

        cacheFor(type).invalidate(objectId);
    }

    /**
     * Deletes every entity stored under {@code objectIds}, dispatched
     * concurrently. Throws the first failure encountered once every id has
     * been attempted.
     *
     * @param objectIds the primary keys to delete
     * @param type the entity type
     * @throws NullPointerException if {@code objectIds} or {@code type} is {@code null}
     * @throws DatabaseClientException if any deletion fails
     */
    public <T extends Serialized> void deleteAll(@NotNull final List<String> objectIds, @NotNull final Class<T> type) throws DatabaseClientException {
        Asserts.requireNonNull(objectIds, "@EntityDatabaseClient.deleteAll: objectIds cannot be null");
        Asserts.requireNonNull(type, "@EntityDatabaseClient.deleteAll: type cannot be null");

        final List<CompletableFuture<Void>> futures = objectIds.stream()
                .map(objectId -> MultiTaskingFactory.getInstance().runAsync(() -> deleteUnchecked(objectId, type)))
                .toList();

        joinAllDelete(futures);
    }

    /**
     * {@link #delete} wrapper for dispatch on {@link MultiTaskingFactory}'s
     * executor: rethrows a checked failure wrapped in a {@link CompletionException}.
     *
     * @param objectId the primary key to delete
     * @param type the entity type
     */
    private <T extends Serialized> void deleteUnchecked(final String objectId, final Class<T> type) {
        try {
            delete(objectId, type);
        } catch (final DatabaseClientException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Clears every entry from {@code type}'s section (leaving the section
     * itself intact) and invalidates its cache. Use {@link #deleteSection}
     * to remove the section itself.
     *
     * @param type the entity type
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public <T extends Serialized> void clear(@NotNull final Class<T> type) {
        Asserts.requireNonNull(type, "@EntityDatabaseClient.clear: type cannot be null");

        sectionFor(type).clear();
        final Cache<String, ? extends Serialized> cache = caches.get(type);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * Re-reads {@code type}'s section from the database (via {@link
     * DatabaseSection#reload()}) and invalidates its cache - refreshes this
     * process's local view without touching the database's contents, unlike
     * {@link #clear}. See {@link de.lino.cloud.api.factory.DataFactory#reload}.
     *
     * @param type the entity type
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public <T extends Serialized> void reload(@NotNull final Class<T> type) {
        Asserts.requireNonNull(type, "@EntityDatabaseClient.reload: type cannot be null");

        sectionFor(type).reload();
        final Cache<String, ? extends Serialized> cache = caches.get(type);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * Deletes {@code type}'s database section entirely (not just its
     * entries) and discards its cached section/cache references. A later
     * operation on {@code type} lazily recreates both.
     *
     * @param type the entity type
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public <T extends Serialized> void deleteSection(@NotNull final Class<T> type) {
        Asserts.requireNonNull(type, "@EntityDatabaseClient.deleteSection: type cannot be null");

        final String databaseName = type.getSimpleName();

        if (this.databaseProvider.getSection(databaseName).isEmpty()) return;

        this.databaseProvider.deleteSection(type.getSimpleName());
        this.sections.remove(type);
        this.caches.remove(type);
    }

    /** Shuts the backing {@link DatabaseProvider} down, releasing its connection(s)/pool. */
    public void shutdown() {
        this.databaseProvider.shutdown();
    }

    /**
     * Returns {@code type}'s read-through, write-through {@link Cache},
     * creating it lazily (bounded by {@link #cacheTtl}/{@link #cacheMaxSize})
     * with a loader that fetches and decrypts from the database on a miss.
     *
     * @param type the entity type
     * @return the cache backing {@code type}
     */
    @SuppressWarnings("unchecked") // safe: every cache is both created and looked up keyed by the same Class<T>
    private <T extends Serialized> Cache<String, T> cacheFor(final Class<T> type) {
        return (Cache<String, T>) caches.computeIfAbsent(
                type, key -> Caches.<String, T>newCache(id -> loadFromDatabaseAsync(id, type), cacheTtl, cacheMaxSize)
        );
    }

    /**
     * {@link #loadFromDatabase} dispatched onto {@link MultiTaskingFactory}'s
     * shared virtual-thread executor - the cache loader a {@link Cache} miss invokes.
     *
     * @param objectId the primary key to look up
     * @param type the entity type
     * @return a future completing with the decrypted entity, or exceptionally with a {@link CompletionException}
     */
    private <T extends Serialized> CompletableFuture<T> loadFromDatabaseAsync(final String objectId, final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> loadFromDatabase(objectId, type));
    }

    /**
     * Reads {@code objectId}'s raw {@link DatabaseEntry} from {@code type}'s
     * section, unwraps its {@link EncryptedEntityRecord}, and decrypts it via
     * {@link SecureEntityChannel#receive}. Any failure (missing entry,
     * corrupted record, key-wrap/authentication failure) is thrown wrapped in
     * a {@link CompletionException}, since this runs as a {@link Cache} loader.
     *
     * @param objectId the primary key to look up
     * @param type the entity type
     * @return the decrypted entity
     */
    private <T extends Serialized> T loadFromDatabase(final String objectId, final Class<T> type) {
        final Optional<DatabaseEntry> entry;
        try {
            entry = sectionFor(type).findEntryById(objectId);
        } catch (final NoSuchDataFound e) {
            throw new CompletionException(new DatabaseClientException(
                    "@EntityDatabaseClient.retrieve: corrupted record for " + type.getSimpleName() + " " + objectId, e
            ));
        }

        if (entry.isEmpty()) {
            throw new CompletionException(new DatabaseClientException(
                    "@EntityDatabaseClient.retrieve: no " + type.getSimpleName() + " found with id '" + objectId + "'"
            ));
        }

        final EncryptedEntityRecord record = entry.get().getDocument().get(DATA_KEY, EncryptedEntityRecord.class);
        if (record == null) {
            throw new CompletionException(new DatabaseClientException(
                    "@EntityDatabaseClient.retrieve: record for " + type.getSimpleName() + " " + objectId + " has no '" + DATA_KEY + "' payload"
            ));
        }

        try {
            return secureEntityChannel.receive(record.toEnvelope(), type);
        } catch (final KeyWrapException | AuthenticationFailedException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * {@link #joinAll} for a batch of pure-store futures, which never decrypt
     * anything: narrows the throws clause to {@link DatabaseClientException}/
     * {@link KeyWrapException} by wrapping an (impossible in practice) {@link
     * AuthenticationFailedException} in an {@link IllegalStateException}.
     *
     * @param futures the futures to await
     * @throws DatabaseClientException if any future failed with one
     * @throws KeyWrapException if any future failed with one
     */
    private static void joinAllStore(final List<CompletableFuture<Void>> futures) throws DatabaseClientException, KeyWrapException {
        try {
            joinAll(futures);
        } catch (final AuthenticationFailedException impossible) {
            throw new IllegalStateException(
                    "@EntityDatabaseClient: unexpected authentication failure while storing (store() never decrypts)", impossible
            );
        }
    }

    /**
     * {@link #joinAll} for a batch of pure-delete futures, which never touch
     * keys or decrypt anything: narrows the throws clause to {@link
     * DatabaseClientException} by wrapping an (impossible in practice) {@link
     * KeyWrapException}/{@link AuthenticationFailedException} in an {@link IllegalStateException}.
     *
     * @param futures the futures to await
     * @throws DatabaseClientException if any future failed with one
     */
    private static void joinAllDelete(final List<CompletableFuture<Void>> futures) throws DatabaseClientException {
        try {
            joinAll(futures);
        } catch (final KeyWrapException | AuthenticationFailedException impossible) {
            throw new IllegalStateException(
                    "@EntityDatabaseClient: unexpected key-wrap/authentication failure while deleting (delete() never touches keys)", impossible
            );
        }
    }

    /**
     * Waits for every future in {@code futures} to complete (success or
     * failure) and unwraps the first failure encountered, if any, via {@link
     * #unwrap}. Since the futures are already running concurrently, this
     * blocks until all of them have finished before surfacing any failure.
     *
     * @param futures the futures to await
     * @throws DatabaseClientException if any future failed with one
     * @throws KeyWrapException if any future failed with one
     * @throws AuthenticationFailedException if any future failed with one
     */
    private static void joinAll(final List<? extends CompletableFuture<?>> futures)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        unwrap(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)));
    }

    /**
     * Joins {@code future} and, if it completed exceptionally via a {@link
     * CompletionException}, rethrows its cause as the matching checked
     * exception this class's own methods declare (or as-is if already an
     * unchecked {@link RuntimeException}), rather than leaving it wrapped.
     *
     * @param future the future to join
     * @return the future's result
     * @throws DatabaseClientException if the future failed with one
     * @throws KeyWrapException if the future failed with one
     * @throws AuthenticationFailedException if the future failed with one
     */
    private static <T> T unwrap(final CompletableFuture<T> future)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        try {
            return future.join();
        } catch (final CompletionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof DatabaseClientException dce) {
                throw dce;
            }
            if (cause instanceof KeyWrapException kwe) {
                throw kwe;
            }
            if (cause instanceof AuthenticationFailedException afe) {
                throw afe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new DatabaseClientException("@EntityDatabaseClient: unexpected failure", cause);
        }
    }
}
