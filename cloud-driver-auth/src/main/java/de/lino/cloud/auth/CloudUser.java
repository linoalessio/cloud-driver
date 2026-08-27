package de.lino.cloud.auth;

import com.google.common.collect.Sets;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A persisted, per-{@link AuthUser} index of that user's own {@link StoredFile}s.
 * {@link #storedFileIds} holds plain {@link StoredFile#fileId()} values, unmodified - a
 * file's own id never encodes its owner. Ownership is expressed purely by Set membership
 * here: looking up "does this user own file X" means checking this Set, not parsing the
 * id itself, and a {@code StoredFile}'s id stays a normal, independent identifier like
 * every other entity in this codebase.
 */
@Getter @ToString @EqualsAndHashCode(callSuper = false)
public final class CloudUser extends Serialized implements ICloudUser {

    private final String authUserId;

    /** Plain {@link StoredFile#fileId()} values this user owns. */
    private final Set<String> storedFileIds;

    /**
     * @param authUserId the owning {@link AuthUser#getId()} - not the full entity, since
     *                    this class (and every caller of it, e.g. after JWT validation)
     *                    only ever needs the id, never anything else on {@code AuthUser}
     */
    public CloudUser(@NotNull final String authUserId) {
        this.authUserId = Objects.requireNonNull(authUserId, "@CloudUser.init: authUserId cannot be null");
        this.storedFileIds = Sets.newConcurrentHashSet();
    }

    /** Tracks {@code storedFileId} as belonging to this user. */
    @Override
    public void addStoredFile(@NotNull final String storedFileId) {
        this.storedFileIds.add(Objects.requireNonNull(storedFileId, "@CloudUser.addStoredFile: storedFileId cannot be null"));
    }

    /** Stops tracking {@code storedFileId} as belonging to this user (e.g. after deletion). */
    @Override
    public void removeStoredFile(@NotNull final String storedFileId) {
        this.storedFileIds.remove(storedFileId);
    }

    /** @return {@code true} if {@code storedFileId} is currently tracked as belonging to this user */
    @Override
    public boolean ownsStoredFile(@NotNull final String storedFileId) {
        return this.storedFileIds.contains(storedFileId);
    }

    /** @return this entity's primary key, {@link #authUserId} */
    @Override
    public List<String> keysOf() {
        return List.of(this.authUserId);
    }

}
