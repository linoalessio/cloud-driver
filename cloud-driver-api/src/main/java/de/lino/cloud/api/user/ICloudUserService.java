package de.lino.cloud.api.user;

import de.lino.cloud.api.file.FileWithFolder;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.PresignedUploadTicket;
import de.lino.cloud.api.file.SharedFileSummary;
import de.lino.cloud.api.file.SharedFolderContents;
import de.lino.cloud.api.file.SharedFolderSummary;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.StoredFileSummary;
import de.lino.cloud.api.file.TrashedFileSummary;
import de.lino.cloud.api.file.TrashedFolderSummary;
import de.lino.cloud.api.s3storage.PresignedDownload;
import de.lino.cloud.api.s3storage.PresignedTransferUnavailableException;
import de.lino.cloud.api.utility.CursorPage;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The behavioral contract {@code CloudUserService} (in {@code cloud-driver-auth})
 * implements - ties an end user (identified by their {@link
 * de.lino.cloud.api.jwt.user.AuthUser#getId()}) to the {@link StoredFile}s they've
 * uploaded and the {@link Folder}s they've organized them into, via that user's own
 * {@link ICloudUser} record. Same "{@code I}-prefixed interface, concrete class
 * implements it" shape {@link de.lino.cloud.api.jwt.auth.IAuthService} already uses.
 * Every method takes the authenticated caller's plain {@code authUserId} - not a full
 * {@code AuthUser} - since that's the only thing available once a JWT has been
 * validated, and it's the only thing {@link ICloudUser} itself ever needs.
 */
public interface ICloudUserService {

    /**
     * Looks up {@code authUserId}'s {@link ICloudUser} record, creating and persisting
     * a fresh (empty) one on first use.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to look up or create a record for
     * @return the existing or newly created {@link ICloudUser} record
     */
    @NotNull
    ICloudUser getOrCreate(@NotNull String authUserId);

    /**
     * Looks up {@code authUserId}'s {@link ICloudUser} record without creating one if it
     * doesn't exist yet - the read-only counterpart to {@link #getOrCreate(String)}.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to look up
     * @return the matching {@link ICloudUser}, or {@link Optional#empty()} if none exists yet
     */
    @NonNull
    Optional<ICloudUser> getCloudUser(@NotNull String authUserId);

    /**
     * Looks up the {@link ICloudUser} record belonging to whichever {@link
     * de.lino.cloud.api.jwt.user.AuthUser} is registered under {@code emailAddress}, if any.
     *
     * @param emailAddress the {@link de.lino.cloud.api.jwt.user.AuthUser#getEmailAddress()} to look up
     * @return the matching {@link ICloudUser}, or {@link Optional#empty()} if no account is registered under that email
     */
    @NonNull
    Optional<ICloudUser> getCloudUserByEmail(@NonNull String emailAddress);

    /**
     * Looks up which account owns {@code storedFileId}, via the same full-{@code
     * StoredFileOwnership}-section scan {@link #getCloudUserByEmail(String)} performs over
     * {@code AuthUser} - added for item 10 (live push via WebSocket, see {@code
     * architecture/SERVICES.md}) so a {@code DatabaseWatchEvent} (which only ever learns a
     * changed file's id, never its owner) can resolve which connected session(s) to notify.
     *
     * @param storedFileId the {@link StoredFile#fileId()} to resolve an owner for
     * @return the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}, or {@link Optional#empty()} if no ownership row tracks this file (e.g. it was hard-deleted)
     */
    @NonNull
    Optional<String> resolveOwnerAuthUserId(@NotNull String storedFileId);

    /**
     * Deletes every {@link StoredFile} and {@link Folder} owned by {@code authUserId} (the
     * same wipe {@link #resetCloudUser(String)} performs) and additionally removes {@code
     * authUserId}'s own {@link ICloudUser} record itself - after this call the user is no
     * longer tracked at all, and a subsequent {@link #getOrCreate(String)} creates a brand
     * new, empty record rather than resurrecting this one.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to delete
     */
    void deleteCloudUser(@NonNull String authUserId);

    /**
     * Deletes every {@link StoredFile} and {@link Folder} owned by {@code authUserId},
     * leaving their {@link ICloudUser} record itself intact but empty - unlike {@link
     * #deleteCloudUser(String)}, the account keeps existing and can be used again
     * immediately. Folders are removed leaf-first regardless of nesting depth, since a
     * folder can only be deleted once it has no children of its own.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to reset
     */
    void resetCloudUser(@NonNull String authUserId);

    /**
     * Adjusts {@code authUserId}'s {@link ICloudUser#getCurrentUploadedBytes()} running total by
     * {@code delta} and persists the change - called with a positive delta after a successful
     * {@link #uploadFile(String, String, byte[], String)} and a negative delta after a successful
     * {@link #deleteFile(String, String)} (when the deleted file's size is known). Clamped at a
     * minimum of {@code 0}. A no-op if {@code authUserId} has no {@link ICloudUser} record yet.
     *
     * @param authUserId the account whose running total to adjust
     * @param delta how many bytes to add (or, if negative, remove) from the running total
     */
    void updateCloudUserBytesUsage(@NonNull String authUserId, final long delta);

    /**
     * Replaces {@code authUserId}'s {@link ICloudUser#getMaxBytesToUpload()} upload-quota ceiling
     * with {@code delta} (clamped at a minimum of {@code 0}) and persists the change - despite the
     * parameter name mirroring {@link #updateCloudUserBytesUsage(String, long)}'s own {@code
     * delta}, this sets the ceiling to that exact value rather than adjusting it incrementally. A
     * no-op if {@code authUserId} has no {@link ICloudUser} record yet.
     *
     * @param authUserId the account whose upload-quota ceiling to replace
     * @param bytes the new upload-quota ceiling, in bytes
     */
    void updateCloudUserBytesLimit(@NonNull String authUserId, final long bytes);

    /**
     * Replaces {@code authUserId}'s stored theme preference (see {@link
     * ICloudUser#getThemeMode()}) and persists the change - the same single-row {@link
     * de.lino.cloud.api.factory.DataFactory#update} shape {@link #updateCloudUserBytesLimit}
     * uses. Lets a light/dark mode choice made on one device (desktop, mobile, ...) sync to
     * every other device signed into the same account, instead of being a local-only setting.
     * A no-op if {@code authUserId} has no {@link ICloudUser} record yet.
     *
     * @param authUserId the account whose theme preference to update
     * @param themeMode the new theme preference (e.g. {@code "LIGHT"}/{@code "DARK"} - this
     *                   layer treats it as an opaque string, the actual enum lives client-side),
     *                   or {@code null} to clear it back to "unset"
     */
    void updateThemePreference(@NonNull String authUserId, @Nullable String themeMode);

    /**
     * One-off, operator-triggered backfill/repair for {@code authUserId}'s {@link
     * ICloudUser#getCurrentUploadedBytes()}: recomputes it from scratch as the sum of every
     * currently-tracked {@link de.lino.cloud.api.file.StoredFile}'s recorded size (including
     * files currently in the trash, which still occupy storage until a purge job removes them -
     * see {@code CloudUserService#deleteFile}'s Javadoc for why trashing alone never decrements
     * this total), then persists the result as a direct overwrite - unlike {@link
     * #updateCloudUserBytesUsage(String, long)}, which only ever applies a relative delta. A
     * row written before per-row size metadata was captured (see {@code
     * StoredFileOwnership#hasMetadata()}) is skipped, the same limitation {@code
     * updateCloudUserBytesUsage}'s own Javadoc already documents for such rows - so an account
     * with any pre-metadata rows may still under-report after this runs, until those specific
     * rows are individually backfilled (e.g. by being listed once via {@link
     * #listFileSummaries(String)}, which backfills metadata as a side effect).
     *
     * <p>Never called automatically - reachable only via the {@code recomputeStorage} terminal
     * {@code Command} (see {@code cloud-driver-extensions-terminal}), matching {@code
     * HardResetCommand}'s "run it deliberately" precedent. A no-op, returning {@code 0}, if
     * {@code authUserId} has no {@link ICloudUser} record yet.
     *
     * @param authUserId the account whose running total to recompute
     * @return the newly-computed and persisted {@link ICloudUser#getCurrentUploadedBytes()} value
     */
    long recomputeUploadedBytes(@NonNull String authUserId);

    /**
     * Uploads {@code fileName}/{@code content} as a new {@link StoredFile} and tracks
     * it on {@code authUserId}'s {@link ICloudUser} record.
     *
     * @param authUserId the uploading user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param fileName the original file name of the content being uploaded
     * @param content the file's raw bytes, of any type
     * @return the uploaded {@link StoredFile}
     */
    @NotNull
    StoredFile uploadFile(@NotNull String authUserId, @NotNull String fileName, byte[] content);

    /**
     * Same as {@link #uploadFile(String, String, byte[])}, placing the new file directly into
     * {@code folderId} instead of the root.
     *
     * @param authUserId the uploading user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param fileName the original file name of the content being uploaded
     * @param content the file's raw bytes, of any type
     * @param folderId the folder to place the new file in, or {@code null} for the root
     * @return the uploaded {@link StoredFile}
     * @throws IllegalArgumentException if {@code folderId} is non-null and isn't tracked as belonging to {@code authUserId}
     */
    @NotNull
    StoredFile uploadFile(@NotNull String authUserId, @NotNull String fileName, byte[] content, @Nullable String folderId);

    /**
     * Begins a presigned, direct-to-client upload (see {@code architecture/AWS_S3_IMPL.md}'s
     * "Explicitly deferred" section, now implemented): checks {@code authUserId}'s quota against
     * the declared {@code sizeBytes} and that {@code folderId} (if given) is actually owned by
     * {@code authUserId}, then returns a fresh {@link PresignedUploadTicket} the caller uploads its
     * content to directly, bypassing this server for the data path entirely. <b>Persists nothing</b>
     * - call {@link #completePresignedUpload} once the upload has actually finished; an abandoned
     * ticket just leaves an orphaned, unlinked object once its URL expires.
     *
     * @param authUserId the uploading user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param fileName the file's original name
     * @param sizeBytes the file's declared size - checked against quota now, and again against the
     *                  real uploaded size in {@link #completePresignedUpload}
     * @param folderId the folder the file will be placed in once completed, or {@code null} for the root
     * @return a ticket pairing the new file's id with where to upload its content
     * @throws IllegalArgumentException if {@code folderId} is non-null and isn't owned by {@code authUserId}
     * @throws de.lino.cloud.api.file.exception.UploadQuotaExceededException if the declared size would exceed {@code authUserId}'s quota
     * @throws PresignedTransferUnavailableException if this deployment has no {@code PresignedTransferService} configured
     */
    @NotNull
    PresignedUploadTicket beginPresignedUpload(@NotNull String authUserId, @NotNull String fileName, long sizeBytes, @Nullable String folderId);

    /**
     * Confirms a presigned upload begun via {@link #beginPresignedUpload} actually completed,
     * verifies its real size against the object store (not the {@code sizeBytes} originally
     * declared - a client could otherwise under-declare to dodge the quota check in {@link
     * #beginPresignedUpload}), and persists the resulting {@link StoredFile}/ownership/usage
     * update - the same effect {@link #uploadFile(String, String, byte[], String)} has, just
     * without this server ever holding the content itself.
     *
     * @param authUserId the uploading user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} - must match the ticket's own {@link #beginPresignedUpload} caller
     * @param fileId the {@link PresignedUploadTicket#fileId()} returned by {@link #beginPresignedUpload}
     * @param fileName the file's original name
     * @param checksumSha256Hex the SHA-256 checksum the uploading client computed over the file's own content, as a lowercase hex string
     * @param folderId the folder to place the new file in, or {@code null} for the root
     * @return a summary of the newly created file
     * @throws IllegalArgumentException if {@code folderId} is non-null and isn't owned by {@code authUserId}, or if no object exists yet under {@code fileId}
     * @throws de.lino.cloud.api.file.exception.UploadQuotaExceededException if the object's real size exceeds {@code authUserId}'s quota - the uploaded object is deleted before this is thrown
     * @throws PresignedTransferUnavailableException if this deployment has no {@code PresignedTransferService} configured
     */
    @NotNull
    StoredFileSummary completePresignedUpload(@NotNull String authUserId, @NotNull String fileId, @NotNull String fileName,
                                               @NotNull String checksumSha256Hex, @Nullable String folderId);

    /**
     * Begins a presigned, direct-to-client download of an already-owned (or shared-with-{@code
     * authUserId}) file - the same ownership/share rules {@link #getFile} applies, but without
     * this server ever fetching the file's content itself.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the {@link StoredFile#fileId()} to download
     * @return where to download the file's content directly from
     * @throws IllegalArgumentException if {@code storedFileId} isn't owned by, or shared with, {@code authUserId}
     * @throws PresignedTransferUnavailableException if this deployment has no {@code PresignedTransferService} configured, or {@code storedFileId} doesn't have its content in that store
     */
    @NotNull
    PresignedDownload beginPresignedDownload(@NotNull String authUserId, @NotNull String storedFileId);

    /**
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose files to list
     * @return every {@link StoredFile} currently tracked as belonging to {@code authUserId}
     */
    @NotNull
    List<StoredFile> listFiles(@NotNull String authUserId);

    /**
     * Same as {@link #listFiles(String)}, but paired with each file's current folder placement.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose files to list
     * @return every {@link StoredFile} currently tracked as belonging to {@code authUserId}, each paired with its folder
     */
    @NotNull
    List<FileWithFolder> listFilesWithFolder(@NotNull String authUserId);

    /**
     * Same as {@link #listFilesWithFolder(String)}, filtered to only the files directly inside {@code folderId}.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose files to list
     * @param folderId the folder to list files from, or {@code null} for the root
     * @return every {@link StoredFile} directly inside {@code folderId} (or the root) that belongs to {@code authUserId}
     */
    @NotNull
    List<FileWithFolder> listFilesWithFolder(@NotNull String authUserId, @Nullable String folderId);

    /**
     * Same as {@link #listFilesWithFolder(String)}, but without any file's content - just its
     * descriptive fields (name, size, content type, timestamps) plus its folder placement. Prefer
     * this for rendering a file list: unlike {@link #listFilesWithFolder(String)}, it never
     * decrypts or decompresses a file's actual content, a cost that scales with total file bytes
     * regardless of what a caller does with the result.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose files to list
     * @return a {@link StoredFileSummary} for every file currently tracked as belonging to {@code authUserId}
     */
    @NotNull
    List<StoredFileSummary> listFileSummaries(@NotNull String authUserId);

    /**
     * Same as {@link #listFileSummaries(String)}, filtered to only the files directly inside {@code folderId}.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose files to list
     * @param folderId the folder to list files from, or {@code null} for the root
     * @return a {@link StoredFileSummary} for every file directly inside {@code folderId} (or the root) that belongs to {@code authUserId}
     */
    @NotNull
    List<StoredFileSummary> listFileSummaries(@NotNull String authUserId, @Nullable String folderId);

    /**
     * Cursor-paginated variant of {@link #listFileSummaries(String, String)} - use when a folder
     * may hold enough files that returning all of them in one response is wasteful. Summaries are
     * sorted by {@link StoredFileSummary#fileId()} (stable but not chronological - an arbitrary,
     * total order is all keyset pagination needs) before being sliced into a page; see {@link
     * CursorPage}'s own Javadoc for why this bounds the response size only, not the per-request
     * scan cost.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose files to list
     * @param folderId the folder to list files from, or {@code null} for the root
     * @param cursor the {@link CursorPage#nextCursor()} of the previous page, or {@code null} for the first page
     * @param limit the maximum number of entries to return; must be positive
     * @return a page of at most {@code limit} {@link StoredFileSummary}s, in ascending {@code fileId} order
     */
    @NotNull
    CursorPage<StoredFileSummary> listFileSummariesPage(@NotNull String authUserId, @Nullable String folderId,
                                                         @Nullable String cursor, int limit);

    /**
     * Fetches one file's full content, paired with its current folder placement. Unlike {@link
     * #listFileSummaries(String)}, this does decrypt/decompress the file's actual content - only
     * reach for this once a specific file's content is actually needed (e.g. the user opened or
     * downloaded it).
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the {@link StoredFile#fileId()} to fetch
     * @return the file's full content, paired with its current folder
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @NotNull
    FileWithFolder getFile(@NotNull String authUserId, @NotNull String storedFileId);

    /**
     * Moves {@code storedFileId} into {@code folderId} (or back to the root, if {@code null}),
     * but only if {@code authUserId} actually owns both the file and the target folder.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the {@link StoredFile#fileId()} to move
     * @param folderId the folder to move the file into, or {@code null} for the root
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId},
     *                                   or {@code folderId} is non-null and isn't tracked as belonging to {@code authUserId}
     */
    void moveFile(@NotNull String authUserId, @NotNull String storedFileId, @Nullable String folderId);

    /**
     * @return every currently registered {@link ICloudUser} record
     */
    @NonNull
    List<ICloudUser> getCloudUsers();

    /**
     * Soft-deletes (moves to the trash) {@code storedFileId}, but only if {@code authUserId}
     * actually owns it - the file's content is untouched and its ownership row remains, just
     * hidden from every normal listing until {@link #restoreFile(String, String)} is called or a
     * purge job permanently removes it. Idempotent: a no-op if {@code storedFileId} is already in
     * the trash.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the {@link StoredFile#fileId()} to delete
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    void deleteFile(@NotNull String authUserId, @NotNull String storedFileId);

    /**
     * Restores a previously soft-deleted {@code storedFileId} out of the trash, but only if
     * {@code authUserId} actually owns it.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the {@link StoredFile#fileId()} to restore
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code storedFileId} is not currently in the trash
     */
    void restoreFile(@NotNull String authUserId, @NotNull String storedFileId);

    /**
     * Lists every file currently in {@code authUserId}'s trash, each paired with when it becomes
     * eligible for permanent removal (added 2026-09-02 - see {@link TrashedFileSummary}'s own
     * Javadoc).
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose trash to list
     * @return a {@link TrashedFileSummary} for every file currently in {@code authUserId}'s trash
     */
    @NotNull
    List<TrashedFileSummary> listDeletedFiles(@NotNull String authUserId);

    /**
     * Creates a new, empty {@link Folder} owned by {@code authUserId}.
     *
     * @param authUserId the owning user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param name the new folder's display name
     * @param parentFolderId the parent folder to nest the new folder inside, or {@code null} for the top level
     * @return the newly created {@link Folder}
     * @throws IllegalArgumentException if {@code parentFolderId} is non-null and isn't tracked as belonging to {@code authUserId}
     */
    @NotNull
    Folder createFolder(@NotNull String authUserId, @NotNull String name, @Nullable String parentFolderId);

    /**
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose folders to list
     * @param parentFolderId the parent folder to list children of, or {@code null} for the top level
     * @return every {@link Folder} belonging to {@code authUserId} directly inside {@code parentFolderId} (or the top level)
     */
    @NotNull
    List<Folder> listFolders(@NotNull String authUserId, @Nullable String parentFolderId);

    /**
     * Cursor-paginated variant of {@link #listFolders(String, String)}, sorted by {@link
     * Folder#getFolderId()} - see {@link #listFileSummariesPage}/{@link CursorPage}'s own Javadoc
     * for the same "bounds response size, not scan cost" caveat.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose folders to list
     * @param parentFolderId the parent folder to list children of, or {@code null} for the top level
     * @param cursor the {@link CursorPage#nextCursor()} of the previous page, or {@code null} for the first page
     * @param limit the maximum number of entries to return; must be positive
     * @return a page of at most {@code limit} {@link Folder}s, in ascending {@code folderId} order
     */
    @NotNull
    CursorPage<Folder> listFoldersPage(@NotNull String authUserId, @Nullable String parentFolderId,
                                        @Nullable String cursor, int limit);

    /**
     * Renames and/or moves {@code folderId} in one step - a full replace of both its {@link
     * Folder#getName()} and {@link Folder#getParentFolderId()}, matching this being a {@code PUT}
     * (whole-resource replace) over HTTP.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param folderId the folder to update
     * @param newName the folder's new display name
     * @param newParentFolderId the folder's new parent, or {@code null} to move it to the top level
     * @return the updated {@link Folder}
     * @throws IllegalArgumentException if {@code folderId}/{@code newParentFolderId} (when non-null)
     *                                   isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code newParentFolderId} is {@code folderId} itself, or one
     *                                of {@code folderId}'s own descendants (which would create a cycle)
     */
    @NotNull
    Folder updateFolder(@NotNull String authUserId, @NotNull String folderId,
                         @NotNull String newName, @Nullable String newParentFolderId);

    /**
     * Soft-deletes (moves to the trash) {@code folderId}, but only if {@code authUserId} owns it
     * and it is currently empty (no non-trashed child folders, no non-trashed files placed
     * directly inside it) - a folder is never deleted recursively. The folder itself remains
     * restorable via {@link #restoreFolder(String, String)} until a purge job permanently
     * removes it.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param folderId the folder to delete
     * @throws IllegalArgumentException if {@code folderId} isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code folderId} still has non-trashed child folders or files inside it
     */
    void deleteFolder(@NotNull String authUserId, @NotNull String folderId);

    /**
     * Restores a previously soft-deleted {@code folderId} out of the trash, but only if {@code
     * authUserId} owns it. Does not validate that {@code folderId}'s own parent is still present/
     * non-trashed - a folder restored under a since-deleted parent simply stays unreachable from a
     * normal listing until that parent is restored too, or this folder is moved elsewhere.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param folderId the folder to restore
     * @throws IllegalArgumentException if {@code folderId} isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code folderId} is not currently in the trash
     */
    void restoreFolder(@NotNull String authUserId, @NotNull String folderId);

    /**
     * Lists every folder currently in {@code authUserId}'s trash, each paired with when it becomes
     * eligible for permanent removal - same addition as {@link #listDeletedFiles}, see {@link
     * TrashedFolderSummary}'s own Javadoc.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose trash to list
     * @return a {@link TrashedFolderSummary} for every folder currently in {@code authUserId}'s trash
     */
    @NotNull
    List<TrashedFolderSummary> listDeletedFolders(@NotNull String authUserId);

    /**
     * Permanently removes every file and folder currently in {@code authUserId}'s trash - the
     * "Empty trash bin" action (added 2026-09-02), an on-demand, per-account counterpart to what
     * {@code TrashPurgeScheduler} does automatically once a trashed item's retention window has
     * elapsed. Unlike that scheduler, this bypasses the retention window entirely - every currently
     * trashed item is removed regardless of how recently it was deleted, the moment this is called.
     * Live (non-trashed) files/folders are completely untouched. Idempotent: a no-op if the trash is
     * already empty. Irreversible, the same "no undo past this point" property {@link
     * #resetCloudUser(String)} already has for a full account wipe - unlike a single {@link
     * #deleteFile}/{@link #deleteFolder}, there is no {@link #restoreFile}/{@link #restoreFolder}
     * once this has run.
     *
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose trash to empty
     */
    void emptyTrash(@NotNull String authUserId);

    /**
     * Grants {@code granteeEmail}'s account read-only access to {@code fileId} - owner-only, like
     * every other mutating method on this interface: only {@code ownerAuthUserId} (the file's
     * actual owner) may create or revoke a share on it, a grantee can never re-share what was
     * shared with them. Idempotent - sharing with the same grantee again just refreshes the
     * grant's timestamp. See {@code CloudUserService#getFile} for how a grantee actually exercises
     * this grant, and this interface's own "which operations honor a share" summary (in {@code
     * CLAUDE.md}'s "JWT authentication for end-user clients" section) for the complete picture.
     *
     * @param ownerAuthUserId the file's actual owner, who must already own {@code fileId}
     * @param fileId the file to share
     * @param granteeEmail the email address of the account to grant read access to
     * @throws IllegalArgumentException if {@code fileId} isn't owned by {@code ownerAuthUserId}, is
     *                                   currently in the trash, or {@code granteeEmail} resolves to
     *                                   {@code ownerAuthUserId} itself
     * @throws GranteeAccountNotFoundException if {@code granteeEmail} has no registered account
     */
    void shareFile(@NotNull String ownerAuthUserId, @NotNull String fileId, @NotNull String granteeEmail);

    /**
     * Revokes a previously-granted read-only share of {@code fileId} from {@code granteeEmail}'s
     * account - owner-only, the reverse of {@link #shareFile}. Idempotent: a no-op if no such grant
     * exists.
     *
     * @param ownerAuthUserId the file's actual owner, who must already own {@code fileId}
     * @param fileId the file to revoke a share of
     * @param granteeEmail the email address of the account whose access to revoke
     * @throws IllegalArgumentException if {@code fileId} isn't owned by {@code ownerAuthUserId}
     * @throws GranteeAccountNotFoundException if {@code granteeEmail} has no registered account
     */
    void revokeFileShare(@NotNull String ownerAuthUserId, @NotNull String fileId, @NotNull String granteeEmail);

    /**
     * Lists every file directly shared with {@code authUserId} via {@link #shareFile} - as {@link
     * SharedFileSummary}s (added 2026-09-02 - each paired with the sharing account's email address,
     * so the caller can see who shared it; the plain {@link StoredFileSummary} this method used to
     * return carried no owner information at all), the same descriptive-fields-only shape {@link
     * #listFileSummaries(String)} returns for the caller's own files. Does <b>not</b> include a
     * file only reachable through a folder-level share ({@link #shareFolder}) - browsing a shared
     * folder's own contents is a documented future extension, not implemented in this first
     * sharing pass (see {@code CLAUDE.md}). A grant whose underlying file has since been deleted or
     * trashed by its owner is silently omitted, rather than surfaced as an error.
     *
     * @param authUserId the account whose incoming file shares to list
     * @return a {@link SharedFileSummary} for every file directly shared with {@code authUserId}
     */
    @NotNull
    List<SharedFileSummary> listSharedWithMe(@NotNull String authUserId);

    /**
     * Grants {@code granteeEmail}'s account read-only access to {@code folderId} and everything
     * nested inside it, at any depth - owner-only, the same shape as {@link #shareFile}.
     * Idempotent - sharing with the same grantee again just refreshes the grant's timestamp.
     *
     * @param ownerAuthUserId the folder's actual owner, who must already own {@code folderId}
     * @param folderId the folder to share
     * @param granteeEmail the email address of the account to grant read access to
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code ownerAuthUserId},
     *                                   is currently in the trash, or {@code granteeEmail} resolves
     *                                   to {@code ownerAuthUserId} itself
     * @throws GranteeAccountNotFoundException if {@code granteeEmail} has no registered account
     */
    void shareFolder(@NotNull String ownerAuthUserId, @NotNull String folderId, @NotNull String granteeEmail);

    /**
     * Revokes a previously-granted read-only share of {@code folderId} from {@code granteeEmail}'s
     * account - owner-only, the reverse of {@link #shareFolder}. Idempotent: a no-op if no such
     * grant exists. Does not affect any direct {@link #shareFile} grant on a file nested inside
     * {@code folderId} - those are tracked independently and must be revoked separately.
     *
     * @param ownerAuthUserId the folder's actual owner, who must already own {@code folderId}
     * @param folderId the folder to revoke a share of
     * @param granteeEmail the email address of the account whose access to revoke
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code ownerAuthUserId}
     * @throws GranteeAccountNotFoundException if {@code granteeEmail} has no registered account
     */
    void revokeFolderShare(@NotNull String ownerAuthUserId, @NotNull String folderId, @NotNull String granteeEmail);

    /**
     * Lists every folder directly shared with {@code authUserId} via {@link #shareFolder} - as
     * {@link SharedFolderSummary}s (added 2026-09-02, same "pair with the sharing account's email"
     * reasoning as {@link #listSharedWithMe}) - just the shared folders themselves, not their
     * contents; see {@link #listSharedFolderContents} for browsing what's actually inside one. A
     * grant whose underlying folder has since been deleted or trashed by its owner is silently
     * omitted.
     *
     * @param authUserId the account whose incoming folder shares to list
     * @return a {@link SharedFolderSummary} for every folder directly shared with {@code authUserId}
     */
    @NotNull
    List<SharedFolderSummary> listSharedFoldersWithMe(@NotNull String authUserId);

    /**
     * Lists the non-trashed files and subfolders directly inside {@code folderId}, for a caller who
     * doesn't own it but reaches it via a share - either a direct {@link #shareFolder} grant on
     * {@code folderId} itself, or an inherited one on any of its ancestor folders (a folder share
     * covers everything nested inside it, at any depth - the same ancestor-walk {@code
     * CloudUserService#getFile}'s own share-aware lookup already performs for a single file, applied
     * here to browsing instead). Added 2026-09-02, finally implementing the "browsing a shared
     * folder's contents" extension {@link #listSharedWithMe}'s own Javadoc used to describe as
     * future/out of scope. If {@code authUserId} happens to <em>own</em> {@code folderId}, this
     * still works (ownership trivially satisfies "has access") but {@link #listFileSummaries}/{@link
     * #listFolders} are the normal, more direct way for an owner to browse their own folder.
     *
     * @param authUserId the caller, who must own {@code folderId} or have it shared with them (directly or via an ancestor)
     * @param folderId the folder to browse
     * @return the non-trashed files/subfolders directly inside {@code folderId}
     * @throws IllegalArgumentException if {@code folderId} doesn't exist, is currently trashed, or
     *                                   isn't owned by or shared with {@code authUserId}
     */
    @NotNull
    SharedFolderContents listSharedFolderContents(@NotNull String authUserId, @NotNull String folderId);

    /**
     * Lists the email addresses of every account {@code fileId} is currently shared with - the
     * owner-side counterpart to {@link #listSharedWithMe}, backing a "who can see this file"/revoke
     * UI. Owner-only, the same reasoning {@link #shareFile}/{@link #revokeFileShare} already use.
     *
     * @param ownerAuthUserId the file's actual owner, who must already own {@code fileId}
     * @param fileId the file whose current shares to list
     * @return the email address of every account currently granted read access to {@code fileId},
     *         in no particular order
     * @throws IllegalArgumentException if {@code fileId} isn't owned by {@code ownerAuthUserId}
     */
    @NotNull
    List<String> listFileShares(@NotNull String ownerAuthUserId, @NotNull String fileId);

    /**
     * Lists the email addresses of every account {@code folderId} is currently shared with - the
     * owner-side counterpart to {@link #listSharedFoldersWithMe}, backing a "who can see this
     * folder"/revoke UI. Owner-only, the same reasoning {@link #shareFolder}/{@link
     * #revokeFolderShare} already use.
     *
     * @param ownerAuthUserId the folder's actual owner, who must already own {@code folderId}
     * @param folderId the folder whose current shares to list
     * @return the email address of every account currently granted read access to {@code
     *         folderId}, in no particular order
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code ownerAuthUserId}
     */
    @NotNull
    List<String> listFolderShares(@NotNull String ownerAuthUserId, @NotNull String folderId);

    /**
     * Counts the distinct files {@code authUserId} owns that currently have at least one active
     * {@link #shareFile} grant - i.e. how many of the caller's own files are shared with someone
     * else. The owner-side counterpart to {@link #listSharedWithMe}'s grantee-side count; backs
     * the desktop app's Dashboard "Shared files" stat card (fixed 2026-09-03 - that card used to
     * display {@code listSharedWithMe(authUserId).size()}, the wrong direction entirely: files
     * shared <em>with</em> the account, not files the account has shared <em>with others</em>).
     * A file shared with more than one grantee is still only counted once. Does not count a file
     * only reachable via a {@link #shareFolder} grant on one of its ancestor folders - the same
     * "direct grants only" scope {@link #listSharedWithMe} already documents for the grantee side.
     *
     * @param authUserId the account whose own outgoing file shares to count
     * @return the number of distinct files {@code authUserId} owns with at least one active share, {@code 0} if none
     */
    int countFilesSharedByMe(@NotNull String authUserId);

}
