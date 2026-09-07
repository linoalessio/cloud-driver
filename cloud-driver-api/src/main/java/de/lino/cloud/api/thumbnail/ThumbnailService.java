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

}
