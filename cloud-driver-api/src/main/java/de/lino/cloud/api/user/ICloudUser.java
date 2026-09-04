package de.lino.cloud.api.user;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.user.AuthUser;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

    /**
     * Resolves the full {@link AuthUser} account this record belongs to, looked up on demand
     * (not held as state on this record) through the process-wide {@code CloudDriver} singleton.
     *
     * @return the {@link AuthUser} identified by {@link #getAuthUserId()}
     */
    @NonNull
    AuthUser getAuthUser();

    /**
     * @return the epoch-millisecond timestamp this record was created at (set once, at
     *     construction) - effectively the owning account's creation/confirmation time
     */
    long getTimeStamp();

    /**
     * @return the maximum total number of bytes this account is allowed to have uploaded at once,
     *     read from configuration at construction time (see the implementing class for the exact
     *     key and its default when unset)
     */
    long getMaxBytesToUpload();

    /** This account's persisted, incrementally-tracked running total - see {@code CloudUserService#updateCloudUserBytesUsage}. */
    long getCurrentUploadedBytes();

    /**
     * Updates this account's upload quota ceiling.
     *
     * @param maxBytesToUpload the new maximum total number of bytes this account may have uploaded at once
     */
    void setMaxBytesToUpload(final long maxBytesToUpload);

    /** Backs {@code CloudUserService#updateCloudUserBytesUsage} - not meant to be called directly by other callers. */
    void setCurrentUploadedBytes(final long currentUploadedBytes);

    /**
     * @return this account's stored light/dark theme preference (e.g. {@code "LIGHT"}/{@code
     *     "DARK"}), synced across every device signed into this account - {@code null} if never
     *     explicitly set, in which case a client should fall back to its own local/system default
     */
    @Nullable
    String getThemeMode();

    /** Backs {@code CloudUserService#updateThemePreference} - not meant to be called directly by other callers. */
    void setThemeMode(@Nullable final String themeMode);

    /**
     * Reports whether uploading {@code bytesToUpload} more bytes would meet or exceed this
     * account's quota - {@code (getCurrentUploadedBytes() + bytesToUpload) >= getMaxBytesToUpload()},
     * so a request landing exactly on the remaining quota is rejected too, not just one that exceeds it.
     *
     * @param bytesToUpload the number of additional bytes a caller is proposing to upload
     * @return {@code true} if the upload would meet or exceed {@link #getMaxBytesToUpload()}
     */
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
