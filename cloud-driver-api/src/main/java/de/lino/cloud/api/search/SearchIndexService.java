package de.lino.cloud.api.search;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A derived, per-account, filename-plus-text-content search index over {@link StoredFile}s -
 * section 5 of {@code architecture/MICRO.md}, reached via {@link IServiceContainer#getSearchIndexService()}
 * - {@code null} until {@code cloud-driver-extensions-search}'s {@code CloudSearchExtension} has
 * published one (not started, or this deployment doesn't run that extension at all), the same
 * "may not exist yet" contract every other {@link IServiceContainer} facet already carries.
 *
 * <p><b>Never a second source of truth</b> - every method here is called synchronously, directly
 * from {@code de.lino.cloud.auth.CloudUserService}'s own upload/rename/move/content-replace/delete
 * methods (the same "reach the optional service directly from the call site, no-op if unpublished,
 * never let a failure here block the real operation" shape {@code FileVersioningService}/{@code
 * MetricsRecorder} already use), never from an async watch-event - see {@code
 * architecture/MICRO.md}'s own "Reconciliation Notes" on why {@code FileChangeListener} (built for
 * section 1) isn't the right mechanism here: it only fires on an {@code INSERT}/{@code UPDATE} of
 * the {@code StoredFile} table, which misses a soft-delete/restore (an {@code UPDATE} of {@code
 * StoredFileOwnership}, a different table entirely) and a genuine hard delete (no notification at
 * all) - both of which this index must react to correctly. Since it's fully derived from data
 * {@code CloudUserService} already owns, an implementation is free to be lost on restart (see
 * {@code InMemorySearchIndexService}'s own Javadoc) - there is nothing here that can't be rebuilt.
 *
 * <p><b>Scoped to a single account, per file/folder, never across accounts</b> - {@link
 * #search(String, String, int)} only ever matches documents indexed under the same {@code
 * authUserId} passed to it. A grantee reading a shared file does not see it in their own search
 * results (out of scope for v1, per the doc's own instruction not to over-engineer this pass -
 * a natural follow-up, not a bug).
 */
public interface SearchIndexService {

    /**
     * Indexes (or re-indexes, replacing any previously indexed state for the same {@code
     * storedFileId}) one file - called by {@code CloudUserService#uploadFile} right after a
     * successful upload, and by {@code #replaceFileContent} after a successful content
     * replacement, both of which already have the raw content in hand to extract text from. Must
     * never throw - the caller wraps every call in a try/catch regardless (defense in depth), but
     * a well-behaved implementation should never let an indexing failure block the real upload/
     * replace it's indexing.
     *
     * @param document the file's current searchable state
     */
    void indexFile(@NotNull SearchDocument document);

    /**
     * Updates only {@code fileName}/{@code folderId} for an already-indexed file - called by
     * {@code CloudUserService#renameFile}/{@code #moveFile}, neither of which have the file's
     * content in hand (re-extracting it just to update two metadata fields would be wasteful). A
     * no-op if {@code storedFileId} was never indexed to begin with (e.g. its content type isn't
     * indexed at all, or the extension wasn't running at upload time) rather than an error - the
     * next real re-index (a content replacement, or a future full reindex) picks it up.
     *
     * @param authUserId the owning account
     * @param storedFileId the file to update
     * @param fileName the file's current display name
     * @param folderId the file's current folder, or {@code null} for the root
     */
    void updateMetadata(@NotNull String authUserId, @NotNull String storedFileId, @NotNull String fileName, String folderId);

    /**
     * Removes one file from the index - called by {@code CloudUserService#deleteFile} (soft
     * delete/trash, so a trashed file no longer appears in search results) and unconditionally by
     * {@code #hardDeleteFile} (permanent removal, idempotent alongside the soft-delete case above
     * for the {@code resetCloudUser}/{@code deleteCloudUser} paths that bypass the trash entirely -
     * see that method's own Javadoc). A no-op if {@code storedFileId} was never indexed.
     *
     * @param authUserId the owning account
     * @param storedFileId the file to remove from the index
     */
    void removeFile(@NotNull String authUserId, @NotNull String storedFileId);

    /**
     * Re-adds a previously soft-deleted file back into the index - called by {@code
     * CloudUserService#restoreFile}, which has the file's full, resolved content in hand already
     * (needed to check ownership/access) and can afford the extra cost of a fresh {@link
     * #indexFile} call here, unlike {@link #updateMetadata} above.
     *
     * @param document the restored file's current searchable state
     */
    default void restoreFile(@NotNull final SearchDocument document) {
        indexFile(document);
    }

    /**
     * Searches {@code authUserId}'s own indexed files for {@code query}, most-relevant first.
     * Never throws - an unindexable/malformed query should simply return no results, not fail the
     * whole search request.
     *
     * @param authUserId the account whose own files to search - never matches another account's
     * @param query the caller's search text
     * @param limit the maximum number of results to return
     * @return matching files, most-relevant first, capped at {@code limit}; empty if nothing matches
     */
    @NotNull
    List<SearchResult> search(@NotNull String authUserId, @NotNull String query, int limit);

}
