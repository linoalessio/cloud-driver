package de.lino.cloud.core.database;

import de.lino.cloud.api.database.DatabaseClientException;
import de.lino.cloud.api.database.EncryptedEntityRecord;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.task.MultiTaskingFactory;
import de.lino.cloud.core.security.entity.SecureEntityChannel;
import de.lino.cloud.core.security.envelope.EnvelopeEncryptionService;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists and retrieves {@link Serialized} domain entities in a {@link
 * DatabaseSection} of the {@code database-driver-api}/{@code
 * database-driver-plugin} stack: each entity is envelope-encrypted via
 * {@link SecureEntityChannel} before it is written, then stored as an
 * {@link EncryptedEntityRecord} JSON document under its {@link
 * Serialized#primaryKey() primary key} (section 9, DATA AT REST).
 *
 * <p>This is the one class in the driver that actually performs I/O against
 * the configured database; every other class in {@code security} only
 * prepares data for that persistence.
 *
 * <p>{@link #store} and {@link #retrieve} are generic per call, not per
 * instance, so a single client handles heterogeneous entity types - the
 * shape needed by a global facade such as {@code CloudAPI}.
 *
 * <p><b>Concurrency.</b> Instances are stateless beyond the internal caches
 * (below) and are safe to share and call from multiple threads. {@link
 * #store} inserts first and only falls back to an update on collision,
 * rather than checking {@code exists()} first and branching on the result -
 * a check-then-act would leave a race window in which a concurrent {@link
 * #store} of the same id, between the check and the write, could make the
 * check stale by the time this call actually writes. Batch operations ({@link
 * #storeAll}, {@link #retrieveAll}) dispatch each entity/id concurrently on
 * {@link MultiTaskingFactory}'s shared virtual-thread executor, appropriate
 * here because encrypting/decrypting and writing/reading one entity is
 * independent, I/O-bound work with respect to every other entity in the same
 * batch - the dominant cost at scale ("big data" batches) is waiting on the
 * database and KMS, not CPU, which is exactly the workload virtual threads
 * are suited for.
 *
 * <p><b>Caching.</b> Each requested entity type gets its own read-through,
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

    private final DatabaseSection databaseSection;
    private final SecureEntityChannel secureEntityChannel;
    private final Duration cacheTtl;
    private final long cacheMaxSize;

    /**
     * One cache per requested entity type, created on first use via {@link
     * #cacheFor}. A {@code ConcurrentHashMap} so concurrent first-uses of
     * different types never contend, and {@code computeIfAbsent} guarantees
     * at most one {@link Cache} is ever created per type even under
     * concurrent access.
     */
    private final Map<Class<? extends Serialized>, Cache<String, ? extends Serialized>> caches = new ConcurrentHashMap<>();

    public EntityDatabaseClient(@NotNull final DatabaseSection databaseSection,
                                 @NotNull final EnvelopeEncryptionService envelopeEncryptionService) {
        this(databaseSection, envelopeEncryptionService, DEFAULT_CACHE_TTL, DEFAULT_CACHE_MAX_SIZE);
    }

    /**
     * @param cacheTtl how long a decrypted entity stays cached; {@code null} for unbounded
     * @param cacheMaxSize maximum cached entries per entity type; {@code <= 0} for unbounded
     */
    public EntityDatabaseClient(@NotNull final DatabaseSection databaseSection,
                                 @NotNull final EnvelopeEncryptionService envelopeEncryptionService,
                                 final Duration cacheTtl, final long cacheMaxSize) {
        this.databaseSection = Objects.requireNonNull(databaseSection, "@EntityDatabaseClient: databaseSection cannot be null");
        this.secureEntityChannel = new SecureEntityChannel(
                Objects.requireNonNull(envelopeEncryptionService, "@EntityDatabaseClient: envelopeEncryptionService cannot be null")
        );
        this.cacheTtl = cacheTtl;
        this.cacheMaxSize = cacheMaxSize;
    }

    /**
     * Encrypts {@code entity} and inserts or updates it in the database
     * under its {@link Serialized#primaryKey()}.
     */
    public <T extends Serialized> void store(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
        Objects.requireNonNull(entity, "@EntityDatabaseClient.store: entity cannot be null");

        final EnvelopeEncryptedPayload envelope = secureEntityChannel.send(entity);
        final JsonDocument document = new JsonDocument().append(DATA_KEY, EncryptedEntityRecord.from(envelope));
        final DatabaseEntry entry = new DatabaseEntry(entity.primaryKey(), document);

        try {
            databaseSection.insert(entry);
        } catch (final DataAlreadyExist alreadyExists) {
            try {
                databaseSection.update(entry);
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

    @SuppressWarnings("unchecked") // safe: entity's own runtime type is always a valid Class<T> for entity itself
    private <T extends Serialized> void cachePut(final T entity) {
        final Class<T> type = (Class<T>) entity.getClass();
        cacheFor(type).put(entity.primaryKey(), entity);
    }

    /**
     * Encrypts and stores every entity in {@code entities}, the same way
     * {@link #store} stores a single entity, dispatched concurrently (see
     * the class Javadoc). Unlike a sequential loop, a failure part-way
     * through does not prevent the remaining entities from being attempted -
     * they are already running concurrently by the time any one of them
     * fails. The first failure encountered is what this method throws, once
     * every entity in the batch has been attempted.
     */
    public <T extends Serialized> void storeAll(@NotNull final List<T> entities) throws DatabaseClientException, KeyWrapException {
        Objects.requireNonNull(entities, "@EntityDatabaseClient.storeAll: entities cannot be null");

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
     * Fetches the entity stored under {@code objectId} and decrypts it back
     * into an instance of {@code type} - from the type's cache if present
     * and not expired, otherwise from the database, verifying the
     * authentication tag before returning any plaintext.
     */
    public <T extends Serialized> T retrieve(@NotNull final String objectId, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Objects.requireNonNull(objectId, "@EntityDatabaseClient.retrieve: objectId cannot be null");
        Objects.requireNonNull(type, "@EntityDatabaseClient.retrieve: type cannot be null");

        return unwrap(cacheFor(type).get(objectId));
    }

    /**
     * Fetches and decrypts every entity stored under {@code objectIds}, in
     * the same order, the same way {@link #retrieve} fetches a single
     * entity, dispatched concurrently (see the class Javadoc) - each id's
     * cache lookup/database read is independent of every other id's.
     */
    public <T extends Serialized> List<T> retrieveAll(@NotNull final List<String> objectIds, @NotNull final Class<T> type)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        Objects.requireNonNull(objectIds, "@EntityDatabaseClient.retrieveAll: objectIds cannot be null");
        Objects.requireNonNull(type, "@EntityDatabaseClient.retrieveAll: type cannot be null");

        final Cache<String, T> cache = cacheFor(type);
        final List<CompletableFuture<T>> futures = objectIds.stream().map(cache::get).toList();

        joinAll(futures);
        // Every future is already complete at this point (joinAll waited on
        // all of them), so these joins return immediately.
        return futures.stream().map(CompletableFuture::join).toList();
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
            entry = databaseSection.findEntryById(objectId);
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
