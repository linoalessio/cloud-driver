package de.lino.cloud.extensions.versioning;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.versioning.FileVersionContent;
import de.lino.cloud.api.versioning.FileVersionSummary;
import de.lino.cloud.api.versioning.FileVersioningService;
import lombok.NonNull;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The one {@link FileVersioningService} implementation - see that interface's own Javadoc for the full contract. */
final class DefaultFileVersioningService implements FileVersioningService {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final Logger logger;

    DefaultFileVersioningService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory, @NonNull final Logger logger) {
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
        this.logger = logger;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Never throws - a failure to capture a version must never block the real content
     * replacement it's capturing history for (see this interface's own Javadoc), so any failure
     * here is caught and logged.
     */
    @Override
    public void captureVersion(@NonNull final String sourceFileId, @NonNull final StoredFile previousContent) {
        try {
            final int nextVersionNumber = this.retainedVersions(sourceFileId).size() + 1;
            final String versionedFileId = UUID.randomUUID().toString();

            this.fileFactory.upload(new StoredFile(versionedFileId, previousContent.fileName(), previousContent.content()));
            this.dataFactory.register(new FileVersion(
                    sourceFileId, nextVersionNumber, versionedFileId,
                    previousContent.fileName(), previousContent.contentType(),
                    previousContent.sizeBytes(), previousContent.checksum(), System.currentTimeMillis()
            ));
        } catch (final DatabaseClientException | KeyWrapException e) {
            this.logger.log(Level.WARNING, "@DefaultFileVersioningService.captureVersion: failed to capture a version of file '" + sourceFileId + "'", e);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public List<FileVersionSummary> listVersions(@NonNull final String sourceFileId) {
        return this.retainedVersions(sourceFileId).stream()
                .map(version -> new FileVersionSummary(version.getVersionNumber(), version.getCapturedAtEpochMillis(), version.getSizeBytes()))
                .toList();
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Optional<FileVersionContent> getVersionContent(@NonNull final String sourceFileId, final int versionNumber) {
        try {
            final Optional<FileVersion> version = this.dataFactory.findById(
                    FileVersion.compositeKey(sourceFileId, versionNumber), FileVersion.class);
            if (version.isEmpty()) return Optional.empty();
            final FileVersion row = version.get();
            return this.fileFactory.findById(row.getVersionedFileId())
                    .map(file -> new FileVersionContent(row.getFileName(), row.getContentType(), file.content()));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            this.logger.log(Level.WARNING, "@DefaultFileVersioningService.getVersionContent: failed to resolve version "
                    + versionNumber + " of file '" + sourceFileId + "'", e);
            return Optional.empty();
        }
    }

    /**
     * Every currently-retained {@link FileVersion} row for {@code sourceFileId}, oldest first -
     * the full-scan-and-filter this class's own Javadoc documents as an accepted trade-off. A
     * failed scan is reported as "no versions" rather than thrown, since every caller of this
     * private helper already treats an empty result as a normal, valid outcome.
     */
    private List<FileVersion> retainedVersions(final String sourceFileId) {
        try {
            return this.dataFactory.getEntities(FileVersion.class).stream()
                    .filter(version -> version.getSourceFileId().equals(sourceFileId))
                    .sorted(Comparator.comparingInt(FileVersion::getVersionNumber))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            this.logger.log(Level.WARNING, "@DefaultFileVersioningService.retainedVersions: failed to scan versions of file '" + sourceFileId + "'", e);
            return List.of();
        }
    }

}
