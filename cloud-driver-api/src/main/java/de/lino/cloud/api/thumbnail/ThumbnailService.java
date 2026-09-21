package de.lino.cloud.api.thumbnail;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Resolves a previously generated thumbnail for a {@link StoredFile}, reached via {@link
 * IServiceContainer#getThumbnailService()} - {@code null} until {@code
 * cloud-driver-extensions-thumbnails}'s {@code CloudThumbnailsExtension} has published one (not
 * started, or this deployment doesn't run that extension at all), the same "may not exist yet"
 * contract every other {@code IServiceContainer} facet already carries.
 *
 * <p>Pure lookup only - this interface performs no ownership/access checking of its own (a
 * caller, e.g. {@code DefaultRestFactory}'s {@code GET /files/{id}/thumbnail} route, must check
 * access to {@code storedFileId} itself first, the same way it already does before resolving a
 * file's real content) and never generates a thumbnail on demand - generation happens
 * asynchronously, off the file-upload path, driven by {@code
 * de.lino.cloud.api.event.database.FileChangeListener}. A file with no thumbnail yet (still
 * generating, unsupported content type, or generation failed) is indistinguishable from one that
 * will never have one - {@link Optional#empty()} either way; a client is expected to fall back to
 * a placeholder icon rather than treat this as an error.
 */
public interface ThumbnailService {

    /**
     * @param storedFileId the source {@link StoredFile}'s id
     * @param size the thumbnail size variant to look up
     * @return the generated thumbnail's raw (JPEG-encoded) bytes, or {@link Optional#empty()} if
     *     none exists for {@code storedFileId}/{@code size} - never throws for a genuine miss
     */
    @NotNull
    Optional<byte[]> getThumbnail(@NotNull String storedFileId, @NotNull ThumbnailSize size);

    /**
     * Drops every cached thumbnail of {@code storedFileId}, content included.
     *
     * <p>Serves two callers. A content change must invalidate the preview, or every client keeps
     * showing a thumbnail of bytes that are no longer there - on a shared file, a preview of
     * content its recipient can no longer see. A permanent deletion must collect it, since a
     * thumbnail's image is its own {@code StoredFile} with no ownership row and nothing else will.
     *
     * <p>Best-effort and idempotent: a file with no thumbnail is not an error. Must never throw,
     * so a cleanup failure cannot abort the write or deletion that triggered it.
     *
     * @param storedFileId the file whose thumbnails to drop
     * @return how many thumbnail rows were removed
     */
    int invalidateThumbnails(@NotNull String storedFileId);

    /**
     * Removes every cached thumbnail this extension stores, content included.
     *
     * <p>Exists so a full data wipe can reach this extension's own section: the entity type lives
     * in the extension, so the core cannot name it, and a wipe that silently left it behind would
     * contradict what it tells the operator it does.
     *
     * <p>Best-effort and idempotent. Must never throw.
     */
    void clearAllData();

}
