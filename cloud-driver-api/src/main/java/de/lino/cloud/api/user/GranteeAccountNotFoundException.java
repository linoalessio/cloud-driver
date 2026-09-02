package de.lino.cloud.api.user;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown by {@link ICloudUserService#shareFile}/{@link ICloudUserService#shareFolder}/{@link
 * ICloudUserService#revokeFileShare}/{@link ICloudUserService#revokeFolderShare} when the supplied
 * grantee email address has no registered account - kept as its own type, distinct from the plain
 * {@link IllegalArgumentException} these methods otherwise throw for "you don't own this
 * file/folder"/"it's trashed", specifically so a REST caller can translate this one case into a
 * clear, account-specific message instead of the generic "No StoredFile/Folder with id ..." {@code
 * DefaultRestFactory#folderFailureOrPropagate} otherwise collapses every {@link
 * IllegalArgumentException} on these routes into - which, for this specific case, misleadingly
 * implied the file/folder itself was missing rather than the grantee address being wrong. This was
 * a real, confirmed bug (2026-09-02): a share attempt against a nonexistent grantee email failed
 * with that misleading message, the grant was never persisted, and the caller had no clear signal
 * that the address itself was the problem - the file then unsurprisingly never showed up on the
 * intended recipient's account.
 *
 * <p>Revealing account existence here is intentional, not a new enumeration risk: the caller is
 * already an authenticated account holder sharing their own file, the same "not an anonymous
 * visitor" reasoning {@code AuthService#requestEmailChange}'s own {@code
 * EmailAlreadyRegisteredException} already relies on.
 */
@Getter
public final class GranteeAccountNotFoundException extends RuntimeException {

    /** The email address that has no registered account. */
    private final String email;

    /**
     * @param email the grantee email address that has no registered account
     */
    public GranteeAccountNotFoundException(@NotNull final String email) {
        super("Cloud user account '" + email + "' does not exist");
        this.email = email;
    }

}
