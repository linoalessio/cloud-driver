package de.lino.cloud.api.user;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.user.AuthUser;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The behavioral contract {@code CloudUser} implements - mirrors the "{@code I}-prefixed
 * interface, concrete class implements it" shape {@link
 * de.lino.cloud.api.jwt.auth.IAuthService}/{@link de.lino.cloud.auth.AuthService} already
 * use elsewhere in the auth stack.
 */
public interface ICloudUser {

    /** @return the {@link AuthUser#getId()} this record belongs to, and its own primary key */
    @NotNull
    String getAuthUserId();

    /** @return the plain {@link StoredFile#fileId()} values currently tracked as owned by this user */
    @NotNull
    Set<String> getStoredFileIds();

    /** Tracks {@code storedFileId} as belonging to this user. */
    void addStoredFile(@NotNull String storedFileId);

    /** Stops tracking {@code storedFileId} as belonging to this user (e.g. after deletion). */
    void removeStoredFile(@NotNull String storedFileId);

    /** @return {@code true} if {@code storedFileId} is currently tracked as belonging to this user */
    boolean ownsStoredFile(@NotNull String storedFileId);

}
