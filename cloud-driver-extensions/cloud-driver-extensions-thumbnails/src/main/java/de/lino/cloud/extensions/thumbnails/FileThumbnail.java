package de.lino.cloud.extensions.thumbnails;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.thumbnail.ThumbnailSize;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A small linking row pairing a source {@link StoredFile} with the {@link StoredFile} holding
 * its generated thumbnail's actual bytes, for one {@link ThumbnailSize} variant - the same {@code
 * StoredFileOwnership}-style pattern this codebase already uses to avoid reintroducing O(n)
 * scan-based ownership checks, applied here to a lookup rather than
 * an ownership check.
 *
 * <p><b>Deliberately a separate linking row rather than a field on {@code StoredFile} itself</b>:
 * {@code StoredFile}
 * is this codebase's single most heavily-scrutinized entity (envelope encryption, S3-backing,
 * direct-transfer mode, compression, ~8 constructor overloads) - adding a field to it for a
 * single, optional addon would widen its blast
 * radius for no real benefit. A separate row achieves the same "not a separate storage path"
 * requirement (a thumbnail's actual bytes are still an ordinary {@link StoredFile}, going through
 * the exact same envelope-encryption/compression pipeline as any other file) while touching
 * {@code StoredFile}'s own schema not at all,
 * and is easier to remove cleanly if this addon is ever disabled/uninstalled.
 *
 * <p>Primary-keyed on {@link #compositeKey(String, String)} (source file id + size name) rather
 * than the source file id alone, so a second {@link ThumbnailSize} variant can be added later
 * without a primary-key migration - see {@link ThumbnailSize}'s own Javadoc for why only one
 * variant exists today.
 */
@Getter @ToString @EqualsAndHashCode(callSuper = false)
public final class FileThumbnail extends Serialized {

    /** The source {@link StoredFile#fileId()} this thumbnail was generated from. */
    private final String sourceFileId;

    /** The {@link ThumbnailSize} variant this row is for, stored as {@link ThumbnailSize#name()}. */
    private final String size;

    /** The {@link StoredFile#fileId()} holding this thumbnail's own (JPEG-encoded) bytes. */
    private final String thumbnailFileId;

    /** When this thumbnail was generated, as epoch milliseconds. */
    private final long generatedAtEpochMillis;

    /**
     * @param sourceFileId the source {@link StoredFile#fileId()} this thumbnail was generated from
     * @param size the {@link ThumbnailSize} variant's name this row is for
     * @param thumbnailFileId the {@link StoredFile#fileId()} holding this thumbnail's own bytes
     * @param generatedAtEpochMillis when this thumbnail was generated, as epoch milliseconds
     * @throws NullPointerException if {@code sourceFileId}/{@code size}/{@code thumbnailFileId} is {@code null}
     */
    public FileThumbnail(@NotNull final String sourceFileId, @NotNull final String size,
                          @NotNull final String thumbnailFileId, final long generatedAtEpochMillis) {
        this.sourceFileId = Objects.requireNonNull(sourceFileId, "@FileThumbnail.init: sourceFileId cannot be null");
        this.size = Objects.requireNonNull(size, "@FileThumbnail.init: size cannot be null");
        this.thumbnailFileId = Objects.requireNonNull(thumbnailFileId, "@FileThumbnail.init: thumbnailFileId cannot be null");
        this.generatedAtEpochMillis = generatedAtEpochMillis;
    }

    /**
     * @return this record's identifying values - the composite primary key first (see {@link
     *     #compositeKey(String, String)}), followed by {@link #sourceFileId} alone so a future
     *     caller can also look this row up by {@link #hasKey(String)} against just the source id
     */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(compositeKey(this.sourceFileId, this.size), this.sourceFileId);
    }

    /**
     * Builds the primary key a {@code FileThumbnail} for {@code sourceFileId}/{@code size} is
     * stored under. {@code sourceFileId} is always a random {@link java.util.UUID}, so plainly
     * concatenating with {@code ":"} can never collide with {@code size}'s own name.
     *
     * @param sourceFileId the source file's id
     * @param size the thumbnail size variant's name
     * @return the composite primary key, {@code sourceFileId + ":" + size}
     */
    @NotNull
    public static String compositeKey(@NotNull final String sourceFileId, @NotNull final String size) {
        Objects.requireNonNull(sourceFileId, "@FileThumbnail.compositeKey: sourceFileId cannot be null");
        Objects.requireNonNull(size, "@FileThumbnail.compositeKey: size cannot be null");
        return sourceFileId + ":" + size;
    }

}
