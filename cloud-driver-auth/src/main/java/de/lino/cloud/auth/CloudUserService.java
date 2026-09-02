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
import de.lino.cloud.api.utility.CursorPage;
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
        // Bypasses the trash entirely (hardDeleteFile), regardless of each file's current
        // deleteFile/restoreFile trash state - this operation's whole point is to actually empty
        // the account, not move everything into (or leave it sitting in) the trash.
        for (final StoredFileOwnership ownership : this.ownedFileOwnershipsIncludingDeleted(authUserId)) {
            this.hardDeleteFile(authUserId, ownership);
        }
        this.deleteAllOwnedFolders(authUserId);
    }

    /**
     * Permanently removes {@code storedFileId}'s content and ownership tracking, bypassing the
     * trash {@link #deleteFile}/{@link #restoreFile} normally goes through entirely - the same
     * delete/decrement sequence {@link #deleteFile} performed before soft delete existed. Used by
     * {@link #resetCloudUser(String)} (which must actually empty the account, not fill its trash)
     * and by a future purge job for records past their retention window.
     *
     * @param authUserId the owning user, whose usage total is decremented if {@code ownership} carries metadata
     * @param ownership the ownership row to permanently remove
     */
    private void hardDeleteFile(final String authUserId, final StoredFileOwnership ownership) {
        final String storedFileId = ownership.getStoredFileId();
        try {
            this.fileFactory.delete(storedFileId);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.hardDeleteFile: failed to delete " + storedFileId, e);
        }
        try {
            this.dataFactory.delete(StoredFileOwnership.compositeKey(authUserId, storedFileId), StoredFileOwnership.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException(
                    "@CloudUserService.hardDeleteFile: failed to untrack ownership of " + storedFileId + " for " + authUserId, e
            );
        }
        if (ownership.hasMetadata()) {
            this.updateCloudUserBytesUsage(authUserId, -ownership.getSizeBytes());
        }
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

    @Override
    public void updateCloudUserBytesLimit(@NonNull String authUserId, long bytes) {

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

    /** Downloads every file in {@code ownerships} and pairs each with its recorded {@link StoredFileOwnership#getFolderId()}. */
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

    /** {@link #resolveFileSummary}, applied to every entry. */
    private List<StoredFileSummary> resolveFileSummaries(final List<StoredFileOwnership> ownerships) {
        return ownerships.stream().map(this::resolveFileSummary).toList();
    }

    /**
     * See {@link ICloudUserService#listFileSummariesPage}'s Javadoc. Resolves the same
     * full-scan/filter list {@link #listFileSummaries(String, String)} does, sorts it by {@link
     * StoredFileSummary#fileId()}, then slices out one page via {@link #paginate}.
     */
    @NonNull
    @Override
    public CursorPage<StoredFileSummary> listFileSummariesPage(@NonNull final String authUserId, @Nullable final String folderId,
                                                                @Nullable final String cursor, final int limit) {
        final List<StoredFileOwnership> filtered = this.ownedFileOwnerships(authUserId).stream()
                .filter(ownership -> Objects.equals(ownership.getFolderId(), folderId))
                .toList();
        final List<StoredFileSummary> sorted = this.resolveFileSummaries(filtered).stream()
                .sorted(Comparator.comparing(StoredFileSummary::fileId))
                .toList();
        return paginate(sorted, cursor, limit, StoredFileSummary::fileId);
    }

    /**
     * Generic keyset-pagination slice over an already-fully-materialized, ascending-{@code
     * keyExtractor}-sorted list - the same "{@code WHERE key > cursor ORDER BY key LIMIT limit}"
     * shape {@code DatabaseBackupScheduler#fetchBatch} applies at the SQL level, applied here at
     * the application level instead (see {@link CursorPage}'s Javadoc for why a real SQL-level
     * cursor isn't available for these owner-scoped, encrypted rows).
     *
     * @param sorted       the full result set, already sorted ascending by {@code keyExtractor}
     * @param cursor       the previous page's {@link CursorPage#nextCursor()}, or {@code null} for the first page
     * @param limit        the maximum number of entries to return; must be positive
     * @param keyExtractor extracts the stable sort/cursor key from one element
     */
    private static <T> CursorPage<T> paginate(final List<T> sorted, @Nullable final String cursor,
                                               final int limit, final java.util.function.Function<T, String> keyExtractor) {
        if (limit <= 0) {
            throw new IllegalArgumentException("@CloudUserService.paginate: limit must be positive, was " + limit);
        }
        final List<T> afterCursor = cursor == null
                ? sorted
                : sorted.stream().filter(item -> keyExtractor.apply(item).compareTo(cursor) > 0).toList();
        final boolean hasMore = afterCursor.size() > limit;
        final List<T> page = afterCursor.subList(0, Math.min(limit, afterCursor.size()));
        final String nextCursor = hasMore ? keyExtractor.apply(page.get(page.size() - 1)) : null;
        return new CursorPage<>(page, nextCursor);
    }

    /**
     * Builds one {@link StoredFileSummary} straight from {@code ownership}'s own fields - unless
     * it predates metadata capture ({@link StoredFileOwnership#hasMetadata()} {@code false}), in
     * which case this falls back to downloading the full {@link StoredFile} exactly once,
     * persisting a {@link StoredFileOwnership#withMetadata(StoredFile)} copy so every later call
     * for this same row takes the fast, no-download path.
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
        if (ownership.isDeleted()) {
            // Hidden from a normal fetch the same "don't confirm existence" way an unowned file
            // already is - a trashed file is only reachable via listDeletedFiles/restoreFile.
            throw new IllegalArgumentException("@CloudUserService.getFile: " + authUserId + " does not own " + storedFileId);
        }
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
     * Soft-deletes (moves to the trash) {@code storedFileId}, but only if {@code authUserId}
     * actually owns it - content and ownership tracking are left untouched, only {@link
     * StoredFileOwnership#isDeleted()} flips, via a single-row {@link DataFactory#update} (see
     * {@link StoredFileOwnership#deletedAtEpochMillis}'s own Javadoc for why this row, not the
     * underlying {@link StoredFile}, carries the flag {@link CloudUserService} actually checks).
     * Idempotent - a no-op if {@code storedFileId} is already in the trash. Does <b>not</b>
     * decrement the owner's usage total - the file's bytes still occupy storage until a purge job
     * (or {@link #resetCloudUser(String)}, via {@link #hardDeleteFile}) actually removes it; see
     * {@link #restoreFile(String, String)} for the reverse.
     *
     * @param authUserId the caller's own id, checked against the ownership record
     * @param storedFileId the file to trash
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @Override
    public void deleteFile(@NonNull final String authUserId, @NonNull final String storedFileId) {
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);
        if (ownership.isDeleted()) {
            return;
        }
        try {
            this.dataFactory.update(ownership.markedDeleted());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.deleteFile: failed to trash " + storedFileId, e);
        }
    }

    /**
     * Restores a previously soft-deleted {@code storedFileId} out of the trash, but only if
     * {@code authUserId} actually owns it - the reverse of {@link #deleteFile(String, String)}.
     *
     * @param authUserId the caller's own id, checked against the ownership record
     * @param storedFileId the file to restore
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code storedFileId} is not currently in the trash
     */
    @Override
    public void restoreFile(@NonNull final String authUserId, @NonNull final String storedFileId) {
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);
        if (!ownership.isDeleted()) {
            throw new IllegalStateException("@CloudUserService.restoreFile: " + storedFileId + " is not in the trash");
        }
        try {
            this.dataFactory.update(ownership.restored());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.restoreFile: failed to restore " + storedFileId, e);
        }
    }

    /**
     * Lists every file currently in {@code authUserId}'s trash, as {@link StoredFileSummary}s -
     * same descriptive-fields-only shape/cost as {@link #listFileSummaries(String)}, just filtered
     * to trashed rows instead of live ones.
     *
     * @param authUserId the user whose trash to list
     * @return a {@link StoredFileSummary} for every file currently in {@code authUserId}'s trash
     */
    @NonNull
    @Override
    public List<StoredFileSummary> listDeletedFiles(@NonNull final String authUserId) {
        final List<StoredFileOwnership> deleted = this.ownedFileOwnershipsIncludingDeleted(authUserId).stream()
                .filter(StoredFileOwnership::isDeleted)
                .toList();
        return this.resolveFileSummaries(deleted);
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
                    .filter(folder -> !folder.isDeleted())
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listFolders: failed to list folders for " + authUserId, e);
        }
    }

    /**
     * See {@link ICloudUserService#listFoldersPage}'s Javadoc. Same full-scan-then-sort-then-slice
     * shape as {@link #listFileSummariesPage}, keyed on {@link Folder#getFolderId()}.
     */
    @NonNull
    @Override
    public CursorPage<Folder> listFoldersPage(@NonNull final String authUserId, @Nullable final String parentFolderId,
                                               @Nullable final String cursor, final int limit) {
        final List<Folder> sorted;
        try {
            sorted = this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .filter(folder -> Objects.equals(folder.getParentFolderId(), parentFolderId))
                    .filter(folder -> !folder.isDeleted())
                    .sorted(Comparator.comparing(Folder::getFolderId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listFoldersPage: failed to list folders for " + authUserId, e);
        }
        return paginate(sorted, cursor, limit, Folder::getFolderId);
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
     * Soft-deletes (moves to the trash) {@code folderId}, but only if {@code authUserId} owns it
     * and it is currently empty of non-trashed content. A folder is never deleted recursively - a
     * non-empty folder must be emptied (its children moved out or deleted individually) first.
     * Idempotent - a no-op if {@code folderId} is already in the trash.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to delete
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code authUserId}
     * @throws IllegalStateException if {@code folderId} still has non-trashed child folders or files inside it
     */
    @Override
    public void deleteFolder(@NonNull final String authUserId, @NonNull final String folderId) {

        final Folder existing = this.requireOwnedFolder(authUserId, folderId);
        if (existing.isDeleted()) {
            return;
        }

        final boolean hasChildFolders = !this.listFolders(authUserId, folderId).isEmpty();
        // A plain ownership-row check, not listFilesWithFolder(...).isEmpty() - this only needs a
        // yes/no answer, so there's no reason to download and decrypt every file's content just to
        // count them. Both listFolders and ownedFileOwnerships already exclude trashed entries, so
        // a folder containing only already-trashed children is treated as empty here.
        final boolean hasChildFiles = this.ownedFileOwnerships(authUserId).stream()
                .anyMatch(ownership -> Objects.equals(ownership.getFolderId(), folderId));
        if (hasChildFolders || hasChildFiles) {
            throw new IllegalStateException("@CloudUserService.deleteFolder: " + folderId + " is not empty");
        }

        try {
            this.dataFactory.update(existing.markedDeleted());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.deleteFolder: failed to trash " + folderId, e);
        }
    }

    /**
     * Restores a previously soft-deleted {@code folderId} out of the trash, but only if {@code
     * authUserId} owns it - the reverse of {@link #deleteFolder(String, String)}. Does not
     * validate {@code folderId}'s own parent - see this method's own {@link
     * ICloudUserService#restoreFolder} Javadoc for why that's an accepted trade-off.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to restore
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code authUserId}
     * @throws IllegalStateException if {@code folderId} is not currently in the trash
     */
    @Override
    public void restoreFolder(@NonNull final String authUserId, @NonNull final String folderId) {
        final Folder existing = this.requireOwnedFolder(authUserId, folderId);
        if (!existing.isDeleted()) {
            throw new IllegalStateException("@CloudUserService.restoreFolder: " + folderId + " is not in the trash");
        }
        try {
            this.dataFactory.update(existing.restored());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.restoreFolder: failed to restore " + folderId, e);
        }
    }

    /**
     * Lists every {@link Folder} currently in {@code authUserId}'s trash, regardless of nesting.
     *
     * @param authUserId the user whose trash to list
     * @return every {@link Folder} currently in {@code authUserId}'s trash
     */
    @NonNull
    @Override
    public List<Folder> listDeletedFolders(@NonNull final String authUserId) {
        try {
            return this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .filter(Folder::isDeleted)
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listDeletedFolders: failed to list trashed folders for " + authUserId, e);
        }
    }

    /** Same O(1) point lookup as {@link #ownsFile}, returning the row itself rather than a boolean. */
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

    /** O(1) point lookup, failing if {@code folderId} doesn't exist or belongs to someone other than {@code authUserId}. */
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
     * Backs {@link #listFiles}/{@link #listFilesWithFolder}/{@link #listFileSummaries}/{@link
     * #deleteFolder}'s emptiness check - see {@link #listFiles}'s Javadoc for the full-scan
     * trade-off this implies. Excludes trashed rows by default; see {@link
     * #ownedFileOwnershipsIncludingDeleted(String)} for the raw, unfiltered scan.
     */
    private List<StoredFileOwnership> ownedFileOwnerships(final String authUserId) {
        return this.ownedFileOwnershipsIncludingDeleted(authUserId).stream()
                .filter(ownership -> !ownership.isDeleted())
                .toList();
    }

    /**
     * Same full scan as {@link #ownedFileOwnerships(String)}, without the trash filter - backs
     * {@link #listDeletedFiles(String)} and {@link #resetCloudUser(String)} (which must reach
     * already-trashed rows too, to actually purge them via {@link #hardDeleteFile}).
     */
    private List<StoredFileOwnership> ownedFileOwnershipsIncludingDeleted(final String authUserId) {
        try {
            return this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getAuthUserId().equals(authUserId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.ownedFileOwnershipsIncludingDeleted: failed to list ownership records for " + authUserId, e);
        }
    }

    /** {@link #ownedFileOwnerships(String)}, mapped down to just each row's {@link StoredFileOwnership#getStoredFileId()}. */
    private List<String> ownedFileIds(final String authUserId) {
        return this.ownedFileOwnerships(authUserId).stream().map(StoredFileOwnership::getStoredFileId).toList();
    }

}
