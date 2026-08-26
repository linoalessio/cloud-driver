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
 * A file of any content type. Being itself a {@link Serialized} domain
 * meta, a {@link StoredFile} is uploaded and downloaded through the same
 * {@link de.lino.cloud.api.factory.DataFactory} any other meta is stored
 * through - {@code dataFactory.register(storedFile)} to upload, {@code
 * dataFactory.fetch(fileId, StoredFile.class)} to download - so it is
 * envelope-encrypted (AES-256-GCM, section 9, DATA AT REST) under {@link
 * #fileId()} before it ever reaches the database, the database only ever
 * holding ciphertext regardless of what the original file was, with no
 * file-specific persistence code required.
 *
 * <p>{@link #contentType()} is never supplied by a caller - there is no
 * constructor parameter for it - it is always inferred from {@code
 * fileName}'s extension via {@link Constraints#CONTENT_TYPES}, falling back
 * to {@link #DEFAULT_CONTENT_TYPE} only if the extension is missing or not
 * one of the recognized ones - so no file is ever rejected merely because
 * its type is unrecognized or absent, callers can upload any byte sequence
 * under any name.
 *
 * <p>{@link #checksum()} is a plaintext-content checksum, separate from and
 * in addition to the AES-256-GCM authentication tag {@link
 * de.lino.cloud.api.security.crypto.AuthenticationFailedException} already
 * guards on the stored ciphertext - see {@link FileChecksum} - so a caller
 * can verify a downloaded file byte-for-byte against what was uploaded via
 * {@link #verifyChecksum()}, not merely rely on the ciphertext having gone
 * un-tampered-with. The checksum is always computed over the original,
 * uncompressed bytes, so it is unaffected by {@link #isCompressed()}.
 *
 * <p><b>On-the-wire representation.</b> {@link Serialized#toByteArray()}
 * serializes this class's fields via reflection (Gson) before envelope
 * encryption ever runs, and plain Gson has no special handling for a raw
 * {@code byte[]} field - it serializes one element-by-element as a JSON
 * array of numbers, several times larger than the content it represents.
 * To avoid encrypting (and storing, and - for a remote database - having to
 * transmit over the network) that bloat, content is held internally as
 * {@link #contentBase64}, a single compact JSON string Gson serializes
 * cheaply; {@link #content()} transparently decodes it back to bytes on
 * first access and caches the result in the {@code transient} (so never
 * itself serialized) {@link #decodedContent} field.
 *
 * <p><b>Compression.</b> Before base64-encoding, the constructor attempts
 * to DEFLATE-compress the content (compress-then-encrypt is the correct
 * order - encrypted bytes are high-entropy and do not compress at all, so
 * compressing afterward would be pointless). Compression only actually
 * helps for compressible content (e.g. text, JSON, uncompressed media) -
 * already-compressed formats (PDF, JPEG, MP4, ZIP, ...) typically shrink
 * only marginally or not at all - so the compressed result is kept only if
 * it is genuinely smaller than the original; otherwise the original bytes
 * are stored as-is. Either way {@link #isCompressed()} reports which
 * happened, and {@link #content()} transparently decompresses again when
 * needed, so this is invisible to every other caller of this class.
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

    /**
     * The MIME type recorded when a file is uploaded without one, or with a
     * blank one - so "unknown type" never blocks an upload.
     */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private static final HashAlgorithm DEFAULT_CHECKSUM_ALGORITHM = HashAlgorithm.SHA_256;

    private final String fileId;
    private final String fileName;
    private final String contentType;

    /**
     * This file's stored content, base64-encoded - see the class Javadoc
     * for why this is a {@code String} rather than a {@code byte[]}. Holds
     * DEFLATE-compressed bytes if {@link #contentCompressed} is {@code
     * true}, the original bytes otherwise.
     */
    private final String contentBase64;

    /**
     * Whether {@link #contentBase64} decodes to DEFLATE-compressed bytes
     * (compression was attempted and it actually shrank the content) or the
     * original bytes as-is (compression was not attempted - i.e. an empty
     * input, since {@link Deflater} still emits framing overhead for zero
     * bytes of input - or it did not help). See {@link #isCompressed()}.
     */
    private final boolean contentCompressed;

    private final FileChecksum checksum;

    // Stored as epoch millis, not Instant: Serialized.toByteArray() serializes
    // this class's fields via reflection (Gson), and java.time.Instant's
    // private fields are not reflectively accessible under the JDK's default
    // module boundaries (java.base does not open java.time) - a plain long
    // sidesteps that entirely. createdAt()/updatedAt() still expose Instant.
    private final long createdAtEpochMilli;
    private final long updatedAtEpochMilli;

    /**
     * Lazily-decoded (and, if {@link #contentCompressed}, decompressed)
     * cache of {@link #contentBase64}, populated on first access by {@link
     * #resolveContent()}. {@code transient} so Gson never serializes it -
     * Gson also never calls this class's constructors when deserializing
     * (it builds instances via reflection instead), so a
     * freshly-deserialized instance always starts with this {@code null}
     * and decodes/decompresses lazily; a freshly-constructed one (via
     * either public constructor) has it primed immediately, since the raw
     * bytes are already in hand there. Plain (non-atomic) reads/writes are
     * safe here: resolving is a pure, deterministic function of {@link
     * #contentBase64}/{@link #contentCompressed}, so two threads racing to
     * populate this cache before a `volatile` write becomes visible only do
     * some redundant, harmless work, never produce an incorrect result.
     */
    private transient volatile byte[] decodedContent;

    /**
     * Full constructor. Prefer {@link #StoredFile(String, String, byte[])}
     * unless the caller already has a specific {@link FileChecksum} or
     * timestamps to preserve (e.g. re-hydrating a file downloaded earlier
     * rather than freshly uploading one).
     *
     * @param fileId this file's unique id, its {@link #primaryKey()}
     * @param fileName the original file name; also the sole source of {@link #contentType()} - see {@link Constraints#CONTENT_TYPES}
     * @param content the file's raw bytes, of any type; defensively copied
     * @param checksum the plaintext checksum {@code content} must match on every future download
     * @param createdAt when this file was first uploaded
     * @param updatedAt when this file's content was last changed
     * @throws NullPointerException if {@code fileId}, {@code fileName}, {@code content}, {@code checksum}, {@code createdAt}, or {@code updatedAt} is {@code null}
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
     * #checksum()} over {@code content} itself (with {@link
     * HashAlgorithm#SHA_256}), and stamps {@link #createdAt()}/{@link
     * #updatedAt()} with the current time - the common case, so a caller
     * uploading a file never has to compute its own checksum or timestamps.
     *
     * @param fileId this file's unique id, its {@link #primaryKey()}
     * @param fileName the original file name; also the sole source of {@link #contentType()} - see {@link Constraints#CONTENT_TYPES}
     * @param content the file's raw bytes, of any type; defensively copied
     * @throws NullPointerException if {@code fileId}, {@code fileName}, or {@code content} is {@code null}
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
     * A content type inferred from {@code fileName}'s extension via {@link
     * Constraints#CONTENT_TYPES}, falling back to {@link
     * #DEFAULT_CONTENT_TYPE} if {@code fileName} has no extension or an
     * unrecognized one.
     */
    private static String normalizeContentType(final String fileName) {
        final String extension = extractExtension(fileName);
        final String inferred = extension == null ? null : Constraints.CONTENT_TYPES.get(extension);
        return inferred != null ? inferred : DEFAULT_CONTENT_TYPE;
    }

    /**
     * The lowercase file extension (without the leading dot) of {@code
     * fileName}, or {@code null} if it has none - i.e. no {@code '.'}, or a
     * {@code '.'} only as its very first or very last character (a hidden
     * file like {@code ".gitignore"} has no extension by this definition,
     * not an extension named {@code "gitignore"}).
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

    @Override
    public List<String> keysOf() {
        return List.of(fileId);
    }

    /**
     * This file's unique id, its {@link #primaryKey()}.
     */
    public String fileId() {
        return fileId;
    }

    /**
     * This file's original file name, as uploaded.
     */
    public String fileName() {
        return fileName;
    }

    /**
     * This file's MIME content type, inferred from {@link #fileName()}'s
     * extension - see the class Javadoc.
     */
    public String contentType() {
        return contentType;
    }

    /**
     * This file's raw bytes. Defensively cloned on every call, the same way
     * {@link de.lino.cloud.api.security.crypto.EncryptedPayload}'s array
     * accessors are, so neither the caller nor this instance can mutate
     * shared state after the fact.
     */
    public byte[] content() {
        return resolveContent().clone();
    }

    /**
     * The size, in bytes, of this file's original, uncompressed content.
     */
    public long sizeBytes() {
        return resolveContent().length;
    }

    /**
     * The plaintext checksum this file's content must match on every future download - see the class Javadoc.
     */
    public FileChecksum checksum() {
        return checksum;
    }

    /**
     * Whether this file is stored DEFLATE-compressed - see the class
     * Javadoc's "Compression" section. Purely informational: {@link
     * #content()} and every other accessor already account for this
     * transparently, so callers never need to check it themselves to get
     * correct behavior.
     */
    public boolean isCompressed() {
        return contentCompressed;
    }

    /**
     * When this file was first uploaded.
     */
    public Instant createdAt() {
        return Instant.ofEpochMilli(createdAtEpochMilli);
    }

    /**
     * When this file's content was last changed.
     */
    public Instant updatedAt() {
        return Instant.ofEpochMilli(updatedAtEpochMilli);
    }

    /**
     * This file's descriptive attributes without its content - see {@link FileMetadata}.
     */
    public FileMetadata metadata() {
        return new FileMetadata(fileId, fileName, contentType, sizeBytes(), checksum, createdAt(), updatedAt());
    }

    /**
     * Whether this file's content still matches its recorded {@link
     * #checksum()} - the same check {@link FileChecksum#matches(byte[])}
     * performs, but working directly off {@link #resolveContent()} instead
     * of first defensively copying it through {@link #content()}, so
     * verifying a large file's integrity costs one pass over its bytes
     * rather than a full clone plus one pass. Callers that only need to
     * know whether a file is intact - not its content - should prefer this
     * over {@code checksum().matches(content())}.
     *
     * @return {@code true} if this file's content matches {@link #checksum()}
     */
    public boolean verifyChecksum() {
        return checksum.matches(resolveContent());
    }

    /**
     * Re-creates this file on the local filesystem inside {@code
     * destination} - a directory, created first if it does not exist yet -
     * as faithfully as the local filesystem allows: the file is written
     * under its own {@link #fileName() file name}, so its suffix/extension
     * survives exactly as uploaded rather than depending on whatever name a
     * caller might otherwise pick, and its last-modified time is set to
     * {@link #updatedAt()} so the recreated file's own filesystem metadata
     * reflects when its content actually last changed, not the moment of
     * this download. Setting the file's creation time to {@link
     * #createdAt()} is attempted on a best-effort basis - not every
     * filesystem exposes a settable creation time (e.g. most Linux
     * filesystems do not), so that one step is silently skipped rather than
     * failing the whole download when unsupported.
     *
     * @param destination the local directory to (re)create this file in
     * @return the path of the recreated file - {@code destination} resolved against {@link #fileName()}
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
     * This file's raw, original bytes: decodes {@link #contentBase64} into
     * {@link #decodedContent} on first access - decompressing it first if
     * {@link #contentCompressed} - and reuses that cache afterward. Never
     * returned directly to callers outside this class - {@link #content()}
     * clones it first, the same defensive-copy guarantee the old {@code
     * byte[] content} field gave for free.
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
     * DEFLATE-compresses {@code data} (compress-then-encrypt is the correct
     * order for this class's callers - see the class Javadoc). Always
     * succeeds and never throws: {@link Deflater} has no failure mode for
     * arbitrary input bytes, unlike formats that reject malformed input.
     * The caller decides whether the result is worth keeping by comparing
     * its length against {@code data}'s.
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
     * @throws IllegalStateException if {@code compressed} is not valid DEFLATE data - this
     *         would mean the stored record itself is corrupted, since only this class's
     *         own {@link #deflate(byte[])} output is ever stored here in the first place
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
