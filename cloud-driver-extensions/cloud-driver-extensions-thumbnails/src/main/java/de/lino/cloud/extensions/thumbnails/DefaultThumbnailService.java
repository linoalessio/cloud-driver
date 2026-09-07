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

}
