package de.lino.cloud.plugin.file;

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
 * {@link Cache}, with no TTL/size bound - an entry stays queued until an
 * explicit {@link #remove}, never expires on its own. Process-local and
 * lost on restart, the same trade-off {@code InMemoryKeyEncryptionService}
 * makes for key material.
 */
public final class InMemoryPendingUploadCache implements PendingUploadCache {

    private final Cache<String, StoredFile> cache =
            Caches.newCache(InMemoryPendingUploadCache::unreachableLoader, null, -1);

    /**
     * Queues {@code file} for upload, overwriting any previously queued content for the same id.
     *
     * @param file the file to queue
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @Override
    public void enqueue(@NotNull final StoredFile file) {
        Asserts.requireNonNull(file, "@InMemoryPendingUploadCache.enqueue: file cannot be null");
        this.cache.put(file.fileId(), file);
    }

    /**
     * Queues every file in {@code files}.
     *
     * @param files the files to queue
     * @throws NullPointerException if {@code files} is {@code null}
     */
    @Override
    public void enqueue(@NotNull final StoredFile... files) {
        Asserts.requireNonNull(files, "@InMemoryPendingUploadCache.enqueue: files cannot be null");
        for (final StoredFile file : files) {
            enqueue(file);
        }
    }

    /**
     * Removes {@code fileId} from the queue, e.g. after a successful retry.
     *
     * @param fileId the id to remove
     * @throws NullPointerException if {@code fileId} is {@code null}
     */
    @Override
    public void remove(@NotNull final String fileId) {
        Asserts.requireNonNull(fileId, "@InMemoryPendingUploadCache.remove: fileId cannot be null");
        this.cache.invalidate(fileId);
    }

    /** @return {@code true} if nothing is currently queued */
    @Override
    public boolean isEmpty() {
        return this.cache.size() == 0;
    }

    /** @return the number of files currently queued */
    @Override
    public int size() {
        return this.cache.size();
    }

    /** @return an immutable snapshot of every currently queued file */
    @NotNull
    @Override
    public List<StoredFile> snapshot() {
        return List.copyOf(this.cache.snapshot().values());
    }

    /**
     * Never actually invoked - a file only ever enters this cache via
     * {@link #enqueue}. Fails loudly rather than returning {@code null}, in
     * case a future caller mistakenly calls {@link Cache#get} on this cache.
     */
    private static CompletableFuture<StoredFile> unreachableLoader(final String fileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "@InMemoryPendingUploadCache: no loader is configured - a file only ever enters this cache via enqueue()"
        ));
    }

}
