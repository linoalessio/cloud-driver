package de.lino.cloud.auth.entity;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A single read-only sharing grant: {@code ownerAuthUserId} has given {@code
 * granteeAuthUserId} read access to one specific {@link StoredFile#fileId()} (see {@link
 * de.lino.cloud.auth.CloudUserService#shareFile} for how this is created).
 *
 * <p><b>Separate entity type from folder sharing</b> ({@link SharedFolderGrant}), deliberately -
 * a folder share implies access to everything inside that folder (a meaningfully different
 * semantic from a single-file grant, which only ever names one file), so unifying the two into
 * one entity would either force every folder share to also enumerate every file it covers
 * (reintroducing the exact O(n) membership-list problem {@link StoredFileOwnership} was built to
 * avoid), or require a discriminator field distinguishing "this row names a file" from "this row
 * names a folder" that every reader would still need to branch on anyway - two distinct types are
 * simpler to reason about than either alternative.
 *
 * <p><b>Read-only, deliberately.</b> There is no access-level field: this codebase's first
 * sharing pass grants read access only (see {@code CloudUserService#getFile}'s share-aware
 * lookup) - a caller can never move, rename, delete, or re-share a file via a grant alone, only
 * the file's actual owner can (every mutating {@code CloudUserService} method stays strictly
 * owner-only, unaffected by this class's existence). Write-sharing (letting a grantee also
 * upload/replace a shared file's content) is a documented future extension, not implemented here.
 *
 * <p>Primary-keyed on {@link #compositeKey(String, String)} (grantee + file), the same shape
 * {@link StoredFileOwnership} uses for its own (user, file) composite key - this is what lets
 * {@code CloudUserService} answer "is this file directly shared with me" as an O(1) point lookup
 * rather than a scan. Implements {@link Owned} for consistency with every other entity in this
 * domain ({@link StoredFileOwnership}, {@code Folder}) - like that class, this one is <b>never</b>
 * mounted through {@code DefaultRestFactory}'s generic {@code Owned}-based routes, only through
 * the bespoke {@code /files/{id}/share} routes, so the generic owner-spoof protection never
 * actually applies to it in practice.
 */
@Getter @ToString @EqualsAndHashCode(callSuper = false)
public final class SharedFileGrant extends Serialized implements Owned {

    /** The account this grant was extended to - the only account that can read the file through this grant. */
    private final String granteeAuthUserId;

    /** The {@link StoredFile#fileId()} being shared. */
    private final String storedFileId;

    /** The account that owns {@link #storedFileId} and created this grant. */
    private final String ownerAuthUserId;

    /** When this grant was created, as epoch millis. */
    private final long grantedAtEpochMillis;

    /**
     * Creates a fresh grant, stamping {@link #grantedAtEpochMillis} with the current time.
     *
     * @param granteeAuthUserId the account being granted read access
     * @param storedFileId the file being shared
     * @param ownerAuthUserId the file's actual owner, creating this grant
     */
    public SharedFileGrant(@NotNull final String granteeAuthUserId, @NotNull final String storedFileId,
                            @NotNull final String ownerAuthUserId) {
        this(granteeAuthUserId, storedFileId, ownerAuthUserId, System.currentTimeMillis());
    }

    /**
     * Full constructor, for re-hydrating a grant with a known timestamp (Gson deserialization).
     *
     * @param granteeAuthUserId the account being granted read access
     * @param storedFileId the file being shared
     * @param ownerAuthUserId the file's actual owner, who created this grant
     * @param grantedAtEpochMillis when this grant was created, as epoch millis
     */
    public SharedFileGrant(@NotNull final String granteeAuthUserId, @NotNull final String storedFileId,
                            @NotNull final String ownerAuthUserId, final long grantedAtEpochMillis) {
        this.granteeAuthUserId = Objects.requireNonNull(granteeAuthUserId, "@SharedFileGrant.init: granteeAuthUserId cannot be null");
        this.storedFileId = Objects.requireNonNull(storedFileId, "@SharedFileGrant.init: storedFileId cannot be null");
        this.ownerAuthUserId = Objects.requireNonNull(ownerAuthUserId, "@SharedFileGrant.init: ownerAuthUserId cannot be null");
        this.grantedAtEpochMillis = grantedAtEpochMillis;
    }

    /**
     * @return this record's identifying values - the composite primary key first (see {@link
     * #primaryKey()}), followed by {@link #granteeAuthUserId} and {@link #storedFileId}
     * individually so either can also be used with {@link #hasKey(String)}
     */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(compositeKey(this.granteeAuthUserId, this.storedFileId), this.granteeAuthUserId, this.storedFileId);
    }

    /**
     * @return {@link #ownerAuthUserId} - this grant "belongs to" the file's actual owner, the only
     * account authorized to create or revoke it (see {@link Owned}'s own Javadoc for why this
     * choice, rather than {@link #granteeAuthUserId}, is treated as the owning side here)
     */
    @NotNull
    @Override
    public String ownerId() {
        return this.ownerAuthUserId;
    }

    /**
     * Builds the primary key a {@code SharedFileGrant} for {@code granteeAuthUserId}/{@code
     * storedFileId} is stored under - mirrors {@link StoredFileOwnership#compositeKey(String, String)}.
     *
     * @param granteeAuthUserId the account being granted read access
     * @param storedFileId the file being shared
     * @return the composite primary key, {@code granteeAuthUserId + ":" + storedFileId}
     */
    @NotNull
    public static String compositeKey(@NotNull final String granteeAuthUserId, @NotNull final String storedFileId) {
        Objects.requireNonNull(granteeAuthUserId, "@SharedFileGrant.compositeKey: granteeAuthUserId cannot be null");
        Objects.requireNonNull(storedFileId, "@SharedFileGrant.compositeKey: storedFileId cannot be null");
        return granteeAuthUserId + ":" + storedFileId;
    }

}
