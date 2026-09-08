package de.lino.cloud.api.versioning;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Keeps prior versions of a {@link StoredFile}'s content around whenever it's overwritten in
 * place (via {@code de.lino.cloud.auth.CloudUserService#replaceFileContent}), reached via {@link
 * IServiceContainer#getFileVersioningService()} - {@code null} until {@code
 * cloud-driver-extensions-versioning}'s {@code CloudVersioningExtension} has published one (not
 * started, or this deployment doesn't run that extension at all), the same "may not exist yet"
 * contract every other {@code IServiceContainer} facet already carries.
 *
 * <p>A new {@code replaceFileContent}
 * primitive had to be added to {@code CloudUserService} first (this app had no "overwrite a
 * file's content" operation at all before this feature).
 */
public interface FileVersioningService {

    /**
     * Captures {@code previousContent} as a new version of {@code sourceFileId}, called
     * synchronously by {@code CloudUserService#replaceFileContent} <b>before</b> the new content
     * is written - the only point at which the about-to-be-overwritten content is still
     * available, since {@code StoredFile} is overwritten in place under the same id (there is no
     * async "after write" notification that could still see the old content by the time it
     * fires). Must never throw - the caller wraps this in a try/catch regardless (defense in
     * depth), but a well-behaved implementation should never let a version-capture failure block
     * the real content replacement it's capturing history for.
     *
     * @param sourceFileId the {@link StoredFile#fileId()} about to be overwritten
     * @param previousContent the file's full state immediately before the overwrite
     */
    void captureVersion(@NotNull String sourceFileId, @NotNull StoredFile previousContent);

    /**
     * Lists every currently-retained version of {@code sourceFileId}, oldest first (ascending
     * {@link FileVersionSummary#versionNumber()}) - a version pruned by this deployment's
     * retention policy simply isn't in the result, with no indication one ever existed.
     *
     * @param sourceFileId the file to list versions of
     * @return every retained version, oldest first, or an empty list if none are retained
     */
    @NotNull
    List<FileVersionSummary> listVersions(@NotNull String sourceFileId);

    /**
     * Resolves one specific version's full content, plus enough descriptive metadata to serve it
     * as a real download.
     *
     * @param sourceFileId the source file the version belongs to
     * @param versionNumber the version to resolve
     * @return the version's content and metadata, or {@link Optional#empty()} if no such version is currently retained
     */
    @NotNull
    Optional<FileVersionContent> getVersionContent(@NotNull String sourceFileId, int versionNumber);

}
