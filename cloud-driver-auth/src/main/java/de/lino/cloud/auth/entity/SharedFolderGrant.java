package de.lino.cloud.auth.entity;

import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.SharePermission;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A single read-only sharing grant on a {@link Folder}: {@code ownerAuthUserId} has given {@code
 * granteeAuthUserId} read access to {@code folderId} and - unlike {@link SharedFileGrant} - to
 * everything nested inside it, at any depth (see {@link
 * de.lino.cloud.auth.CloudUserService}'s share-aware file lookup, which walks a file's own folder
 * ancestry checking each ancestor id against this grant type before falling back to "not
 * accessible"). See {@link SharedFileGrant}'s own Javadoc for why this is a separate entity type
 * rather than one unified with it.
 *
 * <p>Read-only, deliberately - same trade-off as {@link SharedFileGrant}: a grantee can browse
 * into a shared folder and read what's inside it, but can never rename/move/delete the folder
 * itself or anything inside it, nor re-share it further. Every mutating {@code CloudUserService}
 * folder method stays strictly owner-only.
 *
 * <p>Primary-keyed on {@link #compositeKey(String, String)} (grantee + folder), the same O(1)
 * point-lookup shape {@link SharedFileGrant} uses.
 */
@Getter @ToString @EqualsAndHashCode(callSuper = false)
public final class SharedFolderGrant extends Serialized implements Owned {

    /** The account this grant was extended to. */
    private final String granteeAuthUserId;

    /** The {@link Folder#getFolderId()} being shared, along with everything nested inside it. */
    private final String folderId;

    /** The account that owns {@link #folderId} and created this grant. */
    private final String ownerAuthUserId;

    /** When this grant was created, as epoch millis. */
    private final long grantedAtEpochMillis;

    /**
     * This grant's access level - see {@code SharedFileGrant#permissionLevel}'s own Javadoc for
     * the legacy-row/null-handling convention. <b>{@link SharePermission#EDIT} carries no actual
     * behavior change on a folder grant in this pass</b> - see {@link SharePermission}'s own
     * Javadoc for why (real ownership-attribution questions around "who owns a file a grantee
     * uploads into someone else's shared folder" are deliberately left unresolved rather than
     * guessed at). This field exists for forward compatibility/API symmetry with {@code
     * SharedFileGrant} - nothing in {@code CloudUserService} currently branches on it.
     */
    @Nullable
    private final SharePermission permissionLevel;

    /** When this grant expires, as epoch millis, or {@code null} if it never does - see {@code SharedFileGrant#expiresAtEpochMillis}'s own Javadoc. */
    @Nullable
    private final Long expiresAtEpochMillis;

    /**
     * Same as {@link #SharedFolderGrant(String, String, String, SharePermission, Long)}, granting
     * {@link SharePermission#VIEW} with no expiry - the shape every pre-existing caller (and every
     * row written before permission levels/expiry existed) already uses.
     *
     * @param granteeAuthUserId the account being granted read access
     * @param folderId the folder being shared
     * @param ownerAuthUserId the folder's actual owner, creating this grant
     */
    public SharedFolderGrant(@NotNull final String granteeAuthUserId, @NotNull final String folderId,
                              @NotNull final String ownerAuthUserId) {
        this(granteeAuthUserId, folderId, ownerAuthUserId, SharePermission.VIEW, null);
    }

    /**
     * Creates a fresh grant, stamping {@link #grantedAtEpochMillis} with the current time.
     *
     * @param granteeAuthUserId the account being granted access
     * @param folderId the folder being shared
     * @param ownerAuthUserId the folder's actual owner, creating this grant
     * @param permissionLevel this grant's access level
     * @param expiresAtEpochMillis when this grant expires, as epoch millis, or {@code null} to never expire
     */
    public SharedFolderGrant(@NotNull final String granteeAuthUserId, @NotNull final String folderId,
                              @NotNull final String ownerAuthUserId, @NotNull final SharePermission permissionLevel,
                              @Nullable final Long expiresAtEpochMillis) {
        this(granteeAuthUserId, folderId, ownerAuthUserId, System.currentTimeMillis(), permissionLevel, expiresAtEpochMillis);
    }

    /**
     * Full constructor, for re-hydrating a grant with a known timestamp (Gson deserialization).
     *
     * @param granteeAuthUserId the account being granted access
     * @param folderId the folder being shared
     * @param ownerAuthUserId the folder's actual owner, who created this grant
     * @param grantedAtEpochMillis when this grant was created, as epoch millis
     * @param permissionLevel this grant's access level, or {@code null} for a legacy {@link SharePermission#VIEW} row
     * @param expiresAtEpochMillis when this grant expires, as epoch millis, or {@code null} to never expire
     */
    public SharedFolderGrant(@NotNull final String granteeAuthUserId, @NotNull final String folderId,
                              @NotNull final String ownerAuthUserId, final long grantedAtEpochMillis,
                              @Nullable final SharePermission permissionLevel, @Nullable final Long expiresAtEpochMillis) {
        this.granteeAuthUserId = Objects.requireNonNull(granteeAuthUserId, "@SharedFolderGrant.init: granteeAuthUserId cannot be null");
        this.folderId = Objects.requireNonNull(folderId, "@SharedFolderGrant.init: folderId cannot be null");
        this.ownerAuthUserId = Objects.requireNonNull(ownerAuthUserId, "@SharedFolderGrant.init: ownerAuthUserId cannot be null");
        this.grantedAtEpochMillis = grantedAtEpochMillis;
        this.permissionLevel = permissionLevel;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    /** @return {@link #permissionLevel}, or {@link SharePermission#VIEW} if this is a legacy row predating permission levels */
    @NotNull
    public SharePermission permissionLevel() {
        return this.permissionLevel != null ? this.permissionLevel : SharePermission.VIEW;
    }

    /** @return {@code true} if {@link #expiresAtEpochMillis} is set and in the past - an expired grant is treated as if it doesn't exist at all */
    public boolean isExpired() {
        return this.expiresAtEpochMillis != null && this.expiresAtEpochMillis < System.currentTimeMillis();
    }

    /**
     * @return this record's identifying values - the composite primary key first, followed by
     * {@link #granteeAuthUserId} and {@link #folderId} individually so either can also be used
     * with {@link #hasKey(String)}
     */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(compositeKey(this.granteeAuthUserId, this.folderId), this.granteeAuthUserId, this.folderId);
    }

    /** @return {@link #ownerAuthUserId} - see {@link SharedFileGrant#ownerId()}'s Javadoc for the same reasoning. */
    @NotNull
    @Override
    public String ownerId() {
        return this.ownerAuthUserId;
    }

    /**
     * Builds the primary key a {@code SharedFolderGrant} for {@code granteeAuthUserId}/{@code
     * folderId} is stored under.
     *
     * @param granteeAuthUserId the account being granted read access
     * @param folderId the folder being shared
     * @return the composite primary key, {@code granteeAuthUserId + ":" + folderId}
     */
    @NotNull
    public static String compositeKey(@NotNull final String granteeAuthUserId, @NotNull final String folderId) {
        Objects.requireNonNull(granteeAuthUserId, "@SharedFolderGrant.compositeKey: granteeAuthUserId cannot be null");
        Objects.requireNonNull(folderId, "@SharedFolderGrant.compositeKey: folderId cannot be null");
        return granteeAuthUserId + ":" + folderId;
    }

}
