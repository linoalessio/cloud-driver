package de.lino.cloud.auth.entity;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.auth.CloudUserService;
import de.lino.database.database.entity.Serialized;
import de.lino.database.json.JsonDocument;
import lombok.*;
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
@Getter @Setter
@ToString @EqualsAndHashCode(callSuper = false)
public final class CloudUser extends Serialized implements ICloudUser, Owned {

    /** The owning {@link AuthUser#getId()} - this entity's primary key and its own {@link #ownerId()}. */
    private final String authUserId;

    private final long timeStamp;

    private long maxBytesToUpload;

    /**
     * Incrementally-tracked running total of this user's uploaded bytes - updated via {@link
     * CloudUserService#updateCloudUserBytesUsage} (+{@code content.length} on a successful
     * upload, -the deleted file's known size on a successful delete) rather than recomputed by
     * scanning every owned file on each check, so {@link #isUploadLimitReached} stays O(1). Not
     * backfilled for accounts that already had files before this field existed - such an account
     * starts at {@code 0} and only reflects usage from uploads/deletes made after this feature
     * shipped, until a proper backfill is run.
     */
    private long currentUploadedBytes;

    /**
     * {@code configuration.json} key {@link #resolveMaxBytesToUpload} reads {@link
     * #maxBytesToUpload} from - a sibling of {@code "jwt-signing-key"}/{@code "rest-server-port"}.
     */
    private static final String MAX_BYTES_TO_UPLOAD_CONFIG_KEY = "cloud-user-max-bytes-to-upload";

    /**
     * @param authUserId the owning {@link AuthUser#getId()} - not the full entity, since
     *                    this class (and every caller of it, e.g. after JWT validation)
     *                    only ever needs the id, never anything else on {@code AuthUser}
     */
    public CloudUser(@NotNull final String authUserId) {
        this.authUserId = Objects.requireNonNull(authUserId, "@CloudUser.init: authUserId cannot be null");
        this.timeStamp = System.currentTimeMillis();
        this.maxBytesToUpload = resolveMaxBytesToUpload();
    }

    /**
     * Reads {@link #MAX_BYTES_TO_UPLOAD_CONFIG_KEY} from {@link CloudDriver#getConfiguration()},
     * defaulting to {@link Long#MAX_VALUE} (effectively unlimited) if it isn't set - {@link
     * JsonDocument#getLong} throws {@link NullPointerException} on a missing key rather than
     * returning a default, so every account confirmation/upload would otherwise start throwing
     * the moment this constructor ran against a {@code configuration.json} that predates this
     * quota feature (every existing deployment's, until an operator explicitly opts in). Checked
     * via {@link JsonDocument#contains} first so an unset quota fails open, not closed - matching
     * this codebase's existing convention for optional configuration (e.g. a missing {@code
     * "smtp-host"} falls back to {@code LoggingEmailSender} rather than breaking registration).
     */
    private static long resolveMaxBytesToUpload() {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        return configuration.contains(MAX_BYTES_TO_UPLOAD_CONFIG_KEY)
                ? configuration.getLong(MAX_BYTES_TO_UPLOAD_CONFIG_KEY)
                : 1_048_576L;
    }

    @Override
    public @NonNull AuthUser getAuthUser() {
        return CloudDriver.getInstance().getServiceContainer().getAuthService().getAuthUser(this.authUserId).orElseThrow();
    }

    @Override
    public boolean isUploadLimitReached(final long bytesToUpload) {
        return (this.getCurrentUploadedBytes() + bytesToUpload) >= this.maxBytesToUpload;
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
    public @NotNull List<StoredFile> getStoredFiles() {
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
