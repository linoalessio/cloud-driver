package de.lino.cloud.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.FileWithFolder;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
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
            if (authUser.isEmpty()) return Optional.empty();
            return this.getCloudUser(authUser.get().getId());

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
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName, final byte[] content,
                                  @Nullable final String folderId) {

        this.getOrCreate(authUserId);
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final StoredFile storedFile = new StoredFile(UUID.randomUUID().toString(), fileName, content);

        try {
            this.fileFactory.upload(storedFile);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.uploadFile: failed to upload '" + fileName + "'", e);
        }

        try {
            this.dataFactory.register(new StoredFileOwnership(authUserId, storedFile.fileId(), folderId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(
                    "@CloudUserService.uploadFile: failed to track ownership of " + storedFile.fileId() + " for " + authUserId, e
            );
        }

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

        final String ownershipKey = StoredFileOwnership.compositeKey(authUserId, storedFileId);
        if (!this.ownsFile(ownershipKey)) {
            throw new IllegalArgumentException(
                    "@CloudUserService.deleteFile: " + authUserId + " does not own " + storedFileId);
        }

        try {
            this.fileFactory.delete(storedFileId);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteFile: failed to delete " + storedFileId, e);
        }

        try {
            this.dataFactory.delete(ownershipKey, StoredFileOwnership.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException(
                    "@CloudUserService.deleteFile: failed to untrack ownership of " + storedFileId + " for " + authUserId, e
            );
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
        final boolean hasChildFiles = !this.listFilesWithFolder(authUserId, folderId).isEmpty();
        if (hasChildFolders || hasChildFiles) {
            throw new IllegalStateException("@CloudUserService.deleteFolder: " + folderId + " is not empty");
        }

        try {
            this.dataFactory.delete(folderId, Folder.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteFolder: failed to delete " + folderId, e);
        }
    }

    /** O(1) point lookup - a single row fetch under the composite {@code authUserId:storedFileId} key. */
    private boolean ownsFile(final String ownershipKey) {
        try {
            return this.dataFactory.findById(ownershipKey, StoredFileOwnership.class).isPresent();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.ownsFile: failed to look up ownership record " + ownershipKey, e);
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

    /** Backs {@link #listFiles}/{@link #listFilesWithFolder} - see {@link #listFiles}'s Javadoc for the full-scan trade-off this implies. */
    private List<StoredFileOwnership> ownedFileOwnerships(final String authUserId) {
        try {
            return this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getAuthUserId().equals(authUserId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.ownedFileOwnerships: failed to list ownership records for " + authUserId, e);
        }
    }

    /** {@link #ownedFileOwnerships(String)}, mapped down to just each row's {@link StoredFileOwnership#getStoredFileId()}. */
    private List<String> ownedFileIds(final String authUserId) {
        return this.ownedFileOwnerships(authUserId).stream().map(StoredFileOwnership::getStoredFileId).toList();
    }

}
