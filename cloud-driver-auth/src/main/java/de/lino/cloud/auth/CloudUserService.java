package de.lino.cloud.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.user.ICloudUserService;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ties {@link AuthUser} accounts to the {@link StoredFile}s they've uploaded. Each
 * user's own {@link CloudUser} record only identifies the user; ownership of
 * individual files is tracked separately, one {@link StoredFileOwnership} row per
 * (user, file) pair - see that class's Javadoc for why. Framework-agnostic, same
 * reasoning as {@link AuthService}: every checked exception a delegate call can throw
 * is rewrapped as a plain {@link RuntimeException} rather than declared, since a
 * caller wiring this into an HTTP layer handles failures at that boundary, not here.
 * Every method takes the caller's plain {@code authUserId} - not a full {@link
 * AuthUser} - since that's the only thing available once a JWT has been validated
 * (see {@code DefaultRestFactory#requireValidBearerToken}).
 */
public final class CloudUserService implements ICloudUserService {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;

    /**
     * Creates a {@code CloudUserService} backed by the given collaborators.
     *
     * @param dataFactory persists/looks up {@link CloudUser} and {@link StoredFileOwnership} rows
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
    public CloudUser getOrCreate(@NonNull final String authUserId) {
        try {
            final Optional<CloudUser> existing = this.dataFactory.findById(authUserId, CloudUser.class);
            if (existing.isPresent()) {
                return existing.get();
            }
            final CloudUser cloudUser = new CloudUser(authUserId);
            this.dataFactory.register(cloudUser);
            return cloudUser;
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.getOrCreate: failed to look up/create CloudUser for " + authUserId, e);
        }
    }

    /**
     * Uploads {@code fileName}/{@code content} as a new {@link StoredFile} (a fresh,
     * random id) and tracks it as owned by {@code authUserId} via a single new {@link
     * StoredFileOwnership} row - a plain insert, not a rewrite of any existing data,
     * regardless of how many files {@code authUserId} already owns.
     *
     * @param authUserId the uploading user's id, tracked as the new file's owner
     * @param fileName the file's name, used to infer its content type and preserved on download
     * @param content the file's raw bytes
     * @return the newly-created {@link StoredFile}
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName, final byte[] content) {

        this.getOrCreate(authUserId);
        final StoredFile storedFile = new StoredFile(UUID.randomUUID().toString(), fileName, content);

        try {
            this.fileFactory.upload(storedFile);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.uploadFile: failed to upload '" + fileName + "'", e);
        }

        try {
            this.dataFactory.register(new StoredFileOwnership(authUserId, storedFile.fileId()));
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
     * memory. Each row is tiny (two ids) and decrypted concurrently (see {@code
     * EntityDatabaseClient#retrieveAll}), so this is still far cheaper than the old
     * single-blob-of-10,000-ids design on the read side, and this method is called far
     * less often than {@link #uploadFile}/{@link #deleteFile}. If the number of
     * ownership rows system-wide grows large enough for this scan itself to matter,
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

    /** O(1) point lookup - a single row fetch under the composite {@code authUserId:storedFileId} key. */
    private boolean ownsFile(final String ownershipKey) {
        try {
            return this.dataFactory.findById(ownershipKey, StoredFileOwnership.class).isPresent();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.ownsFile: failed to look up ownership record " + ownershipKey, e);
        }
    }

    /** Backs {@link #listFiles} - see that method's Javadoc for the full-scan trade-off this implies. */
    private List<String> ownedFileIds(final String authUserId) {
        try {
            return this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getAuthUserId().equals(authUserId))
                    .map(StoredFileOwnership::getStoredFileId)
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.ownedFileIds: failed to list ownership records for " + authUserId, e);
        }
    }

}
