package de.lino.cloud.api.user;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.user.AuthUser;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;

/**
 * The behavioral contract {@code CloudUser} implements - mirrors the "{@code I}-prefixed
 * interface, concrete class implements it" shape {@link
 * de.lino.cloud.api.jwt.auth.IAuthService}/{@link de.lino.cloud.auth.AuthService} already
 * use elsewhere in the auth stack.
 *
 * <p>File ownership is deliberately not part of this contract - see {@code
 * StoredFileOwnership}'s Javadoc (in {@code cloud-driver-auth}) for why it is tracked
 * through dedicated per-(user, file) records via {@link ICloudUserService} instead of
 * as state on the user record itself.
 */
public interface ICloudUser {

    /** @return the {@link AuthUser#getId()} this record belongs to, and its own primary key */
    @NotNull
    String getAuthUserId();

    @NonNull
    AuthUser getAuthUser();

    long getTimeStamp();

    long getMaxBytesToUpload();

    /** This account's persisted, incrementally-tracked running total - see {@code CloudUserService#updateCloudUserBytesUsage}. */
    long getCurrentUploadedBytes();

    void setMaxBytesToUpload(final long maxBytesToUpload);

    /** Backs {@code CloudUserService#updateCloudUserBytesUsage} - not meant to be called directly by other callers. */
    void setCurrentUploadedBytes(final long currentUploadedBytes);

    boolean isUploadLimitReached(final long bytesToUpload);

    /**
     * Every {@link StoredFile} currently owned by this user, resolved on demand from the
     * per-(user, file) ownership records described in this interface's own Javadoc - not state
     * held on this record itself, so the returned view reflects an unmodifiable, point-in-time
     * snapshot rather than a live, mutable collection.
     *
     * @return an unmodifiable view of every {@link StoredFile} this user currently owns
     */
    @NotNull
    @UnmodifiableView
    List<StoredFile> getStoredFiles();

}
