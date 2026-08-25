package de.lino.cloud.api.file.pending;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Holds every {@link StoredFile} an upload was attempted for while no
 * outbound {@link ConnectivityChecker
 * connectivity} was available, so it can be retried once connectivity
 * returns - the contract backing {@code cloud-driver-plugin}'s
 * {@code ResilientFileFactory} (which enqueues into it) and
 * {@code PendingUploadScheduler} (which periodically drains it). Keyed by
 * {@link StoredFile#fileId()}: enqueuing a file id already pending overwrites
 * the previously queued content rather than duplicating it, the same
 * insert-or-update semantics {@link de.lino.cloud.api.factory.FileFactory#upload}
 * itself has.
 *
 * <p>Implementations must be safe to call concurrently - a file can be
 * enqueued from a caller thread at the same moment the scheduler is draining
 * the cache on its own thread.
 */
public interface PendingUploadCache {

    /**
     * Queues {@code file} for a later retry, overwriting any file already
     * queued under the same {@link StoredFile#fileId()}.
     *
     * @param file the file whose upload could not be attempted or completed
     */
    void enqueue(@NotNull StoredFile file);

    /**
     * Queues every file in {@code files} the same way {@link
     * #enqueue(StoredFile)} queues a single file.
     *
     * @param files the files whose upload could not be attempted or completed
     */
    void enqueue(@NotNull StoredFile... files);

    /**
     * Removes the file queued under {@code fileId}, if any - called once a
     * retried upload succeeds. A no-op if no file is queued under {@code fileId}.
     *
     * @param fileId the {@link StoredFile#fileId()} to remove from the queue
     */
    void remove(@NotNull String fileId);

    /**
     * @return {@code true} if no file is currently queued
     */
    boolean isEmpty();

    /**
     * @return the number of files currently queued
     */
    int size();

    /**
     * A point-in-time copy of every currently queued file, safe to iterate
     * without holding up concurrent {@link #enqueue}/{@link #remove} calls.
     *
     * @return every currently queued file, in no particular guaranteed order
     */
    @NotNull
    List<StoredFile> snapshot();

}
