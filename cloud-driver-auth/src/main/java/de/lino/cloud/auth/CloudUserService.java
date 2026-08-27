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
 * Ties {@link AuthUser} accounts to the {@link StoredFile}s they've uploaded, via each
 * user's own {@link CloudUser} record - see {@link CloudUser}'s own Javadoc for why
 * ownership is tracked as a plain id Set there rather than encoded into the file id
 * itself. Framework-agnostic, same reasoning as {@link AuthService}: every checked
 * exception a delegate call can throw is rewrapped as a plain {@link RuntimeException}
 * rather than declared, since a caller wiring this into an HTTP layer handles failures at
 * that boundary, not here. Every method takes the caller's plain {@code authUserId} -
 * not a full {@link AuthUser} - since that's the only thing available once a JWT has
 * been validated (see {@code DefaultRestFactory#requireValidBearerToken}), and it's the
 * only thing {@link CloudUser} itself ever needs.
 */
public final class CloudUserService implements ICloudUserService {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;

    public CloudUserService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory) {
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
    }

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
     * random id - see {@link CloudUser}'s Javadoc) and tracks it on {@code authUserId}'s
     * {@link CloudUser} record.
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName, final byte[] content) {

        final CloudUser cloudUser = this.getOrCreate(authUserId);
        final StoredFile storedFile = new StoredFile(UUID.randomUUID().toString(), fileName, content);

        try {
            this.fileFactory.upload(storedFile);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.uploadFile: failed to upload '" + fileName + "'", e);
        }

        cloudUser.addStoredFile(storedFile.fileId());
        this.persist(cloudUser, "uploadFile");

        return storedFile;
    }

    @NonNull
    @Override
    public List<StoredFile> listFiles(@NonNull final String authUserId) {
        final CloudUser cloudUser = this.getOrCreate(authUserId);
        try {
            return this.fileFactory.download(cloudUser.getStoredFileIds().toArray(new String[0]));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.listFiles: failed to download files for " + authUserId, e);
        }
    }

    /**
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @Override
    public void deleteFile(@NonNull final String authUserId, @NonNull final String storedFileId) {

        final CloudUser cloudUser = this.getOrCreate(authUserId);
        if (!cloudUser.ownsStoredFile(storedFileId)) {
            throw new IllegalArgumentException(
                    "@CloudUserService.deleteFile: " + authUserId + " does not own " + storedFileId);
        }

        try {
            this.fileFactory.delete(storedFileId);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteFile: failed to delete " + storedFileId, e);
        }

        cloudUser.removeStoredFile(storedFileId);
        this.persist(cloudUser, "deleteFile");
    }

    private void persist(final CloudUser cloudUser, final String callerName) {
        try {
            this.dataFactory.update(cloudUser);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService." + callerName + ": failed to persist CloudUser " + cloudUser.getAuthUserId(), e);
        }
    }

}
