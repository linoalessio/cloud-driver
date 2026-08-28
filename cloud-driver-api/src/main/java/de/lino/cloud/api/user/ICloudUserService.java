package de.lino.cloud.api.user;

import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The behavioral contract {@code CloudUserService} (in {@code cloud-driver-auth})
 * implements - ties an end user (identified by their {@link
 * de.lino.cloud.api.jwt.user.AuthUser#getId()}) to the {@link StoredFile}s they've
 * uploaded, via that user's own {@link ICloudUser} record. Same "{@code I}-prefixed
 * interface, concrete class implements it" shape {@link
 * de.lino.cloud.api.jwt.auth.IAuthService} already uses. Every method takes the
 * authenticated caller's plain {@code authUserId} - not a full {@code AuthUser} -
 * since that's the only thing available once a JWT has been validated, and it's the
 * only thing {@link ICloudUser} itself ever needs.
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
     * @param authUserId the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} whose files to list
     * @return every {@link StoredFile} currently tracked as belonging to {@code authUserId}
     */
    @NotNull
    List<StoredFile> listFiles(@NotNull String authUserId);

    /**
     * Deletes {@code storedFileId} and stops tracking it, but only if {@code authUserId}
     * actually owns it.
     *
     * @param authUserId the requesting user's {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the {@link StoredFile#fileId()} to delete
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    void deleteFile(@NotNull String authUserId, @NotNull String storedFileId);

}
