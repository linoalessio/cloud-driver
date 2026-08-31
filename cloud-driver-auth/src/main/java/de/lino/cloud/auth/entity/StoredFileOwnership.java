package de.lino.cloud.auth.entity;

import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.auth.CloudUserService;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A single (user, file) ownership record - the join between an {@link
 * de.lino.cloud.api.jwt.user.AuthUser} (by its plain id) and one {@link
 * StoredFile#fileId()} it owns, additionally tracking which {@link Folder} (if any)
 * the file currently sits in.
 *
 * <p>Replaces the old design where {@link CloudUser} embedded every owned file id in
 * one {@code Set<String>}: with up to 10,000 files per user, tracking or untracking a
 * single file meant decrypting, deserializing, mutating, re-serializing and
 * re-encrypting that <em>entire</em> set on every upload/delete - an O(n) rewrite for
 * an operation that should be O(1). Here, each ownership is its own {@link Serialized}
 * row, envelope-encrypted independently (see {@code SecureEntityChannel}) and
 * primary-keyed on {@link #compositeKey(String, String)}, so adding, removing, or
 * moving one file is a single-row insert/delete/update that never touches any other
 * file id this or any other user owns. {@link #folderId} lives on this same row for
 * exactly the same reason a file's folder placement doesn't live on {@link StoredFile}
 * itself or as a membership list on {@link Folder} - see {@link Folder}'s own Javadoc.
 *
 * <p>{@link CloudUserService} still uses a full-section scan (via {@code
 * DataFactory#getEntities}) to answer "which files does this user own" for {@link
 * CloudUserService#listFiles}, since neither {@code DataFactory} nor the underlying
 * database-driver expose a lookup by non-primary-key field - see that method's Javadoc
 * for the resulting trade-off. Point checks ({@link CloudUserService}'s ownership
 * check ahead of a delete) go through {@link #compositeKey(String, String)} directly
 * and stay O(1).
 */
@Getter @ToString @EqualsAndHashCode(callSuper = false)
public final class StoredFileOwnership extends Serialized implements Owned {

    /** The owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} - also this row's {@link #ownerId()}. */
    private final String authUserId;

    /** The plain {@link StoredFile#fileId()} tracked as owned by {@link #authUserId}. */
    private final String storedFileId;

    /** The {@link Folder#getFolderId()} this file currently sits in, or {@code null} for the root. */
    @Nullable
    private final String folderId;

    /**
     * {@code storedFileId}'s {@link StoredFile#fileName()}, captured at upload time so a listing
     * (see {@link CloudUserService#listFileSummaries}) never needs to decrypt the full {@link
     * StoredFile} just to render a list entry. {@code null} only for a row written before this
     * metadata was captured at all - see {@link #hasMetadata()}.
     */
    @Nullable
    private final String fileName;

    /** {@code storedFileId}'s {@link StoredFile#contentType()} - see {@link #fileName} for the {@code null} case. */
    @Nullable
    private final String contentType;

    /** {@code storedFileId}'s {@link StoredFile#sizeBytes()}. Only meaningful when {@link #hasMetadata()}. */
    private final long sizeBytes;

    /** {@code storedFileId}'s {@link StoredFile#createdAt()}, as epoch millis. Only meaningful when {@link #hasMetadata()}. */
    private final long createdAtEpochMilli;

    /** {@code storedFileId}'s {@link StoredFile#updatedAt()}, as epoch millis. Only meaningful when {@link #hasMetadata()}. */
    private final long updatedAtEpochMilli;

    /**
     * Same as {@link #StoredFileOwnership(String, String, String)}, placing the file at the root
     * ({@code folderId} {@code null}).
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the plain {@link StoredFile#fileId()} being tracked as owned
     */
    public StoredFileOwnership(@NotNull final String authUserId, @NotNull final String storedFileId) {
        this(authUserId, storedFileId, null);
    }

    /**
     * Same as {@link #StoredFileOwnership(String, String, String, String, String, long, long, long)},
     * leaving every metadata field unset - only appropriate when the caller has no {@link
     * StoredFile} in hand yet (e.g. a purely-structural row); prefer the full constructor whenever
     * one is available, since {@link #hasMetadata()} otherwise stays {@code false} until a later
     * caller backfills it via {@link #withMetadata(StoredFile)}.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the plain {@link StoredFile#fileId()} being tracked as owned
     * @param folderId the {@link Folder#getFolderId()} this file currently sits in, or {@code null} for the root
     */
    public StoredFileOwnership(@NotNull final String authUserId, @NotNull final String storedFileId,
                                @Nullable final String folderId) {
        this(authUserId, storedFileId, folderId, null, null, 0L, 0L, 0L);
    }

    /**
     * Full constructor, additionally capturing {@code fileName}/{@code contentType}/{@code
     * sizeBytes}/timestamps from the {@link StoredFile} being tracked - see {@link #fileName}'s
     * own Javadoc for why. Prefer {@link #StoredFileOwnership(String, String, String, StoredFile)}
     * when a {@link StoredFile} instance is directly in hand.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param storedFileId the plain {@link StoredFile#fileId()} being tracked as owned
     * @param folderId the {@link Folder#getFolderId()} this file currently sits in, or {@code null} for the root
     * @param fileName {@code storedFileId}'s {@link StoredFile#fileName()}, or {@code null} if unknown
     * @param contentType {@code storedFileId}'s {@link StoredFile#contentType()}, or {@code null} if unknown
     * @param sizeBytes {@code storedFileId}'s {@link StoredFile#sizeBytes()}
     * @param createdAtEpochMilli {@code storedFileId}'s {@link StoredFile#createdAt()}, as epoch millis
     * @param updatedAtEpochMilli {@code storedFileId}'s {@link StoredFile#updatedAt()}, as epoch millis
     */
    public StoredFileOwnership(@NotNull final String authUserId, @NotNull final String storedFileId,
                                @Nullable final String folderId, @Nullable final String fileName,
                                @Nullable final String contentType, final long sizeBytes,
                                final long createdAtEpochMilli, final long updatedAtEpochMilli) {
        this.authUserId = Objects.requireNonNull(authUserId, "@StoredFileOwnership.init: authUserId cannot be null");
        this.storedFileId = Objects.requireNonNull(storedFileId, "@StoredFileOwnership.init: storedFileId cannot be null");
        this.folderId = folderId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAtEpochMilli = createdAtEpochMilli;
        this.updatedAtEpochMilli = updatedAtEpochMilli;
    }

    /**
     * Same as the full constructor, reading every metadata field directly off {@code file} instead
     * of requiring the caller to unpack it first.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @param file the file being tracked as owned - {@link StoredFile#fileId()} becomes {@link #storedFileId}
     * @param folderId the {@link Folder#getFolderId()} this file currently sits in, or {@code null} for the root
     * @return a fresh {@code StoredFileOwnership} row for {@code file}
     */
    @NotNull
    public static StoredFileOwnership of(@NotNull final String authUserId, @NotNull final StoredFile file, @Nullable final String folderId) {
        return new StoredFileOwnership(authUserId, file.fileId(), folderId, file.fileName(), file.contentType(),
                file.sizeBytes(), file.createdAt().toEpochMilli(), file.updatedAt().toEpochMilli());
    }

    /**
     * @param newFolderId the {@link Folder#getFolderId()} to move this file into, or {@code null} for the root
     * @return a copy of this row with {@link #folderId} changed to {@code newFolderId}, every other field (metadata included) carried over unchanged
     */
    @NotNull
    public StoredFileOwnership movedTo(@Nullable final String newFolderId) {
        return new StoredFileOwnership(this.authUserId, this.storedFileId, newFolderId,
                this.fileName, this.contentType, this.sizeBytes, this.createdAtEpochMilli, this.updatedAtEpochMilli);
    }

    /**
     * @return {@code true} once this row carries {@link #fileName}/{@link #contentType}/etc. -
     * {@code false} only for a row written before this metadata existed at all, in which case a
     * caller (see {@link CloudUserService#listFileSummaries}) falls back to fetching the full
     * {@link StoredFile} once and backfilling it via {@link #withMetadata(StoredFile)}.
     */
    public boolean hasMetadata() {
        return this.fileName != null;
    }

    /**
     * @param file the full {@link StoredFile} this row tracks ownership of ({@link
     * StoredFile#fileId()} must equal {@link #storedFileId})
     * @return a copy of this row with every metadata field populated from {@code file}
     */
    @NotNull
    public StoredFileOwnership withMetadata(@NotNull final StoredFile file) {
        return new StoredFileOwnership(this.authUserId, this.storedFileId, this.folderId,
                file.fileName(), file.contentType(), file.sizeBytes(),
                file.createdAt().toEpochMilli(), file.updatedAt().toEpochMilli());
    }

    /**
     * @return this record's identifying values - the composite primary key first (see
     * {@link #primaryKey()}), followed by {@link #authUserId} and {@link
     * #storedFileId} individually so either can also be used with {@link
     * #hasKey(String)}
     */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(compositeKey(this.authUserId, this.storedFileId), this.authUserId, this.storedFileId);
    }

    /** @return {@link #authUserId} - this ownership record belongs to the user it tracks a file for */
    @NotNull
    @Override
    public String ownerId() {
        return this.authUserId;
    }

    /**
     * Builds the primary key a {@code StoredFileOwnership} for {@code authUserId}/{@code
     * storedFileId} is stored under. {@code storedFileId} is always a random {@link
     * java.util.UUID}, so plainly concatenating with {@code ":"} can never collide with
     * either component.
     *
     * @param authUserId the owning user's id
     * @param storedFileId the owned file's id
     * @return the composite primary key, {@code authUserId + ":" + storedFileId}
     */
    @NotNull
    public static String compositeKey(@NotNull final String authUserId, @NotNull final String storedFileId) {
        Objects.requireNonNull(authUserId, "@StoredFileOwnership.compositeKey: authUserId cannot be null");
        Objects.requireNonNull(storedFileId, "@StoredFileOwnership.compositeKey: storedFileId cannot be null");
        return authUserId + ":" + storedFileId;
    }

}
