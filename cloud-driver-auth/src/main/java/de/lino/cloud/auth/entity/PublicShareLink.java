package de.lino.cloud.auth.entity;

import de.lino.cloud.api.file.StoredFile;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * A public, unauthenticated share link on one {@link StoredFile} - section 6 of {@code
 * architecture/MICRO.md}. Resolved via the public {@code GET /public/files/{token}} route with no
 * bearer token/login at all, unlike every other file-access path in this codebase.
 *
 * <p><b>{@link #token} is this entity's own primary key, stored raw, mirroring {@link
 * RefreshToken}'s exact shape - see that class's own Javadoc for the full "why not a raw+hash
 * pair like {@code ApiKey}" reasoning.</b> Envelope-encrypted at rest like every other {@link
 * Serialized} entity is the actual security boundary here; a caller presenting a token is itself
 * the proof needed, and {@code CloudUserService#resolvePublicFileLink} never accepts anything less
 * specific than the exact token value.
 *
 * <p><b>Always read-only</b> - there is no permission-level field, deliberately: this codebase has
 * no unauthenticated write path anywhere, and a public link doesn't introduce the first one.
 */
@Getter @ToString(exclude = "token")
@EqualsAndHashCode(callSuper = false)
public final class PublicShareLink extends Serialized {

    /** Length, in bytes, of the random token material generated for a fresh {@link PublicShareLink} - matches {@link RefreshToken#RAW_TOKEN_LENGTH_BYTES}. */
    public static final int RAW_TOKEN_LENGTH_BYTES = 48;

    /** The opaque token value itself - also this entity's primary key. Excluded from {@link #toString()}. */
    private final String token;

    /** The {@link StoredFile#fileId()} this link resolves to. */
    private final String storedFileId;

    /** The account that owns {@link #storedFileId} and created this link - the only account that can list/revoke it. */
    private final String ownerAuthUserId;

    /** When this link was created, as epoch millis. */
    private final long createdAtEpochMillis;

    /** When this link expires, as epoch millis, or {@code null} if it never does. */
    @Nullable
    private final Long expiresAtEpochMillis;

    /**
     * Generates a fresh, random public share link (48 bytes via {@link SecureRandom},
     * base64url-encoded - the same generation style {@link RefreshToken}'s own constructor uses),
     * stamping {@link #createdAtEpochMillis} with the current time.
     *
     * @param storedFileId the file this link resolves to
     * @param ownerAuthUserId the file's actual owner, creating this link
     * @param expiresAtEpochMillis when this link should expire, as epoch millis, or {@code null} to never expire
     */
    public PublicShareLink(@NotNull final String storedFileId, @NotNull final String ownerAuthUserId,
                            @Nullable final Long expiresAtEpochMillis) {
        this(generateToken(), storedFileId, ownerAuthUserId, System.currentTimeMillis(), expiresAtEpochMillis);
    }

    /**
     * Full constructor, for re-hydrating a link with a known token/timestamp (Gson deserialization).
     *
     * @param token the link's own token, its primary key
     * @param storedFileId the file this link resolves to
     * @param ownerAuthUserId the file's actual owner, who created this link
     * @param createdAtEpochMillis when this link was created, as epoch millis
     * @param expiresAtEpochMillis when this link expires, as epoch millis, or {@code null} if it never does
     */
    public PublicShareLink(@NotNull final String token, @NotNull final String storedFileId, @NotNull final String ownerAuthUserId,
                            final long createdAtEpochMillis, @Nullable final Long expiresAtEpochMillis) {
        this.token = Objects.requireNonNull(token, "@PublicShareLink.init: token cannot be null");
        this.storedFileId = Objects.requireNonNull(storedFileId, "@PublicShareLink.init: storedFileId cannot be null");
        this.ownerAuthUserId = Objects.requireNonNull(ownerAuthUserId, "@PublicShareLink.init: ownerAuthUserId cannot be null");
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    /** @return a fresh, random, base64url-encoded token of {@link #RAW_TOKEN_LENGTH_BYTES} bytes */
    private static String generateToken() {
        final byte[] raw = new byte[RAW_TOKEN_LENGTH_BYTES];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @return {@code true} if {@link #expiresAtEpochMillis} is set and in the past */
    public boolean isExpired() {
        return this.expiresAtEpochMillis != null && this.expiresAtEpochMillis < System.currentTimeMillis();
    }

    /** @return this entity's primary key, {@link #token} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.token);
    }

}
