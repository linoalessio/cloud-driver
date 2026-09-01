package de.lino.cloud.api.user;

import de.lino.cloud.api.file.FileWithFolder;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.StoredFileSummary;
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
     * Deletes {@code storedFileId} and stops tracking it, but only if {@code authUserId}
     * actually owns it.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the {@link StoredFile#fileId()} to delete
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    void deleteFile(@NotNull String authUserId, @NotNull String storedFileId);

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
     * Deletes {@code folderId}, but only if {@code authUserId} owns it and it is currently empty
     * (no child folders, no files placed directly inside it) - a folder is never deleted
     * recursively.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param folderId the folder to delete
     * @throws IllegalArgumentException if {@code folderId} isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code folderId} still has child folders or files inside it
     */
    void deleteFolder(@NotNull String authUserId, @NotNull String folderId);

}
