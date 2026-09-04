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

    /**
     * The epoch-millisecond time this {@code CloudUser} row was first created (see the
     * constructor). Set once and never updated afterwards, so it doubles as the account's
     * creation timestamp - not a per-login value.
     */
    private final long timeStamp;

    /**
     * This account's upload quota ceiling, in bytes, checked by {@link #isUploadLimitReached}.
     * Read once at construction via {@link #resolveMaxBytesToUpload()} and persisted from then
     * on (mutable only via {@link CloudUserService#updateCloudUserBytesLimit}, an operator-driven
     * change, not automatically re-read from configuration afterwards).
     */
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
     * This account's stored light/dark theme preference (e.g. {@code "LIGHT"}/{@code "DARK"}),
     * synced across every device signed into this account instead of being a local, per-device
     * setting - updated via {@link CloudUserService#updateThemePreference}. {@code null} means
     * never explicitly set (a fresh account, or one that predates this field); a client falls
     * back to its own default in that case, the same "nullable = not opted into" convention
     * {@code Folder#parentFolderId} uses elsewhere in this codebase. Deliberately a plain
     * {@code String}, not a real enum, at this layer - the actual {@code ThemeMode}-shaped enum
     * lives client-side; the server only ever stores and echoes back whatever string a client sent.
     */
    private String themeMode;

    /**
     * {@code configuration.json} key {@link #resolveMaxBytesToUpload} reads {@link
     * #maxBytesToUpload} from - a sibling of {@code "jwt-signing-key"}/{@code "rest-server-port"}.
     */
    private static final String MAX_BYTES_TO_UPLOAD_CONFIG_KEY = "cloud-user-max-bytes-to-upload";

    /**
     * Creates a new {@code CloudUser} row for {@code authUserId}, stamping {@link #timeStamp}
     * with the current time and resolving {@link #maxBytesToUpload} from {@code
     * configuration.json} via {@link #resolveMaxBytesToUpload()}.
     *
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
     * defaulting to {@code 1_048_576L} (1 MiB) if it isn't set - {@link JsonDocument#getLong}
     * throws {@link NullPointerException} on a missing key rather than returning a default, so
     * this checks via {@link JsonDocument#contains} first rather than reading it unconditionally
     * (which would otherwise NPE on every single account confirmation/upload against a {@code
     * configuration.json} that predates this quota feature - every existing deployment's, until
     * an operator explicitly sets the key). Unlike some other optional configuration keys in this
     * codebase (e.g. a missing {@code "smtp-host"} falls back to {@code LoggingEmailSender} rather
     * than breaking registration), an absent key here does <b>not</b> fail open to "unlimited" -
     * it deliberately falls back to a strict 1 MiB quota, so an operator wanting a different (or
     * effectively unlimited) limit must set {@link #MAX_BYTES_TO_UPLOAD_CONFIG_KEY} explicitly.
     *
     * @return the configured upload quota ceiling in bytes, or {@code 1_048_576L} if unset
     */
    private static long resolveMaxBytesToUpload() {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        return configuration.contains(MAX_BYTES_TO_UPLOAD_CONFIG_KEY)
                ? configuration.getLong(MAX_BYTES_TO_UPLOAD_CONFIG_KEY)
                : 1_048_576L;
    }

    /**
     * Resolves the full {@link AuthUser} this row belongs to, via the process-wide {@link
     * CloudDriver} singleton's {@code AuthService}.
     *
     * @return the matching {@link AuthUser}
     * @throws java.util.NoSuchElementException if no {@link AuthUser} exists under {@link #authUserId}
     *     (should not normally happen - a {@code CloudUser} is only ever created for an existing account)
     */
    @Override
    public @NonNull AuthUser getAuthUser() {
        return CloudDriver.getInstance().getServiceContainer().getAuthService().getAuthUser(this.authUserId).orElseThrow();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Compares {@link #currentUploadedBytes} plus {@code bytesToUpload} against {@link
     * #maxBytesToUpload} using {@code >=}, so a request landing exactly on the remaining quota is
     * rejected, not just one that exceeds it.
     */
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
