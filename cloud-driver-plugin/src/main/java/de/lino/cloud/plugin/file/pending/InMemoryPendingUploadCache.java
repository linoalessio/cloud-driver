package de.lino.cloud.plugin.file.pending;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.provider.Caches;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link PendingUploadCache} backed by {@code database-driver-api}'s own
 * {@link Cache} - the same thread-safe, {@code O(1)}-amortized {@link
 * Cache#put}/{@link Cache#invalidate}, non-blocking-{@link Cache#get} async
 * contract {@code EntityDatabaseClient} already builds its read-through
 * caches on (see {@code EntityDatabaseClient#cacheFor}), rather than a
 * hand-rolled map - so a deployment with many files queued at once (the
 * "big data" case: a long outage followed by a large batch of retries) gets
 * the same amortized-{@code O(1)} put/invalidate and full-scan-only-on-{@link
 * #snapshot()} performance characteristics {@link Cache}'s own Javadoc
 * documents, for free.
 *
 * <p>Constructed with no TTL and no size bound: unlike a read-through cache,
 * where a stale or oversize entry is fine to evict (it just gets reloaded),
 * an entry here represents an upload nothing else remembers - it must stay
 * queued indefinitely until an explicit {@link #remove} (a successful retry)
 * takes it out, never expire or get evicted on its own. The {@link Cache}'s
 * loader is consequently unreachable in normal operation: every method this
 * class implements ({@link #enqueue}, {@link #remove}, {@link #isEmpty},
 * {@link #size}, {@link #snapshot}) uses {@link Cache#put}/{@link
 * Cache#invalidate}/{@link Cache#size}/{@link Cache#snapshot} directly and
 * never calls {@link Cache#get} - there is no meaningful value to
 * asynchronously load for a file id that was never enqueued.
 *
 * <p>Process-local and lost on restart, the same "not for production
 * hardening, just makes the feature work" trade-off {@code
 * InMemoryKeyEncryptionService} makes for key material. A deployment that
 * needs queued uploads to survive a restart should provide its own {@link
 * PendingUploadCache} (e.g. persisted to a local file or a database section)
 * instead.
 */
public final class InMemoryPendingUploadCache implements PendingUploadCache {

    private final Cache<String, StoredFile> cache =
            Caches.newCache(InMemoryPendingUploadCache::unreachableLoader, null, -1);

    @Override
    public void enqueue(@NotNull final StoredFile file) {
        Asserts.assertNotNull(file, "@InMemoryPendingUploadCache.enqueue: file cannot be null");
        this.cache.put(file.fileId(), file);
    }

    @Override
    public void enqueue(@NotNull final StoredFile... files) {
        Asserts.assertNotNull(files, "@InMemoryPendingUploadCache.enqueue: files cannot be null");
        for (final StoredFile file : files) {
            enqueue(file);
        }
    }

    @Override
    public void remove(@NotNull final String fileId) {
        Asserts.assertNotNull(fileId, "@InMemoryPendingUploadCache.remove: fileId cannot be null");
        this.cache.invalidate(fileId);
    }

    @Override
    public boolean isEmpty() {
        return this.cache.size() == 0;
    }

    @Override
    public int size() {
        return this.cache.size();
    }

    @NotNull
    @Override
    public List<StoredFile> snapshot() {
        return List.copyOf(this.cache.snapshot().values());
    }

    /**
     * Never actually invoked - see the class Javadoc's "loader is
     * unreachable" note. Fails loudly instead of e.g. returning {@code null}
     * so a future caller who mistakenly starts calling {@link Cache#get} on
     * this cache gets an immediate, clear signal rather than a silent
     * {@code null}.
     */
    private static CompletableFuture<StoredFile> unreachableLoader(final String fileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "@InMemoryPendingUploadCache: no loader is configured - a file only ever enters this cache via enqueue()"
        ));
    }

}
