package de.lino.cloud.auth.entity;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.auth.CloudUserService;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Objects;

/**
 * A persisted record identifying one end user in the auth stack, keyed by their own
 * {@link AuthUser#getId()}.
 *
 * <p>File ownership is <strong>not</strong> tracked here anymore - see {@link
 * StoredFileOwnership}'s Javadoc for why embedding an ever-growing {@code
 * Set<String>} of owned file ids on this entity (up to 10,000 per user) made every
 * single file upload/delete pay for an O(n) re-encrypt of every other file id the
 * user already owned. {@link CloudUserService} now manages ownership through
 * dedicated {@link StoredFileOwnership} rows instead, one per (user, file) pair.
 */
@Getter @ToString @EqualsAndHashCode(callSuper = false)
public final class CloudUser extends Serialized implements ICloudUser, Owned {

    /** The owning {@link AuthUser#getId()} - this entity's primary key and its own {@link #ownerId()}. */
    private final String authUserId;

    /**
     * @param authUserId the owning {@link AuthUser#getId()} - not the full entity, since
     *                    this class (and every caller of it, e.g. after JWT validation)
     *                    only ever needs the id, never anything else on {@code AuthUser}
     */
    public CloudUser(@NotNull final String authUserId) {
        this.authUserId = Objects.requireNonNull(authUserId, "@CloudUser.init: authUserId cannot be null");
    }

    @Override
    public @NonNull AuthUser getAuthUser() {
        return CloudDriver.getInstance().getServiceContainer().getAuthService().getAuthUser(this.authUserId).orElseThrow();
    }

    /**
     * Convenience accessor for every {@link StoredFile} this user owns, resolved on demand
     * through the process-wide {@link CloudDriver} singleton rather than held as state on this
     * entity - see {@link CloudUserService#listFiles} for the underlying lookup (including its
     * full-table-scan trade-off) this delegates to.
     *
     * @return an unmodifiable view of every {@link StoredFile} currently tracked as belonging
     *     to this user
     */
    @UnmodifiableView
    public List<StoredFile> getStoredFiles() {
        return CloudDriver.getInstance().getServiceContainer().getCloudUserService().listFiles(this.authUserId);
    }

    /** @return this entity's primary key, {@link #authUserId} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.authUserId);
    }

    /**
     * @return {@link #authUserId} - a {@code CloudUser}'s primary key already
     * is its owning user's id, so this is the same value as {@link #keysOf()}.
     */
    @NotNull
    @Override
    public String ownerId() {
        return this.authUserId;
    }

}
