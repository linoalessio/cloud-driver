package de.lino.cloud.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.FileWithFolder;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.StoredFileSummary;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.file.exception.UploadQuotaExceededException;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Ties {@link AuthUser} accounts to the {@link StoredFile}s they've uploaded and the {@link
 * Folder}s they've organized them into. Each user's own {@link CloudUser} record only
 * identifies the user; ownership of individual files is tracked separately, one {@link
 * StoredFileOwnership} row per (user, file) pair, which also carries that file's current
 * folder placement - see that class's Javadoc for why. Framework-agnostic, same reasoning as
 * {@link AuthService}: every checked exception a delegate call can throw is rewrapped as a
 * plain {@link RuntimeException} rather than declared, since a caller wiring this into an HTTP
 * layer handles failures at that boundary, not here. Every method takes the caller's plain
 * {@code authUserId} - not a full {@link AuthUser} - since that's the only thing available once
 * a JWT has been validated (see {@code DefaultRestFactory#requireValidBearerToken}).
 */
public final class CloudUserService implements ICloudUserService {

    /** Persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows. */
    private final DataFactory dataFactory;

    /** Uploads/downloads/deletes the underlying {@link StoredFile} content. */
    private final FileFactory fileFactory;

    /**
     * Creates a {@code CloudUserService} backed by the given collaborators.
     *
     * @param dataFactory persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows
     * @param fileFactory uploads/downloads/deletes the underlying {@link StoredFile} content
     */
    public CloudUserService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory) {
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
    }

    /**
     * Looks up {@code authUserId}'s {@link CloudUser} record, creating and persisting a fresh
     * one on first use.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @return the existing or newly-created {@link CloudUser}
     */
    @NonNull
    @Override
    public ICloudUser getOrCreate(@NonNull final String authUserId) {
        try {

            final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
            if (cloudUser.isPresent()) return cloudUser.get();

            final CloudUser newCloudUser = new CloudUser(authUserId);
            this.dataFactory.register(newCloudUser);
            return newCloudUser;
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.getOrCreate: failed to look up/create CloudUser for " + authUserId, e);
        }
    }

    /**
     * Looks up {@code authUserId}'s {@link CloudUser} record directly, without creating one if it
     * doesn't exist yet - the read-only counterpart to {@link #getOrCreate(String)}.
     *
     * @param authUserId the owning {@link AuthUser#getId()}
     * @return the matching {@link ICloudUser}, or {@link Optional#empty()} if none is registered under that id
     */
    @Override
    public @NonNull Optional<ICloudUser> getCloudUser(@NotNull String authUserId) {
        try {
            return this.dataFactory.findById(authUserId, CloudUser.class).map(cloudUser -> cloudUser);
        } catch (DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Looks up the {@link AuthUser} registered under {@code emailAddress} (the same full-table
     * scan {@link AuthService#login} performs), then resolves that account's own {@link
     * CloudUser} record via {@link #getCloudUser(String)}.
     *
     * @param emailAddress the {@link AuthUser#getEmailAddress()} to look up
     * @return the matching {@link ICloudUser}, or {@link Optional#empty()} if no account is registered under that email
     */
    @Override
    public @NonNull Optional<ICloudUser> getCloudUserByEmail(@NonNull final String emailAddress) {
        try {

            final Optional<AuthUser> authUser = this.dataFactory.getEntities(AuthUser.class).stream()
                    .filter(user -> user.getEmailAddress().equals(emailAddress))
                    .findFirst();
            return authUser.flatMap(user -> this.getCloudUser(user.getId()));

        } catch (final DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.getCloudUserByEmail: failed to look up CloudUser for " + emailAddress, e);
        }
    }

    /**
     * Deletes every {@link StoredFile}/{@link Folder} owned by {@code authUserId} (via {@link
     * #resetCloudUser(String)}) and additionally removes the {@link CloudUser} record itself -
     * unlike {@link #resetCloudUser(String)}, the account is no longer tracked at all afterwards.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to delete
     */
    @Override
    public void deleteCloudUser(@NonNull final String authUserId) {
        this.resetCloudUser(authUserId);
        try {
            this.dataFactory.delete(authUserId, CloudUser.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteCloudUser: failed to delete CloudUser record for " + authUserId, e);
        }
    }

    /**
     * Deletes every {@link StoredFile} (via {@link #deleteFile}, so both the file content and
     * its {@link StoredFileOwnership} row are removed together) and every {@link Folder} owned
     * by {@code authUserId}, leaving the {@link CloudUser} record itself untouched. Folders are
     * deleted leaf-first ({@link #deleteAllOwnedFolders}) since {@link #deleteFolder} refuses to
     * remove a folder that still has children.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to reset
     */
    @Override
    public void resetCloudUser(@NonNull final String authUserId) {
        for (final StoredFileOwnership ownership : this.ownedFileOwnerships(authUserId)) {
            this.deleteFile(authUserId, ownership.getStoredFileId());
        }
        this.deleteAllOwnedFolders(authUserId);
    }

    /**
     * Adjusts {@code authUserId}'s {@link ICloudUser#getCurrentUploadedBytes()} running total by
     * {@code delta} (positive after a successful upload, negative after a successful delete) and
     * persists the change - a single-row {@link DataFactory#update}, not a rewrite of anything
     * else on the account. Clamped at a minimum of {@code 0}: a negative running total would be
     * nonsensical and would under-report usage to {@link CloudUser#isUploadLimitReached}, letting
     * a caller upload past its real quota. A no-op if {@code authUserId} has no {@link CloudUser}
     * record yet (nothing to adjust).
     *
     * @param authUserId the account whose running total to adjust
     * @param delta how many bytes to add (or, if negative, remove) from the running total
     */
    @Override
    public void updateCloudUserBytesUsage(@NonNull final String authUserId, final long delta) {
        final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
        if (cloudUser.isEmpty()) return;

        final ICloudUser existing = cloudUser.get();
        existing.setCurrentUploadedBytes(Math.max(0, existing.getCurrentUploadedBytes() + delta));

        try {
            this.dataFactory.update((CloudUser) existing);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateCloudUserBytesUsage: failed to persist usage update for " + authUserId, e);
        }
    }

    /**
     * Sets {@code authUserId}'s {@link ICloudUser#getMaxBytesToUpload()} upload quota to {@code
     * bytes} and persists the change - a single-row {@link DataFactory#update}. Clamped at a
     * minimum of {@code 0}. A no-op if {@code authUserId} has no {@link CloudUser} record yet.
     *
     * @param authUserId the account whose quota to change
     * @param bytes the new quota ceiling, in bytes
     */
    @Override
    public void updateCloudUserBytesLimit(@NonNull String authUserId, final long bytes) {

        final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
        if (cloudUser.isEmpty()) return;

        final ICloudUser existing = cloudUser.get();
        existing.setMaxBytesToUpload(Math.max(0, bytes));

        try {
            this.dataFactory.update((CloudUser) existing);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateCloudUserBytesLimit: failed to persist usage update for " + authUserId, e);
        }

    }

    /**
     * Deletes every {@link Folder} owned by {@code authUserId}, regardless of nesting depth,
     * by repeatedly deleting whichever folders are currently leaves (no other remaining folder
     * points at them via {@link Folder#getParentFolderId()}) until none are left - the same
     * "must be empty first" constraint {@link #deleteFolder} enforces for a single folder,
     * applied bottom-up across the whole tree instead of requiring the caller to do so one
     * folder at a time.
     *
     * @param authUserId the owning user whose entire folder tree should be deleted
     * @throws IllegalStateException if a cycle is detected among the remaining folders (defense-in-depth;
     *     writes elsewhere already prevent this from occurring)
     */
    private void deleteAllOwnedFolders(final String authUserId) {
        final List<Folder> remaining;
        try {
            remaining = new ArrayList<>(this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .toList());
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.deleteAllOwnedFolders: failed to list folders for " + authUserId, e);
        }

        while (!remaining.isEmpty()) {
            final Set<String> parentIds = remaining.stream()
                    .map(Folder::getParentFolderId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            final List<Folder> leaves = remaining.stream()
                    .filter(folder -> !parentIds.contains(folder.getFolderId()))
                    .toList();
            if (leaves.isEmpty()) {
                throw new IllegalStateException(
                        "@CloudUserService.deleteAllOwnedFolders: cycle detected among folders owned by " + authUserId);
            }
            for (final Folder leaf : leaves) {
                try {
                    this.dataFactory.delete(leaf.getFolderId(), Folder.class);
                } catch (final DatabaseClientException e) {
                    throw new RuntimeException("@CloudUserService.deleteAllOwnedFolders: failed to delete folder " + leaf.getFolderId(), e);
                }
            }
            remaining.removeAll(leaves);
        }
    }

    /**
     * Uploads {@code fileName}/{@code content} as a new {@link StoredFile} (a fresh,
     * random id) and tracks it as owned by {@code authUserId} via a single new {@link
     * StoredFileOwnership} row - a plain insert, not a rewrite of any existing data,
     * regardless of how many files {@code authUserId} already owns. Placed at the root.
     *
     * @param authUserId the uploading user's id, tracked as the new file's owner
     * @param fileName the file's name, used to infer its content type and preserved on download
     * @param content the file's raw bytes
     * @return the newly-created {@link StoredFile}
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName, final byte[] content) {
        return this.uploadFile(authUserId, fileName, content, null);
    }

    /**
     * Same as {@link #uploadFile(String, String, byte[])}, placing the new file directly into
     * {@code folderId} instead of the root.
     *
     * @param authUserId the uploading user's id, tracked as the new file's owner
     * @param fileName the file's name, used to infer its content type and preserved on download
     * @param content the file's raw bytes
     * @param folderId the folder to place the new file in, or {@code null} for the root
     * @return the newly-created {@link StoredFile}
     * @throws IllegalArgumentException if {@code folderId} is non-null and isn't owned by {@code authUserId}
     * @throws UploadQuotaExceededException if {@code authUserId} has reached its {@link
     *                                       ICloudUser#getMaxBytesToUpload()} upload quota
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName, final byte[] content,
                                  @Nullable final String folderId) {

        final ICloudUser cloudUser = this.getOrCreate(authUserId);
        // Checked before requireOwnedFolder/constructing the StoredFile (which DEFLATE-compresses
        // and base64-encodes content up front) - no reason to pay for either on a rejected upload.
        if (cloudUser.isUploadLimitReached(content.length)) {
            throw new UploadQuotaExceededException(
                    authUserId, cloudUser.getCurrentUploadedBytes(), content.length, cloudUser.getMaxBytesToUpload());
        }
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final StoredFile storedFile = new StoredFile(UUID.randomUUID().toString(), fileName, content);

        try {
            this.fileFactory.upload(storedFile);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.uploadFile: failed to upload '" + fileName + "'", e);
        }

        try {
            this.dataFactory.register(StoredFileOwnership.of(authUserId, storedFile, folderId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(
                    "@CloudUserService.uploadFile: failed to track ownership of " + storedFile.fileId() + " for " + authUserId, e
            );
        }

        this.updateCloudUserBytesUsage(authUserId, content.length);

        return storedFile;
    }

    /**
     * Lists every {@link StoredFile} currently tracked as belonging to {@code authUserId}.
     *
     * <p><strong>Trade-off:</strong> neither {@link DataFactory} nor the underlying
     * database-driver expose a lookup by a non-primary-key field, so this scans and
     * decrypts every {@link StoredFileOwnership} row across <em>every</em> user - via
     * {@link DataFactory#getEntities} - and filters down to {@code authUserId} in
     * memory. Each row is tiny (two ids plus a folder id) and decrypted concurrently
     * (see {@code EntityDatabaseClient#retrieveAll}), so this is still far cheaper than
     * the old single-blob-of-10,000-ids design on the read side, and this method is
     * called far less often than {@link #uploadFile}/{@link #deleteFile}. If the number
     * of ownership rows system-wide grows large enough for this scan itself to matter,
     * the fix is a proper indexed query (e.g. {@code WHERE authUserId = ?}) exposed
     * from {@code database-driver-v2} up through {@code DataFactory} - not something
     * available today.
     *
     * @param authUserId the user whose files should be listed
     * @return every {@link StoredFile} currently tracked as belonging to {@code authUserId}
     */
    @NonNull
    @Override
    public List<StoredFile> listFiles(@NonNull final String authUserId) {
        final List<String> ownedFileIds = this.ownedFileIds(authUserId);
        try {
            return this.fileFactory.download(ownedFileIds.toArray(new String[0]));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.listFiles: failed to download files for " + authUserId, e);
        }
    }

    /**
     * Same as {@link #listFiles(String)}, but paired with each file's current folder
     * placement - see that method's Javadoc for the same full-scan trade-off this shares.
     *
     * @param authUserId the user whose files should be listed
     * @return every {@link StoredFile} currently tracked as belonging to {@code authUserId}, each paired with its folder
     */
    @NonNull
    @Override
    public List<FileWithFolder> listFilesWithFolder(@NonNull final String authUserId) {
        return this.resolveFilesWithFolder(this.ownedFileOwnerships(authUserId));
    }

    /**
     * Same as {@link #listFilesWithFolder(String)}, filtered to only the files directly inside {@code folderId}.
     *
     * @param authUserId the user whose files should be listed
     * @param folderId the folder to list files from, or {@code null} for the root
     * @return every {@link StoredFile} directly inside {@code folderId} (or the root) that belongs to {@code authUserId}
     */
    @NonNull
    @Override
    public List<FileWithFolder> listFilesWithFolder(@NonNull final String authUserId, @Nullable final String folderId) {
        final List<StoredFileOwnership> filtered = this.ownedFileOwnerships(authUserId).stream()
                .filter(ownership -> Objects.equals(ownership.getFolderId(), folderId))
                .toList();
        return this.resolveFilesWithFolder(filtered);
    }

    /**
     * Downloads every file in {@code ownerships} and pairs each with its recorded {@link StoredFileOwnership#getFolderId()}.
     *
     * @param ownerships the ownership rows whose files should be downloaded and paired
     * @return each downloaded {@link StoredFile}, paired with its recorded folder placement
     */
    private List<FileWithFolder> resolveFilesWithFolder(final List<StoredFileOwnership> ownerships) {
        final Map<String, String> folderIdByFileId = new HashMap<>();
        ownerships.forEach(ownership -> folderIdByFileId.put(ownership.getStoredFileId(), ownership.getFolderId()));

        final String[] ids = ownerships.stream().map(StoredFileOwnership::getStoredFileId).toArray(String[]::new);
        try {
            return this.fileFactory.download(ids).stream()
                    .map(file -> new FileWithFolder(file, folderIdByFileId.get(file.fileId())))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.resolveFilesWithFolder: failed to download files", e);
        }
    }

    /**
     * Same as {@link #listFilesWithFolder(String)}, but without any file's content - just each
     * {@link StoredFileOwnership} row's own recorded name/size/content-type/timestamps/folder.
     * Unlike {@link #listFilesWithFolder(String)}/{@link #listFiles(String)}, this never calls
     * {@link FileFactory#download} at all: every {@link StoredFileOwnership} row already carries
     * its own file's descriptive fields (captured once, at upload time - see {@link
     * StoredFileOwnership#hasMetadata()}), so building a listing is just reading rows this method
     * already scans regardless. This is the efficient path for rendering a file list; reach for
     * {@link #listFilesWithFolder(String)} only once a specific file's actual content is needed.
     *
     * @param authUserId the user whose files should be listed
     * @return a {@link StoredFileSummary} for every file currently tracked as belonging to {@code authUserId}
     */
    @NonNull
    @Override
    public List<StoredFileSummary> listFileSummaries(@NonNull final String authUserId) {
        return this.resolveFileSummaries(this.ownedFileOwnerships(authUserId));
    }

    /**
     * Same as {@link #listFileSummaries(String)}, filtered to only the files directly inside {@code folderId}.
     *
     * @param authUserId the user whose files should be listed
     * @param folderId the folder to list files from, or {@code null} for the root
     * @return a {@link StoredFileSummary} for every file directly inside {@code folderId} (or the root) that belongs to {@code authUserId}
     */
    @NonNull
    @Override
    public List<StoredFileSummary> listFileSummaries(@NonNull final String authUserId, @Nullable final String folderId) {
        final List<StoredFileOwnership> filtered = this.ownedFileOwnerships(authUserId).stream()
                .filter(ownership -> Objects.equals(ownership.getFolderId(), folderId))
                .toList();
        return this.resolveFileSummaries(filtered);
    }

    /**
     * {@link #resolveFileSummary}, applied to every entry.
     *
     * @param ownerships the ownership rows to summarize
     * @return one {@link StoredFileSummary} per entry in {@code ownerships}
     */
    private List<StoredFileSummary> resolveFileSummaries(final List<StoredFileOwnership> ownerships) {
        return ownerships.stream().map(this::resolveFileSummary).toList();
    }

    /**
     * Builds one {@link StoredFileSummary} straight from {@code ownership}'s own fields - unless
     * it predates metadata capture ({@link StoredFileOwnership#hasMetadata()} {@code false}), in
     * which case this falls back to downloading the full {@link StoredFile} exactly once,
     * persisting a {@link StoredFileOwnership#withMetadata(StoredFile)} copy so every later call
     * for this same row takes the fast, no-download path.
     *
     * @param ownership the ownership row to summarize
     * @return the resulting {@link StoredFileSummary}
     */
    private StoredFileSummary resolveFileSummary(final StoredFileOwnership ownership) {
        final String storedFileId = ownership.getStoredFileId();
        StoredFileOwnership resolved = ownership;
        if (!resolved.hasMetadata()) {
            final StoredFile file;
            try {
                file = this.fileFactory.findById(storedFileId)
                        .orElseThrow(() -> new IllegalStateException(
                                "@CloudUserService.resolveFileSummary: owned file not found: " + storedFileId));
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
                throw new RuntimeException(
                        "@CloudUserService.resolveFileSummary: failed to backfill metadata for " + storedFileId, e);
            }
            resolved = resolved.withMetadata(file);
            try {
                this.dataFactory.update(resolved);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new RuntimeException(
                        "@CloudUserService.resolveFileSummary: failed to persist backfilled metadata for " + storedFileId, e);
            }
        }
        return new StoredFileSummary(resolved.getStoredFileId(), resolved.getFileName(), resolved.getContentType(),
                resolved.getSizeBytes(), resolved.getCreatedAtEpochMilli(), resolved.getUpdatedAtEpochMilli(), resolved.getFolderId());
    }

    /**
     * Fetches one file's full content, paired with its current folder placement - unlike {@link
     * #listFileSummaries(String)}, this does pay the decrypt/decompress cost {@link
     * FileFactory#findById} incurs, the same cost {@link #listFilesWithFolder(String)} pays for
     * every entry it returns; only reach for this once a specific file's actual content is needed
     * (e.g. the user opened/downloaded it).
     *
     * @param authUserId the requesting user's id, checked against the ownership record
     * @param storedFileId the file to fetch
     * @return the file's full content, paired with its current folder
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @NonNull
    @Override
    public FileWithFolder getFile(@NonNull final String authUserId, @NonNull final String storedFileId) {
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);
        try {
            final StoredFile file = this.fileFactory.findById(storedFileId)
                    .orElseThrow(() -> new IllegalStateException(
                            "@CloudUserService.getFile: owned file not found: " + storedFileId));
            return new FileWithFolder(file, ownership.getFolderId());
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.getFile: failed to download " + storedFileId, e);
        }
    }

    /**
     * Moves {@code storedFileId} into {@code folderId} (or back to the root, if {@code null}) -
     * a single-row update on its {@link StoredFileOwnership}, never touching the file's own
     * content or any other file's placement.
     *
     * @param authUserId the requesting user's id, checked against the ownership record
     * @param storedFileId the file to move
     * @param folderId the folder to move the file into, or {@code null} for the root
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId},
     *                                   or {@code folderId} is non-null and isn't owned by {@code authUserId}
     */
    @Override
    public void moveFile(@NonNull final String authUserId, @NonNull final String storedFileId, @Nullable final String folderId) {

        final StoredFileOwnership existing = this.requireOwnedFile(authUserId, storedFileId);
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        try {
            this.dataFactory.update(existing.movedTo(folderId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.moveFile: failed to move " + storedFileId + " to folder " + folderId, e);
        }
    }

    /**
     * Lists every {@link CloudUser} currently registered, as their {@link ICloudUser} contract.
     *
     * @return every currently-registered {@link ICloudUser}
     */
    @NonNull
    @Override
    public List<ICloudUser> getCloudUsers() {
        try {
            return this.dataFactory.getEntities(CloudUser.class).stream()
                    .map(ICloudUser.class::cast)
                    .toList();
        } catch (final DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.getCloudUsers: failed to list CloudUser records", e);
        }
    }

    /**
     * Deletes {@code storedFileId} and stops tracking its ownership, but only if {@code
     * authUserId} actually owns it.
     *
     * @param authUserId the caller's own id, checked against the ownership record
     * @param storedFileId the file to delete
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @Override
    public void deleteFile(@NonNull final String authUserId, @NonNull final String storedFileId) {

        // Fetches the ownership row itself (not just a boolean) - its own recorded sizeBytes
        // (when known, see StoredFileOwnership#hasMetadata()) is what lets the usage decrement
        // below avoid a full FileFactory#download just to find out how large the deleted file was.
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);

        try {
            this.fileFactory.delete(storedFileId);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteFile: failed to delete " + storedFileId, e);
        }

        try {
            this.dataFactory.delete(StoredFileOwnership.compositeKey(authUserId, storedFileId), StoredFileOwnership.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException(
                    "@CloudUserService.deleteFile: failed to untrack ownership of " + storedFileId + " for " + authUserId, e
            );
        }

        if (ownership.hasMetadata()) {
            this.updateCloudUserBytesUsage(authUserId, -ownership.getSizeBytes());
        }
    }

    /**
     * Creates a new, empty {@link Folder} owned by {@code authUserId} - a plain insert, the same
     * O(1) shape {@link #uploadFile(String, String, byte[], String)} already has for a new file.
     *
     * @param authUserId the owning user's id
     * @param name the new folder's display name
     * @param parentFolderId the parent folder to nest the new folder inside, or {@code null} for the top level
     * @return the newly created {@link Folder}
     * @throws IllegalArgumentException if {@code parentFolderId} is non-null and isn't owned by {@code authUserId}
     */
    @NonNull
    @Override
    public Folder createFolder(@NonNull final String authUserId, @NonNull final String name, @Nullable final String parentFolderId) {

        this.getOrCreate(authUserId);
        if (parentFolderId != null) this.requireOwnedFolder(authUserId, parentFolderId);

        final Folder folder = new Folder(UUID.randomUUID().toString(), authUserId, name, parentFolderId);
        try {
            this.dataFactory.register(folder);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.createFolder: failed to create folder '" + name + "'", e);
        }
        return folder;
    }

    /**
     * Lists every {@link Folder} belonging to {@code authUserId} directly inside {@code
     * parentFolderId} - the same full-scan-then-filter trade-off {@link #listFiles(String)}
     * already documents and accepts.
     *
     * @param authUserId the user whose folders should be listed
     * @param parentFolderId the parent folder to list children of, or {@code null} for the top level
     * @return every {@link Folder} belonging to {@code authUserId} directly inside {@code parentFolderId} (or the top level)
     */
    @NonNull
    @Override
    public List<Folder> listFolders(@NonNull final String authUserId, @Nullable final String parentFolderId) {
        try {
            return this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .filter(folder -> Objects.equals(folder.getParentFolderId(), parentFolderId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listFolders: failed to list folders for " + authUserId, e);
        }
    }

    /**
     * Renames and/or moves {@code folderId} in one step, via a single {@code DataFactory#update}
     * on the resulting copy (see {@link Folder#renamedTo(String)}/{@link Folder#movedTo(String)}).
     * A move is only validated - never blindly trusted - against two failure modes: the target
     * parent must actually belong to {@code authUserId} ({@link #requireOwnedFolder}), and it must
     * not be {@code folderId} itself or one of its own descendants, which would otherwise create a
     * cycle {@link #listFolders}/a client's own tree walk could loop on forever.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to update
     * @param newName the folder's new display name
     * @param newParentFolderId the folder's new parent, or {@code null} to move it to the top level
     * @return the updated {@link Folder}
     * @throws IllegalArgumentException if {@code folderId}/{@code newParentFolderId} (when non-null) isn't owned by {@code authUserId}
     * @throws IllegalStateException if {@code newParentFolderId} is {@code folderId} itself, or one of its own descendants
     */
    @NonNull
    @Override
    public Folder updateFolder(@NonNull final String authUserId, @NonNull final String folderId,
                                @NonNull final String newName, @Nullable final String newParentFolderId) {

        final Folder existing = this.requireOwnedFolder(authUserId, folderId);

        if (newParentFolderId != null) {
            if (newParentFolderId.equals(folderId)) {
                throw new IllegalStateException("@CloudUserService.updateFolder: cannot move " + folderId + " into itself");
            }
            final Folder targetParent = this.requireOwnedFolder(authUserId, newParentFolderId);
            this.requireNotDescendant(authUserId, folderId, targetParent);
        }

        final Folder updated = existing.renamedTo(newName).movedTo(newParentFolderId);
        try {
            this.dataFactory.update(updated);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateFolder: failed to update " + folderId, e);
        }
        return updated;
    }

    /**
     * Walks {@code targetParent}'s own ancestor chain up to the top level, failing if {@code
     * folderId} appears anywhere in it - that would mean {@code targetParent} already sits
     * (transitively) inside {@code folderId}, so moving {@code folderId} to become a child of
     * {@code targetParent} would create a cycle. O(depth of {@code targetParent}); folder
     * nesting is expected to stay shallow enough for this to be cheap.
     *
     * @param authUserId the owning user, used to resolve each ancestor via {@link #requireOwnedFolder}
     * @param folderId the folder being moved, checked for appearing in {@code targetParent}'s own ancestor chain
     * @param targetParent the folder {@code folderId} would be moved into
     * @throws IllegalStateException if {@code folderId} appears in {@code targetParent}'s ancestor chain
     */
    private void requireNotDescendant(final String authUserId, final String folderId, final Folder targetParent) {
        Folder current = targetParent;
        while (current != null) {
            if (current.getFolderId().equals(folderId)) {
                throw new IllegalStateException(
                        "@CloudUserService.updateFolder: cannot move " + folderId
                                + " into its own descendant " + targetParent.getFolderId());
            }
            current = current.getParentFolderId() == null ? null : this.requireOwnedFolder(authUserId, current.getParentFolderId());
        }
    }

    /**
     * Deletes {@code folderId}, but only if {@code authUserId} owns it and it is currently empty.
     * A folder is never deleted recursively - a non-empty folder must be emptied (its children
     * moved out or deleted individually) first.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to delete
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code authUserId}
     * @throws IllegalStateException if {@code folderId} still has child folders or files inside it
     */
    @Override
    public void deleteFolder(@NonNull final String authUserId, @NonNull final String folderId) {

        this.requireOwnedFolder(authUserId, folderId);

        final boolean hasChildFolders = !this.listFolders(authUserId, folderId).isEmpty();
        // A plain ownership-row check, not listFilesWithFolder(...).isEmpty() - this only needs a
        // yes/no answer, so there's no reason to download and decrypt every file's content just to
        // count them.
        final boolean hasChildFiles = this.ownedFileOwnerships(authUserId).stream()
                .anyMatch(ownership -> Objects.equals(ownership.getFolderId(), folderId));
        if (hasChildFolders || hasChildFiles) {
            throw new IllegalStateException("@CloudUserService.deleteFolder: " + folderId + " is not empty");
        }

        try {
            this.dataFactory.delete(folderId, Folder.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteFolder: failed to delete " + folderId, e);
        }
    }

    /**
     * An O(1) point lookup on {@code storedFileId}'s ownership row, failing if {@code authUserId}
     * doesn't own it.
     *
     * @param authUserId the user expected to own the file
     * @param storedFileId the file to check ownership of
     * @return the matching {@link StoredFileOwnership} row
     * @throws IllegalArgumentException if no such ownership row exists for {@code authUserId}/{@code storedFileId}
     */
    private StoredFileOwnership requireOwnedFile(final String authUserId, final String storedFileId) {
        final String ownershipKey = StoredFileOwnership.compositeKey(authUserId, storedFileId);
        final Optional<StoredFileOwnership> ownership;
        try {
            ownership = this.dataFactory.findById(ownershipKey, StoredFileOwnership.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireOwnedFile: failed to look up ownership record " + ownershipKey, e);
        }
        if (ownership.isEmpty()) {
            throw new IllegalArgumentException("@CloudUserService.requireOwnedFile: " + authUserId + " does not own " + storedFileId);
        }
        return ownership.get();
    }

    /**
     * An O(1) point lookup on {@code folderId}, failing if it doesn't exist or belongs to someone
     * other than {@code authUserId}.
     *
     * @param authUserId the user expected to own the folder
     * @param folderId the folder to check ownership of
     * @return the matching {@link Folder}
     * @throws IllegalArgumentException if {@code folderId} doesn't exist or isn't owned by {@code authUserId}
     */
    private Folder requireOwnedFolder(final String authUserId, final String folderId) {
        final Optional<Folder> folder;
        try {
            folder = this.dataFactory.findById(folderId, Folder.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireOwnedFolder: failed to look up folder " + folderId, e);
        }
        if (folder.isEmpty() || !folder.get().getOwnerId().equals(authUserId)) {
            throw new IllegalArgumentException("@CloudUserService.requireOwnedFolder: " + authUserId + " does not own folder " + folderId);
        }
        return folder.get();
    }

    /**
     * Backs {@link #listFiles}/{@link #listFilesWithFolder} - see {@link #listFiles}'s Javadoc for
     * the full-scan trade-off this implies.
     *
     * @param authUserId the user whose ownership rows should be listed
     * @return every {@link StoredFileOwnership} row belonging to {@code authUserId}
     */
    private List<StoredFileOwnership> ownedFileOwnerships(final String authUserId) {
        try {
            return this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getAuthUserId().equals(authUserId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.ownedFileOwnerships: failed to list ownership records for " + authUserId, e);
        }
    }

    /**
     * {@link #ownedFileOwnerships(String)}, mapped down to just each row's {@link StoredFileOwnership#getStoredFileId()}.
     *
     * @param authUserId the user whose owned file ids should be listed
     * @return every {@link StoredFile#fileId()} tracked as belonging to {@code authUserId}
     */
    private List<String> ownedFileIds(final String authUserId) {
        return this.ownedFileOwnerships(authUserId).stream().map(StoredFileOwnership::getStoredFileId).toList();
    }

}
