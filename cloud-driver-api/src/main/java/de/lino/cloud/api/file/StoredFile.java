package de.lino.cloud.api.file;

import de.lino.cloud.api.file.meta.FileChecksum;
import de.lino.cloud.api.file.meta.FileMetadata;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
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
     * Lazily-decoded (and decompressed) cache of {@link #contentBase64},
     * populated on first access. Transient so Gson never serializes it;
     * plain reads/writes are safe since resolving is a pure, deterministic
     * function of the base64 content.
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

    /** The size, in bytes, of this file's original, uncompressed content. */
    public long sizeBytes() {
        return resolveContent().length;
    }

    /** The plaintext checksum this file's content must match on every future download. */
    public FileChecksum checksum() {
        return checksum;
    }

    /** Whether this file is stored DEFLATE-compressed; purely informational, every accessor already accounts for it. */
    public boolean isCompressed() {
        return contentCompressed;
    }

    /** When this file was first uploaded. */
    public Instant createdAt() {
        return Instant.ofEpochMilli(createdAtEpochMilli);
    }

    /** When this file's content was last changed. */
    public Instant updatedAt() {
        return Instant.ofEpochMilli(updatedAtEpochMilli);
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
     * {@link #fileName()}, with last-modified time set to {@link
     * #updatedAt()} and creation time set to {@link #createdAt()} on a
     * best-effort basis (not every filesystem supports it).
     *
     * @param destination the local directory to (re)create this file in
     * @return the path of the recreated file
     * @throws IOException if creating {@code destination} or writing the file fails
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public Path downloadToDevice(final Path destination) throws IOException {
        Asserts.requireNonNull(destination, "@StoredFile.downloadToDevice: destination cannot be null");

        Files.createDirectories(destination);

        Path target = destination.resolve(fileName);

        if (Files.exists(target)) target = destination.resolve(UUID.randomUUID() + "_" + fileName);

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
     */
    private byte[] resolveContent() {
        byte[] resolved = decodedContent;
        if (resolved == null) {
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
