package de.lino.cloud.api.file;

import de.lino.cloud.api.file.meta.FileChecksum;
import de.lino.cloud.api.file.meta.FileMetadata;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A file of any content type, persisted through the same {@link
 * de.lino.cloud.api.factory.DataFactory} as any other {@link Serialized}
 * entity - envelope-encrypted (AES-256-GCM) under {@link #fileId()} before
 * it reaches the database.
 *
 * <p>{@link #contentType()} is always inferred from {@code fileName}'s
 * extension via {@link Constraints#CONTENT_TYPES}, falling back to {@link
 * #DEFAULT_CONTENT_TYPE} if unrecognized or absent.
 *
 * <p>{@link #checksum()} is a plaintext-content checksum, independent of
 * the AES-256-GCM authentication tag - verify a downloaded file against it
 * via {@link #verifyChecksum()}. Content is stored base64-encoded (as
 * {@link #contentBase64}, a {@code String} rather than a raw {@code
 * byte[]}, since Gson would otherwise serialize a {@code byte[]} as an
 * exploded JSON array of numbers); {@link #content()} decodes it lazily,
 * caching the result in {@link #decodedContent}. The constructor also
 * attempts DEFLATE compression before encoding, keeping the compressed form
 * only if it is smaller - see {@link #isCompressed()}.
 *
 * <p><b>Content can instead live in an external object store (S3) - see {@link #objectStorageKey}.</b>
 * A file is either inline ({@link #contentBase64} set, {@link #objectStorageKey} {@code null} -
 * every file ever uploaded before this field existed, and every file on a deployment that hasn't
 * opted into S3-backed storage at all) or S3-backed ({@link #objectStorageKey} set, {@link
 * #contentBase64} {@code null}) - never both. {@code DefaultFileFactory} (the only production
 * caller that constructs either shape) resolves an S3-backed file's content by fetching/decrypting
 * it from the configured {@code ObjectStorageService} and calling {@link
 * #withResolvedContent(byte[])} to hydrate a copy - every accessor below throws {@link
 * IllegalStateException} on an S3-backed instance until that hydration has happened.
 */
// content is excluded from toString(): dumping it - even base64-encoded -
// would still put a file's entire content into any log line or console
// output that prints a StoredFile. callSuper is deliberately left false
// (the default): Serialized itself never overrides equals()/hashCode(), so
// callSuper=true would compare via Object's identity equals() and make
// every pair of distinct instances unequal regardless of field values,
// defeating value-based equality entirely.
@ToString(exclude = {"contentBase64", "decodedContent"})
@EqualsAndHashCode(exclude = "decodedContent", callSuper = false)
public final class StoredFile extends Serialized {

    /** Fallback MIME type used when none can be inferred from the file name. */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** The hash algorithm used to compute {@link #checksum()} for a freshly uploaded file. */
    private static final HashAlgorithm DEFAULT_CHECKSUM_ALGORITHM = HashAlgorithm.SHA_256;

    /** This file's unique id, its {@link #primaryKey()}. */
    private final String fileId;

    /** This file's original file name, as uploaded; also the source {@link #normalizeContentType(String)} infers {@link #contentType} from. */
    private final String fileName;

    /** This file's MIME content type, inferred from {@link #fileName}'s extension via {@link Constraints#CONTENT_TYPES}. */
    private final String contentType;

    /**
     * Base64-encoded content; DEFLATE-compressed if {@link
     * #contentCompressed}, otherwise the original bytes.
     */
    private final String contentBase64;

    /** Whether {@link #contentBase64} decodes to DEFLATE-compressed bytes rather than the original. */
    private final boolean contentCompressed;

    /** The plaintext checksum this file's content must match on every future download - see {@link #verifyChecksum()}. */
    private final FileChecksum checksum;

    /**
     * When this file was first uploaded, as epoch milliseconds - exposed as an {@link Instant}
     * via {@link #createdAt()}. Stored as a plain {@code long} rather than {@link Instant}
     * directly: {@link Serialized#toByteArray()} serializes this class's fields via reflection
     * (Gson), and {@link Instant}'s own private fields are not reflectively accessible under the
     * JDK's default module boundaries ({@code java.base} does not open {@code java.time}) - a
     * plain {@code long} sidesteps that entirely.
     */
    private final long createdAtEpochMilli;

    /**
     * When this file's content was last changed, as epoch milliseconds - exposed as an {@link
     * Instant} via {@link #updatedAt()}. Stored as a plain {@code long} for the same
     * reflection-serialization reason documented on {@link #createdAtEpochMilli}.
     */
    private final long updatedAtEpochMilli;

    /**
     * When this file was soft-deleted, or {@code null} if it is not currently in the trash - see
     * {@link #markedDeleted()}/{@link #restored()}. Boxed (not a primitive {@code long}, unlike
     * {@link #createdAtEpochMilli}/{@link #updatedAtEpochMilli}) specifically so "not deleted" has
     * its own representable state, the same "nullable field = feature not opted into" convention
     * {@link Folder#getParentFolderId()} already uses.
     *
     * <p><b>Not the field {@code CloudUserService#deleteFile}/{@code #restoreFile} actually flip on
     * a routine trash/restore.</b> Doing so here would mean a full {@code FileFactory#findById}
     * (decrypt+decompress) followed by a full {@code DataFactory#update} (recompress+re-encrypt) on
     * every single delete/restore - exactly the O(file size) full-content-rewrite cost {@code
     * StoredFileOwnership} was built to avoid for ownership tracking in the first place. {@code
     * CloudUserService} instead mirrors the trash flag onto the already-cheap {@code
     * StoredFileOwnership} row it already reads for every listing - see that class's own Javadoc.
     * This field remains real and usable on the entity itself for any lower-level caller working
     * directly against {@code FileFactory}/{@code DataFactory} without going through {@code
     * CloudUserService}'s ownership layer, and is what the trash-purge job ultimately checks before
     * permanently removing a file's content.
     */
    private final Long deletedAtEpochMillis;

    /**
     * The key this file's content is stored under in an external object store (S3), or {@code
     * null} for a file whose content still lives inline in {@link #contentBase64} - see this
     * class's own Javadoc. Mutually exclusive with {@link #contentBase64}: exactly one of the two
     * is non-null on any given instance. Set via {@link #withObjectStorageKey(String)}, never by a
     * constructor a caller invokes directly with content in hand - a freshly uploaded file always
     * starts inline; only {@code DefaultFileFactory#upload} decides to move it to S3.
     */
    private final String objectStorageKey;

    /**
     * Whether this file's content was uploaded directly by the client to {@link
     * #objectStorageKey} via a presigned URL (see {@code PresignedTransferService}), rather than
     * through this server or moved there afterward by {@code DefaultFileFactory#upload}. A
     * direct-transfer file's content is never DEFLATE-compressed and never encrypted by this
     * application's own {@code EnvelopeEncryptionService} - confidentiality at rest comes from the
     * object store's own server-side encryption instead (see {@code S3PresignedTransferService}'s
     * Javadoc) - so {@link #resolveContent()} for such a file, once hydrated via {@link
     * #withResolvedContent(byte[])}, is exactly the bytes the object store returned, no
     * decompression step involved (harmless either way, since {@link #contentCompressed} is always
     * {@code false} on a direct-transfer instance). Always {@code false} when {@link
     * #objectStorageKey} is {@code null}.
     * -- GETTER --
     *
     * @return {@code true} if this file's content was uploaded directly by the client to {@link
     *     #objectStorageKey} via a presigned URL - never DEFLATE-compressed, never encrypted by
     *     this application's own {@code EnvelopeEncryptionService}. Always {@code false} if {@link
     *     #isS3Backed()} is {@code false}.

     */
    @Getter
    private final boolean directTransfer;

    /**
     * This file's size, as declared by the uploading client and verified against the object
     * store's own real content length at upload-completion time - set only on a {@link
     * #directTransfer} instance, {@code null} otherwise. {@link #sizeBytes()} returns this
     * directly when present, since a direct-transfer file's content is never fetched by this
     * server just to learn its size the way {@link #resolveContent()} would otherwise require.
     */
    private final Long declaredSizeBytes;

    /**
     * Lazily-decoded (and decompressed) cache of {@link #contentBase64},
     * populated on first access. Transient so Gson never serializes it;
     * plain reads/writes are safe since resolving is a pure, deterministic
     * function of the base64 content. For an {@link #isS3Backed()} instance, this is instead
     * primed directly by {@link #withResolvedContent(byte[])} - there is no {@link #contentBase64}
     * to decode it from.
     */
    private transient volatile byte[] decodedContent;

    /**
     * Full constructor, for re-hydrating a file with a known checksum and
     * timestamps. Prefer {@link #StoredFile(String, String, byte[])} for a
     * fresh upload.
     *
     * @param fileId this file's unique id, its {@link #primaryKey()}
     * @param fileName the original file name; also the source of {@link #contentType()} - see {@link Constraints#CONTENT_TYPES}
     * @param content the file's raw bytes, of any type; defensively copied
     * @param checksum the plaintext checksum {@code content} must match on every future download
     * @param createdAt when this file was first uploaded
     * @param updatedAt when this file's content was last changed
     * @throws NullPointerException if any argument is {@code null}
     */
    public StoredFile(final String fileId, final String fileName,
                       final byte[] content, final FileChecksum checksum,
                       final Instant createdAt, final Instant updatedAt) {
        this.fileId = Asserts.requireNonNull(fileId, "@StoredFile: fileId cannot be null");
        this.fileName = Asserts.requireNonNull(fileName, "@StoredFile: fileName cannot be null");
        this.contentType = normalizeContentType(this.fileName);

        final byte[] contentCopy = Asserts.requireNonNull(content, "@StoredFile: content cannot be null").clone();
        final byte[] compressed = deflate(contentCopy);
        this.contentCompressed = compressed.length < contentCopy.length;
        this.contentBase64 = Base64.getEncoder().encodeToString(this.contentCompressed ? compressed : contentCopy);
        this.decodedContent = contentCopy; // already have the original bytes in hand - prime the cache instead of discarding them

        this.checksum = Asserts.requireNonNull(checksum, "@StoredFile: checksum cannot be null");
        this.createdAtEpochMilli = Asserts.requireNonNull(createdAt, "@StoredFile: createdAt cannot be null").toEpochMilli();
        this.updatedAtEpochMilli = Asserts.requireNonNull(updatedAt, "@StoredFile: updatedAt cannot be null").toEpochMilli();
        this.deletedAtEpochMillis = null;
        this.objectStorageKey = null;
        this.directTransfer = false;
        this.declaredSizeBytes = null;
    }

    /**
     * Copy constructor backing {@link #markedDeleted()}/{@link #restored()} - carries every field
     * over from {@code source} unchanged except {@link #deletedAtEpochMillis}, reusing {@code
     * source}'s already-resolved {@link #contentBase64}/{@link #decodedContent}/{@link
     * #objectStorageKey} directly rather than decompressing and recompressing content just to flip
     * one flag.
     */
    private StoredFile(final StoredFile source, final Long deletedAtEpochMillis) {
        this.fileId = source.fileId;
        this.fileName = source.fileName;
        this.contentType = source.contentType;
        this.contentBase64 = source.contentBase64;
        this.contentCompressed = source.contentCompressed;
        this.checksum = source.checksum;
        this.createdAtEpochMilli = source.createdAtEpochMilli;
        this.updatedAtEpochMilli = source.updatedAtEpochMilli;
        this.deletedAtEpochMillis = deletedAtEpochMillis;
        this.objectStorageKey = source.objectStorageKey;
        this.directTransfer = source.directTransfer;
        this.declaredSizeBytes = source.declaredSizeBytes;
        this.decodedContent = source.decodedContent;
    }

    /**
     * Copy constructor backing {@link #withObjectStorageKey(String)} - carries every field over
     * from {@code source} except {@link #contentBase64} (nulled) and {@link #decodedContent}
     * (dropped, unhydrated): the resulting instance is a metadata-only reference to content that
     * now lives at {@code objectStorageKey} in an external object store, not this entity's own
     * {@link #contentBase64} field. {@link #directTransfer} always stays {@code false} here - this
     * constructor backs {@code DefaultFileFactory#upload}'s app-encrypted S3 path only; a
     * direct-transfer instance is only ever built via {@link #StoredFile(String, String, long,
     * FileChecksum, Instant, Instant, String)}.
     */
    private StoredFile(final StoredFile source, final String objectStorageKey) {
        this.fileId = source.fileId;
        this.fileName = source.fileName;
        this.contentType = source.contentType;
        this.contentBase64 = null;
        this.contentCompressed = source.contentCompressed;
        this.checksum = source.checksum;
        this.createdAtEpochMilli = source.createdAtEpochMilli;
        this.updatedAtEpochMilli = source.updatedAtEpochMilli;
        this.deletedAtEpochMillis = source.deletedAtEpochMillis;
        this.objectStorageKey = Asserts.requireNonNull(
                objectStorageKey, "@StoredFile: objectStorageKey cannot be null"
        );
        this.directTransfer = false;
        this.declaredSizeBytes = null;
        this.decodedContent = null;
    }

    /**
     * Copy constructor backing {@link #withResolvedContent(byte[])} - carries every field over
     * from {@code source} unchanged (including {@link #objectStorageKey}, which stays set: this
     * hydrates an S3-backed reference with fetched content, it does not turn it back into an
     * inline file) except {@link #decodedContent}, primed with the given, already-resolved bytes.
     */
    private StoredFile(final StoredFile source, final byte[] decodedContent) {
        this.fileId = source.fileId;
        this.fileName = source.fileName;
        this.contentType = source.contentType;
        this.contentBase64 = source.contentBase64;
        this.contentCompressed = source.contentCompressed;
        this.checksum = source.checksum;
        this.createdAtEpochMilli = source.createdAtEpochMilli;
        this.updatedAtEpochMilli = source.updatedAtEpochMilli;
        this.deletedAtEpochMillis = source.deletedAtEpochMillis;
        this.objectStorageKey = source.objectStorageKey;
        this.directTransfer = source.directTransfer;
        this.declaredSizeBytes = source.declaredSizeBytes;
        this.decodedContent = decodedContent;
    }

    /**
     * Copy constructor backing {@link #renamedTo(String)} - carries every field over from {@code
     * source} unchanged except {@link #fileName} and {@link #contentType} (re-inferred from the
     * new name's extension, the same way the primary constructor infers it for a fresh upload).
     * Content, checksum, and every timestamp are left untouched - renaming changes neither this
     * file's bytes nor when they were last uploaded. The trailing {@code boolean} parameter exists
     * purely to give this constructor a distinct erasure from {@link #StoredFile(StoredFile,
     * String)} (backing {@link #withObjectStorageKey(String)}), which would otherwise collide on
     * {@code (StoredFile, String)}.
     */
    private StoredFile(final StoredFile source, final String newFileName, final boolean renaming) {
        this.fileId = source.fileId;
        this.fileName = Asserts.requireNonNull(newFileName, "@StoredFile: fileName cannot be null");
        this.contentType = normalizeContentType(this.fileName);
        this.contentBase64 = source.contentBase64;
        this.contentCompressed = source.contentCompressed;
        this.checksum = source.checksum;
        this.createdAtEpochMilli = source.createdAtEpochMilli;
        this.updatedAtEpochMilli = source.updatedAtEpochMilli;
        this.deletedAtEpochMillis = source.deletedAtEpochMillis;
        this.objectStorageKey = source.objectStorageKey;
        this.directTransfer = source.directTransfer;
        this.declaredSizeBytes = source.declaredSizeBytes;
        this.decodedContent = source.decodedContent;
    }

    /**
     * Constructor for a file whose content was uploaded directly to an external object store by
     * the client itself, via a presigned URL - never touched by this server at all, so there is no
     * {@code content} argument here the way every other constructor has. Used only by {@code
     * CloudUserService#completePresignedUpload}, once the object store has confirmed the upload
     * actually landed.
     *
     * @param fileId this file's unique id, its {@link #primaryKey()}
     * @param fileName the original file name; also the source of {@link #contentType()}
     * @param sizeBytes the file's real size, as confirmed against the object store - see {@link #declaredSizeBytes}
     * @param checksum the checksum the uploading client itself computed and reported - trusted, not
     *     independently verified by this server (it never sees the content to verify it against)
     * @param createdAt when this file was first uploaded
     * @param updatedAt when this file's content was last changed
     * @param objectStorageKey the key this file's content is stored under in the external object store
     * @throws NullPointerException if any argument is {@code null}
     */
    public StoredFile(final String fileId, final String fileName, final long sizeBytes, final FileChecksum checksum,
                       final Instant createdAt, final Instant updatedAt, final String objectStorageKey) {
        this.fileId = Asserts.requireNonNull(fileId, "@StoredFile: fileId cannot be null");
        this.fileName = Asserts.requireNonNull(fileName, "@StoredFile: fileName cannot be null");
        this.contentType = normalizeContentType(this.fileName);
        this.contentBase64 = null;
        this.contentCompressed = false;
        this.checksum = Asserts.requireNonNull(checksum, "@StoredFile: checksum cannot be null");
        this.createdAtEpochMilli = Asserts.requireNonNull(createdAt, "@StoredFile: createdAt cannot be null").toEpochMilli();
        this.updatedAtEpochMilli = Asserts.requireNonNull(updatedAt, "@StoredFile: updatedAt cannot be null").toEpochMilli();
        this.deletedAtEpochMillis = null;
        this.objectStorageKey = Asserts.requireNonNull(objectStorageKey, "@StoredFile: objectStorageKey cannot be null");
        this.directTransfer = true;
        this.declaredSizeBytes = sizeBytes;
    }

    /**
     * Convenience constructor for a newly uploaded file: computes {@link
     * #checksum()} over {@code content} (with {@link HashAlgorithm#SHA_256})
     * and stamps {@link #createdAt()}/{@link #updatedAt()} with the current
     * time.
     *
     * @param fileId this file's unique id, its {@link #primaryKey()}
     * @param fileName the original file name; also the source of {@link #contentType()} - see {@link Constraints#CONTENT_TYPES}
     * @param content the file's raw bytes, of any type; defensively copied
     * @throws NullPointerException if any argument is {@code null}
     */
    public StoredFile(final String fileId, final String fileName, final byte[] content) {
        this(
                fileId, fileName,
                Asserts.requireNonNull(content, "@StoredFile: content cannot be null"),
                FileChecksum.of(DEFAULT_CHECKSUM_ALGORITHM, content),
                Instant.now(), Instant.now()
        );
    }

    /**
     * Infers a content type from {@code fileName}'s extension, falling back to {@link #DEFAULT_CONTENT_TYPE}.
     *
     * @param fileName the file name to infer a content type from
     * @return the inferred MIME content type, never {@code null}
     */
    private static String normalizeContentType(final String fileName) {
        final String extension = extractExtension(fileName);
        final String inferred = extension == null ? null : Constraints.CONTENT_TYPES.get(extension);
        return inferred != null ? inferred : DEFAULT_CONTENT_TYPE;
    }

    /**
     * The lowercase file extension of {@code fileName} (without the dot), or {@code null} if it has none.
     *
     * @param fileName the file name to extract an extension from, possibly {@code null}
     * @return the lowercase extension, or {@code null} if {@code fileName} is {@code null} or has no extension
     */
    private static String extractExtension(final String fileName) {
        if (fileName == null) {
            return null;
        }

        final int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * @return this entity's primary key, a single-element list containing {@link #fileId}
     */
    @Override
    public List<String> keysOf() {
        return List.of(fileId);
    }

    /** This file's unique id, its {@link #primaryKey()}. */
    public String fileId() {
        return fileId;
    }

    /** This file's original file name, as uploaded. */
    public String fileName() {
        return fileName;
    }

    /** This file's MIME content type, inferred from {@link #fileName()}'s extension. */
    public String contentType() {
        return contentType;
    }

    /** This file's raw bytes, defensively cloned on every call. */
    public byte[] content() {
        return resolveContent().clone();
    }

    /**
     * The size, in bytes, of this file's original, uncompressed content - {@link
     * #declaredSizeBytes} directly for a {@link #isDirectTransfer()} instance (avoids fetching
     * content this server never needs to touch just to learn its length), otherwise {@link
     * #resolveContent()}'s length as before.
     */
    public long sizeBytes() {
        return declaredSizeBytes != null ? declaredSizeBytes : resolveContent().length;
    }

    /** The plaintext checksum this file's content must match on every future download. */
    public FileChecksum checksum() {
        return checksum;
    }

    /** Whether this file is stored DEFLATE-compressed; purely informational, every accessor already accounts for it. */
    public boolean isCompressed() {
        return contentCompressed;
    }

    /** @return {@code true} if this file's content lives in an external object store (S3) rather than inline in {@link #contentBase64} */
    public boolean isS3Backed() {
        return objectStorageKey != null;
    }

    /** The key this file's content is stored under in an external object store, or {@code null} if {@link #isS3Backed()} is {@code false}. */
    public String objectStorageKey() {
        return objectStorageKey;
    }

    /**
     * The raw bytes {@link #contentBase64} decodes to - DEFLATE-compressed if {@link
     * #isCompressed()}, otherwise the original plaintext - without the additional decompression
     * step {@link #resolveContent()} performs. Used by {@code DefaultFileFactory#upload} to obtain
     * the exact bytes to encrypt and hand to an external object store (S3), rather than letting
     * them reach this entity's own serialized JSON as {@link #contentBase64}.
     *
     * @return the raw, base64-decoded (but not decompressed) content bytes
     * @throws IllegalStateException if this file is already {@link #isS3Backed()} and carries no {@link #contentBase64} to decode
     */
    public byte[] rawStorableBytes() {
        if (contentBase64 == null) {
            throw new IllegalStateException(
                    "@StoredFile.rawStorableBytes: file '" + fileId + "' has no inline content to read - "
                            + "it is already S3-backed (objectStorageKey '" + objectStorageKey + "')"
            );
        }
        return Base64.getDecoder().decode(contentBase64);
    }

    /**
     * Decompresses {@code rawBytes} if this file is stored {@link #isCompressed() compressed},
     * otherwise returns a defensive copy unchanged - the decompression half of what {@link
     * #resolveContent()} does for {@link #contentBase64}-sourced bytes, exposed so {@code
     * DefaultFileFactory} can apply the same step to bytes it decrypted from an external object
     * store instead.
     *
     * @param rawBytes the raw (compressed-if-{@link #isCompressed()}) bytes to resolve
     * @return the original, uncompressed plaintext bytes
     * @throws NullPointerException if {@code rawBytes} is {@code null}
     * @throws IllegalStateException if {@link #isCompressed()} is {@code true} and {@code rawBytes} is not valid DEFLATE data
     */
    public byte[] decompressIfNeeded(final byte[] rawBytes) {
        Asserts.requireNonNull(rawBytes, "@StoredFile.decompressIfNeeded: rawBytes cannot be null");
        return contentCompressed ? inflate(rawBytes) : rawBytes.clone();
    }

    /**
     * @return a copy of this file with content moved out of {@link #contentBase64} - {@link
     *     #objectStorageKey} set to {@code objectStorageKey}, {@link #contentBase64} nulled, and no
     *     cached {@link #decodedContent} carried over. A metadata-only reference: {@link #content()}
     *     and every method built on {@link #resolveContent()} throw {@link IllegalStateException}
     *     on the result until {@link #withResolvedContent(byte[])} hydrates a fetched copy. Used by
     *     {@code DefaultFileFactory#upload} once this file's content has already been written to
     *     external object storage separately.
     * @throws NullPointerException if {@code objectStorageKey} is {@code null}
     */
    public StoredFile withObjectStorageKey(final String objectStorageKey) {
        return new StoredFile(this, objectStorageKey);
    }

    /**
     * @return a copy of this {@link #isS3Backed()} file with {@code content} primed as its
     *     resolved, uncompressed plaintext - so {@link #content()}/{@link #sizeBytes()}/{@link
     *     #verifyChecksum()}/{@link #downloadToDevice()} work exactly as they already do on an
     *     inline file. Called by {@code DefaultFileFactory#download}/{@code #findById}/{@code
     *     #getEntities} once content has been fetched from the configured {@code
     *     ObjectStorageService} and decrypted/decompressed.
     * @param content this file's resolved, uncompressed plaintext bytes; defensively copied
     * @throws NullPointerException if {@code content} is {@code null}
     */
    public StoredFile withResolvedContent(final byte[] content) {
        Asserts.requireNonNull(content, "@StoredFile.withResolvedContent: content cannot be null");
        return new StoredFile(this, content.clone());
    }

    /** When this file was first uploaded. */
    public Instant createdAt() {
        return Instant.ofEpochMilli(createdAtEpochMilli);
    }

    /** When this file's content was last changed. */
    public Instant updatedAt() {
        return Instant.ofEpochMilli(updatedAtEpochMilli);
    }

    /** @return {@code true} if this file is currently soft-deleted (in the trash) */
    public boolean isDeleted() {
        return deletedAtEpochMillis != null;
    }

    /** When this file was soft-deleted, or {@code null} if it is not currently in the trash. */
    public Instant deletedAt() {
        return deletedAtEpochMillis == null ? null : Instant.ofEpochMilli(deletedAtEpochMillis);
    }

    /** @return a copy of this file, soft-deleted as of now - see {@link #deletedAtEpochMillis}'s own Javadoc for who actually calls this */
    public StoredFile markedDeleted() {
        return new StoredFile(this, System.currentTimeMillis());
    }

    /** @return a copy of this file, restored out of the trash */
    public StoredFile restored() {
        return new StoredFile(this, (Long) null);
    }

    /**
     * @param newFileName this file's new display name - {@link #contentType()} is re-inferred
     *     from its extension, the same way it is for a freshly uploaded file
     * @return a copy of this file with {@link #fileName()} (and {@link #contentType()}) changed;
     *     content, checksum, and every timestamp are unchanged
     * @throws NullPointerException if {@code newFileName} is {@code null}
     */
    public StoredFile renamedTo(final String newFileName) {
        return new StoredFile(this, newFileName, true);
    }

    /** This file's descriptive attributes without its content - see {@link FileMetadata}. */
    public FileMetadata metadata() {
        return new FileMetadata(fileId, fileName, contentType, sizeBytes(), checksum, createdAt(), updatedAt());
    }

    /**
     * Whether this file's content still matches its recorded {@link
     * #checksum()}, without the defensive copy {@link #content()} would
     * force. Prefer this over {@code checksum().matches(content())} when
     * only a yes/no answer is needed.
     *
     * @return {@code true} if this file's content matches {@link #checksum()}
     */
    public boolean verifyChecksum() {
        return checksum.matches(resolveContent());
    }

    /**
     * Re-creates this file on the local filesystem inside {@code
     * destination} (a directory, created first if missing), under its own
     * {@link #fileName()} (with any {@code /} replaced by {@code _} first -
     * {@code fileName} is arbitrary user input and a real {@code /} would
     * otherwise be misread as introducing a subdirectory that was never
     * created, failing the write below with {@link java.nio.file.NoSuchFileException}),
     * with last-modified time set to {@link #updatedAt()} and creation time
     * set to {@link #createdAt()} on a best-effort basis (not every
     * filesystem supports it).
     *
     * @param destination the local directory to (re)create this file in
     * @return the path of the recreated file
     * @throws IOException if creating {@code destination} or writing the file fails
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public Path downloadToDevice(final Path destination) throws IOException {
        Asserts.requireNonNull(destination, "@StoredFile.downloadToDevice: destination cannot be null");

        Files.createDirectories(destination);

        final String safeFileName = fileName.replace('/', '_');
        Path target = destination.resolve(safeFileName);

        if (Files.exists(target)) target = destination.resolve(UUID.randomUUID() + "_" + safeFileName);

        Files.write(target, resolveContent());
        Files.setLastModifiedTime(target, FileTime.from(updatedAt()));

        try {
            final BasicFileAttributeView attributeView = Files.getFileAttributeView(target, BasicFileAttributeView.class);
            attributeView.setTimes(null, null, FileTime.from(createdAt()));
        } catch (final IOException | UnsupportedOperationException creationTimeNotSupported) {
            // best-effort only - not every filesystem exposes a settable creation time
        }

        return target;
    }

    /**
     * {@link #downloadToDevice(Path)}, defaulting {@code destination} to {@link Constraints#USER_DOWNLOADS_PATH}.
     *
     * @return the path of the recreated file
     * @throws IOException if creating the destination directory or writing the file fails
     */
    public Path downloadToDevice() throws IOException {
        return this.downloadToDevice(Constraints.USER_DOWNLOADS_PATH);
    }

    /**
     * Decodes (and decompresses, if needed) {@link #contentBase64} into {@link #decodedContent}, caching the result.
     *
     * @return this file's original, uncompressed plaintext bytes - the live cached array, not a defensive copy
     * @throws IllegalStateException if this file is {@link #isS3Backed()} and no content has been
     *     supplied yet via {@link #withResolvedContent(byte[])} - {@code DefaultFileFactory} must
     *     fetch it from the configured {@code ObjectStorageService} first
     */
    private byte[] resolveContent() {
        byte[] resolved = decodedContent;
        if (resolved == null) {
            if (objectStorageKey != null) {
                throw new IllegalStateException(
                        "@StoredFile.resolveContent: file '" + fileId + "'s content lives in external object "
                                + "storage (key '" + objectStorageKey + "') and has not been resolved yet - "
                                + "DefaultFileFactory must fetch it and call withResolvedContent() first"
                );
            }
            final byte[] stored = Base64.getDecoder().decode(contentBase64);
            resolved = contentCompressed ? inflate(stored) : stored;
            decodedContent = resolved;
        }
        return resolved;
    }

    /**
     * DEFLATE-compresses {@code data}. Never throws for arbitrary input bytes.
     *
     * @param data the bytes to compress
     * @return the DEFLATE-compressed bytes
     */
    private static byte[] deflate(final byte[] data) {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            final byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                final int written = deflater.deflate(chunk);
                buffer.write(chunk, 0, written);
            }
            return buffer.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Reverses {@link #deflate(byte[])}.
     *
     * @param compressed the DEFLATE-compressed bytes to decompress
     * @return the original, uncompressed bytes
     * @throws IllegalStateException if {@code compressed} is not valid DEFLATE data
     */
    private static byte[] inflate(final byte[] compressed) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, compressed.length * 2));
            final byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                final int written = inflater.inflate(chunk);
                if (written == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalStateException("@StoredFile.inflate: compressed content ended unexpectedly");
                }
                buffer.write(chunk, 0, written);
            }
            return buffer.toByteArray();
        } catch (final DataFormatException e) {
            throw new IllegalStateException("@StoredFile.inflate: stored content is not valid DEFLATE data", e);
        } finally {
            inflater.end();
        }
    }

}
