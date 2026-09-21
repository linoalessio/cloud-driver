package de.lino.cloud.extensions.thumbnails;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.thumbnail.ThumbnailService;
import de.lino.cloud.api.thumbnail.ThumbnailSize;
import lombok.NonNull;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one {@link ThumbnailService} implementation - pure lookup, no ownership/access checking of
 * its own (see that interface's own Javadoc for why) and never generates on demand; a miss here
 * (either the {@link FileThumbnail} row doesn't exist, or its referenced thumbnail {@code
 * StoredFile} does not - the latter should never happen in practice, since both are only ever
 * written together by {@link CloudThumbnailsExtension}, but is handled the same way regardless)
 * is reported as {@link Optional#empty()}.
 */
final class DefaultThumbnailService implements ThumbnailService {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final Logger logger;

    DefaultThumbnailService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory, @NonNull final Logger logger) {
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
        this.logger = logger;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A genuine persistence failure (as opposed to a plain miss) is caught and logged, then
     * reported as {@link Optional#empty()} too - this method has no checked-exception contract of
     * its own to surface it through, and a caller (e.g. {@code DefaultRestFactory}'s {@code GET
     * /files/{id}/thumbnail} route) treats a miss and a resolution failure identically anyway (a
     * plain {@code 404}), matching {@link #getThumbnail}'s own Javadoc.
     */
    @Override
    public Optional<byte[]> getThumbnail(@NonNull final String storedFileId, @NonNull final ThumbnailSize size) {
        try {
            final Optional<FileThumbnail> thumbnail = this.dataFactory.findById(
                    FileThumbnail.compositeKey(storedFileId, size.name()), FileThumbnail.class);
            if (thumbnail.isEmpty()) {
                return Optional.empty();
            }
            return this.fileFactory.findById(thumbnail.get().getThumbnailFileId()).map(file -> file.content());
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            this.logger.log(Level.WARNING, "@DefaultThumbnailService.getThumbnail: failed to resolve thumbnail for file '" + storedFileId + "'", e);
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Walks every {@link ThumbnailSize}, dropping the thumbnail's own {@code StoredFile} before
     * its {@link FileThumbnail} row, so a failure part-way through leaves a row pointing at
     * nothing (regenerable) rather than content nothing points at (unreachable forever).
     */
    @Override
    public int invalidateThumbnails(@NonNull final String storedFileId) {
        int removed = 0;
        for (final ThumbnailSize size : ThumbnailSize.values()) {
            final String key = FileThumbnail.compositeKey(storedFileId, size.name());
            final Optional<FileThumbnail> thumbnail;
            try {
                thumbnail = this.dataFactory.findById(key, FileThumbnail.class);
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException lookupFailed) {
                this.logger.log(Level.WARNING,
                        "@DefaultThumbnailService.invalidateThumbnails: failed to look up the " + size + " thumbnail of '" + storedFileId + "'",
                        lookupFailed);
                continue;
            }
            if (thumbnail.isEmpty()) {
                continue;
            }
            try {
                this.fileFactory.delete(thumbnail.get().getThumbnailFileId());
            } catch (final DatabaseClientException | RuntimeException alreadyGoneOrOther) {
                // Drop the row regardless: a thumbnail row pointing at missing content is simply
                // regenerated, where a surviving row would suppress regeneration forever.
            }
            try {
                this.dataFactory.delete(key, FileThumbnail.class);
                removed++;
            } catch (final DatabaseClientException | RuntimeException alreadyGone) {
                // nothing left to do
            }
        }
        return removed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Drops the whole {@link FileThumbnail} section in one call, rather than walking it - a wipe
     * is not a per-row operation, and this runs while everything else is being torn down too.
     */
    @Override
    public void clearAllData() {
        try {
            this.dataFactory.deleteSection(FileThumbnail.class);
        } catch (final RuntimeException wipeFailed) {
            this.logger.log(Level.WARNING, "Failed to clear the stored thumbnails", wipeFailed);
        }
    }

}
