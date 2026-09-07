package de.lino.cloud.extensions.versioning;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.meta.FileChecksum;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * One retained prior version of a {@link StoredFile}'s content - the same small-linking-row
 * pattern {@code cloud-driver-extensions-thumbnails}' own {@code FileThumbnail} uses: a version's
 * actual bytes are stored as an ordinary {@link StoredFile} (going through the same
 * envelope-encryption/compression pipeline as any other upload), this row only links a source
 * file + a 1-based version number to the {@link StoredFile} holding that version's content.
 *
 * <p>Primary-keyed on {@link #compositeKey(String, int)} (source file id + version number) - a
 * version number is computed once, at capture time, as "however many versions of this file are
 * already retained, plus one" (see {@code DefaultFileVersioningService#captureVersion}) rather
 * than tracked as a separate counter anywhere - versions are pruned by this extension's own
 * retention policy, so the count per file stays small in practice, making the full-scan-and-count
 * this needs cheap, matching the same accepted trade-off {@code StoredFileOwnership}/{@code
 * SharedFileGrant} already make elsewhere in this codebase for a non-primary-key lookup.
 */
@Getter @ToString @EqualsAndHashCode(callSuper = false)
public final class FileVersion extends Serialized {

    /** The source {@link StoredFile#fileId()} this is a prior version of. */
    private final String sourceFileId;

    /** A 1-based, per-file sequence number - {@code 1} is the oldest currently-retained version. */
    private final int versionNumber;

    /** The {@link StoredFile#fileId()} holding this version's own content. */
    private final String versionedFileId;

    /** The source file's {@link StoredFile#fileName()} at the moment this version was captured - may differ from the current live name if the file was renamed since. Used to set a sensible {@code Content-Disposition} when a version is downloaded, without an extra lookup. */
    private final String fileName;

    /** The source file's {@link StoredFile#contentType()} at the moment this version was captured. */
    private final String contentType;

    /** This version's content size, in bytes - a copy of {@link StoredFile#sizeBytes()} at capture time, so listing never has to resolve {@link #versionedFileId}'s content just to report a size. */
    private final long sizeBytes;

    /** This version's content checksum - a copy of {@link StoredFile#checksum()} at capture time; the content being versioned is already fully known, so this is carried over rather than recomputed. */
    private final FileChecksum contentHash;

    /** When this version was captured (i.e. when the overwrite that superseded it happened), as epoch milliseconds. */
    private final long capturedAtEpochMillis;

    /**
     * @param sourceFileId the source {@link StoredFile#fileId()} this is a prior version of
     * @param versionNumber a 1-based, per-file sequence number
     * @param versionedFileId the {@link StoredFile#fileId()} holding this version's own content
     * @param fileName the source file's {@link StoredFile#fileName()} at capture time
     * @param contentType the source file's {@link StoredFile#contentType()} at capture time
     * @param sizeBytes this version's content size, in bytes
     * @param contentHash this version's content checksum
     * @param capturedAtEpochMillis when this version was captured, as epoch milliseconds
     * @throws NullPointerException if {@code sourceFileId}/{@code versionedFileId}/{@code fileName}/{@code contentType}/{@code contentHash} is {@code null}
     */
    public FileVersion(@NotNull final String sourceFileId, final int versionNumber, @NotNull final String versionedFileId,
                        @NotNull final String fileName, @NotNull final String contentType,
                        final long sizeBytes, @NotNull final FileChecksum contentHash, final long capturedAtEpochMillis) {
        this.sourceFileId = Objects.requireNonNull(sourceFileId, "@FileVersion.init: sourceFileId cannot be null");
        this.versionNumber = versionNumber;
        this.versionedFileId = Objects.requireNonNull(versionedFileId, "@FileVersion.init: versionedFileId cannot be null");
        this.fileName = Objects.requireNonNull(fileName, "@FileVersion.init: fileName cannot be null");
        this.contentType = Objects.requireNonNull(contentType, "@FileVersion.init: contentType cannot be null");
        this.sizeBytes = sizeBytes;
        this.contentHash = Objects.requireNonNull(contentHash, "@FileVersion.init: contentHash cannot be null");
        this.capturedAtEpochMillis = capturedAtEpochMillis;
    }

    /**
     * @return this record's identifying values - the composite primary key first (see {@link
     *     #compositeKey(String, int)}), followed by {@link #sourceFileId} alone so a future
     *     caller can also look this row up by {@link #hasKey(String)} against just the source id
     */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(compositeKey(this.sourceFileId, this.versionNumber), this.sourceFileId);
    }

    /**
     * Builds the primary key a {@code FileVersion} for {@code sourceFileId}/{@code versionNumber}
     * is stored under. {@code sourceFileId} is always a random {@link java.util.UUID}, so plainly
     * concatenating with {@code ":"} can never collide with the version number's own digits.
     *
     * @param sourceFileId the source file's id
     * @param versionNumber the version number
     * @return the composite primary key, {@code sourceFileId + ":" + versionNumber}
     */
    @NotNull
    public static String compositeKey(@NotNull final String sourceFileId, final int versionNumber) {
        Objects.requireNonNull(sourceFileId, "@FileVersion.compositeKey: sourceFileId cannot be null");
        return sourceFileId + ":" + versionNumber;
    }

}
