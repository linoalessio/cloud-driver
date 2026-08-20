package de.lino.cloud.plugin.security.database;

import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.database.EncryptedEntityRecord;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.task.MultiTaskingFactory;
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
import de.lino.cloud.api.utility.Asserts;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists and retrieves {@link Serialized} domain entities in {@link
 * DatabaseSection}s of a {@link DatabaseProvider} from the {@code
 * database-driver-api}/{@code database-driver-plugin} stack: each meta is
 * envelope-encrypted via {@link SecureEntityChannel} before it is written,
 * then stored as an {@link EncryptedEntityRecord} JSON document under its
 * {@link Serialized#primaryKey() primary key} (section 9, DATA AT REST).
 *
 * <p>Entities are routed to a section per meta type - see {@link
 * #sectionFor}, resolved lazily and cached the same way the read-through
 * caches below are, so callers work against a single client for every meta
 * type instead of wiring up one client per {@link DatabaseSection}.
 *
 * <p>This is the one class in the driver that actually performs I/O against
 * the configured database; every other class in {@code security} only
 * prepares data for that persistence.
 *
 * <p>{@link #store} and {@link #retrieve} are generic per call, not per
 * instance, so a single client handles heterogeneous meta types - the
 * shape needed by a global facade such as {@code CloudAPI}.
 *
 * <p><b>Concurrency.</b> Instances are stateless beyond the internal caches
 * (below) and are safe to share and call from multiple threads. {@link
 * #store} inserts first and only falls back to an update on collision,
 * rather than checking {@code exists()} first and branching on the result -
 * a check-then-act would leave a race window in which a concurrent {@link
 * #store} of the same id, between the check and the write, could make the
 * check stale by the time this call actually writes. Batch operations ({@link
 * #storeAll}, {@link #retrieveAll}) dispatch each meta/id concurrently on
 * {@link MultiTaskingFactory}'s shared virtual-thread executor, appropriate
 * here because encrypting/decrypting and writing/reading one meta is
 * independent, I/O-bound work with respect to every other meta in the same
 * batch - the dominant cost at scale ("big data" batches) is waiting on the
 * database and KMS, not CPU, which is exactly the workload virtual threads
 * are suited for.
 *
 * <p><b>Caching.</b> Each requested meta type gets its own read-through,
 * write-through {@link Cache}, created lazily. A cache hit skips the KMS
 * unwrap and AES-256-GCM decrypt entirely. Entries are short-lived and
 * size-bounded by default (see {@link #DEFAULT_CACHE_TTL}/{@link
 * #DEFAULT_CACHE_MAX_SIZE}) deliberately: unlike the database, which only
 * ever holds ciphertext, this cache holds decrypted plaintext in process
 * memory (section 9, DATA AT REST - minimize how long and how widely
 * plaintext exists). Tune or disable via the second constructor if that
 * trade-off is wrong for a given deployment.
 */
public final class EntityDatabaseClient {

    private static final String DATA_KEY = "data";
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);
    private static final long DEFAULT_CACHE_MAX_SIZE = 1_000;

    private final DatabaseProvider databaseProvider;
    private final SecureEntityChannel secureEntityChannel;
    private final Duration cacheTtl;
    private final long cacheMaxSize;

    /**
     * One cache per requested meta type, created on first use via {@link
     * #cacheFor}. A {@code ConcurrentHashMap} so concurrent first-uses of
     * different types never contend, and {@code computeIfAbsent} guarantees
     * at most one {@link Cache} is ever created per type even under
     * concurrent access.
     */
    private final Map<Class<? extends Serialized>, Cache<String, ? extends Serialized>> caches = new ConcurrentHashMap<>();

    /**
     * One {@link DatabaseSection} per requested meta type, created on first
     * use via {@link #sectionFor}. Same {@code ConcurrentHashMap} +
     * {@code computeIfAbsent} reasoning as {@link #caches}.
     */
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
        this.databaseProvider = Asserts.assertNotNull(databaseProvider, "@EntityDatabaseClient: databaseProvider cannot be null");
        this.secureEntityChannel = new SecureEntityChannel(
                Asserts.assertNotNull(envelopeEncryptionService, "@EntityDatabaseClient: envelopeEncryptionService cannot be null")
        );
        this.cacheTtl = cacheTtl;
        this.cacheMaxSize = cacheMaxSize;
    }

    /**
     * The {@link DatabaseSection} entities of {@code type} are stored in,
     * named after {@code type}'s simple class name, created lazily on the
     * backing {@link DatabaseProvider} the first time {@code type} is seen
     * and reused (via {@link #sections}) on every call after that - so each
     * meta type gets its own section without the caller having to create
     * or wire one up manually.
     */
    private DatabaseSection sectionFor(final Class<?> type) {
        return sections.computeIfAbsent(type, key -> {
            final String sectionName = key.getSimpleName();
            return databaseProvider.getSection(sectionName).orElseGet(() -> databaseProvider.createSection(sectionName));
        });
    }

    /**
     * Encrypts {@code meta} and inserts or updates it in the database
     * under its {@link Serialized#primaryKey()}.
     */
    public <T extends Serialized> void store(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        Asserts.assertNotNull(entity, "@EntityDatabaseClient.store: meta cannot be null");

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

    @SuppressWarnings("unchecked") // safe: meta's own runtime type is always a valid Class<T> for meta itself
    private <T extends Serialized> void cachePut(final T entity) {
        final Class<T> type = (Class<T>) entity.getClass();
        cacheFor(type).put(entity.primaryKey(), entity);
    }

    /**
     * Encrypts {@code meta} and overwrites the existing database record
     * under its {@link Serialized#primaryKey()}. Unlike {@link #store},
     * which inserts-or-updates, this fails - via the underlying {@link
     * DatabaseSection#update}'s {@code NoSuchEntryFound} - if no such record
     * exists yet.
     */
    public <T extends Serialized> void update(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        Asserts.assertNotNull(entity, "@EntityDatabaseClient.update: meta cannot be null");

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
     * Encrypts and overwrites the existing database record of every meta
     * in {@code entities}, the same way {@link #update} overwrites a single
     * meta, dispatched concurrently (see the class Javadoc). The same
     * partial-failure semantics as {@link #storeAll} apply.
     */
    public <T extends Serialized> void updateAll(@NotNull final List<T> entities) throws DatabaseClientException, KeyWrapException {
        Asserts.assertNotNull(entities, "@EntityDatabaseClient.updateAll: entities cannot be null");

        final List<CompletableFuture<Void>> futures = entities.stream()
                .map(entity -> MultiTaskingFactory.getInstance().runAsync(() -> updateUnchecked(entity)))
                .toList();

        // update() never decrypts anything, so it can never actually fail
        // authentication - joinAllStore keeps that reflected in this
        // method's throws clause instead of claiming AuthenticationFailedException.
        joinAllStore(futures);
    }

    private <T extends Serialized> void updateUnchecked(final T entity) {
        try {
            update(entity);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Encrypts and stores every meta in {@code entities}, the same way
     * {@link #store} stores a single meta, dispatched concurrently (see
     * the class Javadoc). Unlike a sequential loop, a failure part-way
     * through does not prevent the remaining entities from being attempted -
     * they are already running concurrently by the time any one of them
     * fails. The first failure encountered is what this method throws, once
     * every meta in the batch has been attempted.
     */
    public <T extends Serialized> void storeAll(@NotNull final List<T> entities) throws DatabaseClientException, KeyWrapException {
        Asserts.assertNotNull(entities, "@EntityDatabaseClient.storeAll: entities cannot be null");

        final List<CompletableFuture<Void>> futures = entities.stream()
                .map(entity -> MultiTaskingFactory.getInstance().runAsync(() -> storeUnchecked(entity)))
                .toList();

        // store() never decrypts anything, so it can never actually fail
        // authentication - joinAllStore keeps that reflected in this
        // method's throws clause instead of claiming AuthenticationFailedException.
        joinAllStore(futures);
    }

    private <T extends Serialized> void storeUnchecked(final T entity) {
        try {
            store(entity);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Fetches the meta stored under {@code objectId} and decrypts it back
     * into an instance of {@code type} - from the type's cache if present
     * and not expired, otherwise from the database, verifying the
     * authentication tag before returning any plaintext.
     */
    @NotNull
    public <T extends Serialized> T retrieve(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.assertNotNull(objectId, "@EntityDatabaseClient.retrieve: objectId cannot be null");
        Asserts.assertNotNull(type, "@EntityDatabaseClient.retrieve: type cannot be null");

        return unwrap(cacheFor(type).get(objectId));
    }

    /**
     * Fetches and decrypts every meta stored under {@code objectIds}, in
     * the same order, the same way {@link #retrieve} fetches a single
     * meta, dispatched concurrently (see the class Javadoc) - each id's
     * cache lookup/database read is independent of every other id's.
     */
    @NotNull
    public <T extends Serialized> List<T> retrieveAll(@NotNull final List<String> objectIds, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.assertNotNull(objectIds, "@EntityDatabaseClient.retrieveAll: objectIds cannot be null");
        Asserts.assertNotNull(type, "@EntityDatabaseClient.retrieveAll: type cannot be null");

        final Cache<String, T> cache = cacheFor(type);
        final List<CompletableFuture<T>> futures = objectIds.stream().map(cache::get).toList();

        joinAll(futures);
        // Every future is already complete at this point (joinAll waited on
        // all of them), so these joins return immediately.
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Looks up the meta stored under {@code objectId} the same way {@link
     * #retrieve} does (cache first, then database), but returns {@link
     * Optional#empty()} instead of throwing when no such meta exists.
     *
     * <p>Optimized for the cache-hit path, which is expected to be the
     * common case: it always attempts {@link #retrieve} first, so a hit
     * costs exactly what {@link #retrieve} already costs, with no extra
     * lookup. Only on a {@link DatabaseClientException} does it pay one
     * extra, cheap {@link DatabaseSection#exists} check to tell a genuine
     * miss apart from a corrupted record - so an id that turns out to be
     * confirmed-present-but-broken is rethrown rather than silently
     * swallowed into {@code empty()}.
     */
    @NotNull
    public <T extends Serialized> Optional<T> findById(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.assertNotNull(objectId, "@EntityDatabaseClient.findById: objectId cannot be null");
        Asserts.assertNotNull(type, "@EntityDatabaseClient.findById: type cannot be null");

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
     * Retrieves and decrypts every meta of {@code type} currently stored
     * in its database section - every id currently present in the section,
     * decrypted the same way {@link #retrieve} decrypts a single id,
     * dispatched concurrently via {@link #retrieveAll} (see the class
     * Javadoc) since decrypting one meta is independent of every other.
     */
    @NotNull
    public <T extends Serialized> List<T> getEntities(@NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Asserts.assertNotNull(type, "@EntityDatabaseClient.getEntities: type cannot be null");

        final List<String> objectIds = sectionFor(type).getEntries().stream().map(DatabaseEntry::getId).toList();
        return retrieveAll(objectIds, type);
    }

    /**
     * Deletes the meta stored under {@code objectId} from the {@code type}
     * database section and evicts it from {@code type}'s cache. Deleting a
     * nonexistent id is treated as a failure, not a silent no-op, matching
     * {@link #retrieve}'s behavior for an unknown id.
     */
    public <T extends Serialized> void delete(@NotNull final String objectId, @NotNull final Class<T> type) throws DatabaseClientException {
        Asserts.assertNotNull(objectId, "@EntityDatabaseClient.delete: objectId cannot be null");
        Asserts.assertNotNull(type, "@EntityDatabaseClient.delete: type cannot be null");

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
     * Deletes every meta stored under {@code objectIds} from the {@code
     * type} database section, the same way {@link #delete} deletes a single
     * id, dispatched concurrently (see the class Javadoc). The same
     * partial-failure semantics as {@link #storeAll} apply.
     */
    public <T extends Serialized> void deleteAll(@NotNull final List<String> objectIds, @NotNull final Class<T> type) throws DatabaseClientException {
        Asserts.assertNotNull(objectIds, "@EntityDatabaseClient.deleteAll: objectIds cannot be null");
        Asserts.assertNotNull(type, "@EntityDatabaseClient.deleteAll: type cannot be null");

        final List<CompletableFuture<Void>> futures = objectIds.stream()
                .map(objectId -> MultiTaskingFactory.getInstance().runAsync(() -> deleteUnchecked(objectId, type)))
                .toList();

        joinAllDelete(futures);
    }

    private <T extends Serialized> void deleteUnchecked(final String objectId, final Class<T> type) {
        try {
            delete(objectId, type);
        } catch (final DatabaseClientException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Clears every entry from the {@code type} database section, leaving the
     * section itself intact, and evicts {@code type}'s cache (if one has
     * been created yet) so no stale decrypted meta is served afterward -
     * use {@link #deleteSection} instead to remove the section itself.
     */
    public <T extends Serialized> void clear(@NotNull final Class<T> type) {
        Asserts.assertNotNull(type, "@EntityDatabaseClient.clear: type cannot be null");

        sectionFor(type).clear();
        final Cache<String, ? extends Serialized> cache = caches.get(type);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * Deletes the {@code type} database section entirely - not just its
     * entries - and discards {@code type}'s cached {@link DatabaseSection}
     * reference and cache (if either has been created yet). A later
     * operation on {@code type} lazily recreates both, the same way {@link
     * #sectionFor} already does for any meta type it has not seen before.
     */
    public <T extends Serialized> void deleteSection(@NotNull final Class<T> type) {
        Asserts.assertNotNull(type, "@EntityDatabaseClient.deleteSection: type cannot be null");

        this.databaseProvider.deleteSection(type.getSimpleName());
        this.sections.remove(type);
        this.caches.remove(type);
    }

    @SuppressWarnings("unchecked") // safe: every cache is both created and looked up keyed by the same Class<T>
    private <T extends Serialized> Cache<String, T> cacheFor(final Class<T> type) {
        return (Cache<String, T>) caches.computeIfAbsent(
                type, key -> Caches.<String, T>newCache(id -> loadFromDatabaseAsync(id, type), cacheTtl, cacheMaxSize)
        );
    }

    private <T extends Serialized> CompletableFuture<T> loadFromDatabaseAsync(final String objectId, final Class<T> type) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> loadFromDatabase(objectId, type));
    }

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

    private static void joinAllStore(final List<CompletableFuture<Void>> futures) throws DatabaseClientException, KeyWrapException {
        try {
            joinAll(futures);
        } catch (final AuthenticationFailedException impossible) {
            throw new IllegalStateException(
                    "@EntityDatabaseClient: unexpected authentication failure while storing (store() never decrypts)", impossible
            );
        }
    }

    private static void joinAllDelete(final List<CompletableFuture<Void>> futures) throws DatabaseClientException {
        try {
            joinAll(futures);
        } catch (final KeyWrapException | AuthenticationFailedException impossible) {
            throw new IllegalStateException(
                    "@EntityDatabaseClient: unexpected key-wrap/authentication failure while deleting (delete() never touches keys)", impossible
            );
        }
    }

    private static void joinAll(final List<? extends CompletableFuture<?>> futures)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        unwrap(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)));
    }

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
