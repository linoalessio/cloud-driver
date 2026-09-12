package de.lino.cloud.api.file;

import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * The per-chunk plaintext hash manifest of one {@link StoredFile}'s current content - the
 * server-side half of chunk-level diffing (roadmap Phase 4, format signed off by Lino
 * 2026-09-12): a sync client fetches this via {@code GET /files/{id}/chunk-manifest}, hashes its
 * own local copy the same way, and sends only the chunks that actually differ through {@code
 * PATCH /files/{id}/content} - so the cost of a small edit to a large file scales with the edit,
 * not the file.
 *
 * <p>Chunks are fixed-size ({@link Constraints#CONTENT_CHUNK_SIZE_BYTES}, the same boundary the
 * streaming AEAD encryption uses - chunk identity and encryption boundary are deliberately the
 * same concept), hashed with SHA-256 over the <em>plaintext</em>, compared positionally. Stored
 * as one row per file (primary key = {@link #fileId()}), envelope-encrypted like every other
 * entity - chunk hashes are content-derived fingerprints and must never sit plaintext in the
 * database.
 *
 * <p><b>When a manifest exists.</b> Maintained by {@code CloudUserService} on every content
 * write it sees the plaintext of (server-mediated upload - streamed or in-memory - and content
 * replacement, {@code PATCH} included). Deliberately absent for: a dedup alias (it owns no
 * content), a presigned direct-transfer file (this server never sees its plaintext), and any
 * file last written before this mechanism existed. An absent manifest simply means "diffing not
 * available - fall back to a full upload", never an error.
 *
 * <p>Best-effort by design: a failed manifest write only disables diffing for that file until
 * its next content write - it must never fail the upload/replace it rides along with.
 */
@ToString(exclude = "chunkHashesBase64")
@EqualsAndHashCode(callSuper = false)
public final class FileChunkManifest extends Serialized {

    /** SHA-256 produces 32-byte digests; used to slice {@link #chunkHashesBase64} back apart. */
    private static final int HASH_LENGTH_BYTES = 32;

    /** The {@link StoredFile#fileId()} this manifest describes - this row's {@link #primaryKey()}. */
    private final String fileId;

    /** Plaintext bytes per chunk this manifest was computed with - always {@link Constraints#CONTENT_CHUNK_SIZE_BYTES} today, recorded so a future chunk-size change can't silently mis-compare. */
    private final int chunkSizeBytes;

    /** The content's total plaintext size in bytes - determines the final chunk's (shorter) length. */
    private final long totalSizeBytes;

    /** Every chunk's SHA-256, concatenated in order, base64-encoded (a single string - Gson would serialize a {@code byte[]} as an exploded number array). */
    private final String chunkHashesBase64;

    /** When this manifest was (re)computed, as epoch milliseconds. */
    private final long updatedAtEpochMillis;

    /**
     * @param fileId the file this manifest describes
     * @param chunkSizeBytes plaintext bytes per chunk the hashes were computed with
     * @param totalSizeBytes the content's total plaintext size
     * @param chunkHashes every chunk's SHA-256, concatenated in order
     * @throws NullPointerException if {@code fileId} or {@code chunkHashes} is {@code null}
     * @throws IllegalArgumentException if {@code chunkHashes}' length isn't a whole number of
     *     SHA-256 digests, or doesn't match the chunk count {@code totalSizeBytes} implies
     */
    public FileChunkManifest(@NotNull final String fileId, final int chunkSizeBytes, final long totalSizeBytes,
                              final byte[] chunkHashes) {
        this.fileId = Asserts.requireNonNull(fileId, "@FileChunkManifest: fileId cannot be null");
        Asserts.requireNonNull(chunkHashes, "@FileChunkManifest: chunkHashes cannot be null");
        if (chunkHashes.length % HASH_LENGTH_BYTES != 0) {
            throw new IllegalArgumentException(
                    "@FileChunkManifest: chunkHashes length " + chunkHashes.length + " is not a whole number of SHA-256 digests");
        }
        final int expectedChunks = chunkCountFor(totalSizeBytes, chunkSizeBytes);
        if (chunkHashes.length / HASH_LENGTH_BYTES != expectedChunks) {
            throw new IllegalArgumentException("@FileChunkManifest: " + (chunkHashes.length / HASH_LENGTH_BYTES)
                    + " hashes given but " + totalSizeBytes + " bytes at " + chunkSizeBytes + " bytes/chunk implies " + expectedChunks);
        }
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalSizeBytes = totalSizeBytes;
        this.chunkHashesBase64 = Base64.getEncoder().encodeToString(chunkHashes);
        this.updatedAtEpochMillis = System.currentTimeMillis();
    }

    /**
     * Computes the manifest of {@code content} at the standard {@link
     * Constraints#CONTENT_CHUNK_SIZE_BYTES} chunk size.
     *
     * @param fileId the file the content belongs to
     * @param content the full plaintext content
     * @return the freshly computed manifest
     * @throws NullPointerException if {@code fileId} or {@code content} is {@code null}
     */
    @NotNull
    public static FileChunkManifest of(@NotNull final String fileId, final byte[] content) {
        Asserts.requireNonNull(content, "@FileChunkManifest.of: content cannot be null");
        final MessageDigest digest = sha256();
        final int chunkSize = Constraints.CONTENT_CHUNK_SIZE_BYTES;
        final int chunkCount = chunkCountFor(content.length, chunkSize);
        final byte[] hashes = new byte[chunkCount * HASH_LENGTH_BYTES];
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            final int offset = chunk * chunkSize;
            digest.reset();
            digest.update(content, offset, Math.min(chunkSize, content.length - offset));
            try {
                digest.digest(hashes, chunk * HASH_LENGTH_BYTES, HASH_LENGTH_BYTES);
            } catch (final java.security.DigestException impossible) {
                throw new IllegalStateException("@FileChunkManifest.of: SHA-256 digest into a correctly-sized buffer failed", impossible);
            }
        }
        return new FileChunkManifest(fileId, chunkSize, content.length, hashes);
    }

    /**
     * Streaming counterpart of {@link #of(String, byte[])} for content too large to hold in
     * memory (a scratch-file-backed upload): reads {@code content} once, chunk by chunk. The
     * stream is drained fully but deliberately not closed - the caller owns it.
     *
     * @param fileId the file the content belongs to
     * @param content the full plaintext content stream; drained fully, not closed
     * @return the freshly computed manifest
     * @throws NullPointerException if {@code fileId} or {@code content} is {@code null}
     * @throws UncheckedIOException if reading {@code content} fails
     */
    @NotNull
    public static FileChunkManifest of(@NotNull final String fileId, @NotNull final InputStream content) {
        Asserts.requireNonNull(content, "@FileChunkManifest.of: content cannot be null");
        final MessageDigest digest = sha256();
        final int chunkSize = Constraints.CONTENT_CHUNK_SIZE_BYTES;
        final byte[] buffer = new byte[chunkSize];
        final List<byte[]> hashes = new ArrayList<>();
        long totalBytes = 0;
        try {
            while (true) {
                final int chunkBytes = content.readNBytes(buffer, 0, chunkSize);
                if (chunkBytes == 0) {
                    break;
                }
                totalBytes += chunkBytes;
                digest.reset();
                digest.update(buffer, 0, chunkBytes);
                hashes.add(digest.digest());
                if (chunkBytes < chunkSize) {
                    break;
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("@FileChunkManifest.of: failed reading the content stream for '" + fileId + "'", e);
        }
        final byte[] concatenated = new byte[hashes.size() * HASH_LENGTH_BYTES];
        for (int chunk = 0; chunk < hashes.size(); chunk++) {
            System.arraycopy(hashes.get(chunk), 0, concatenated, chunk * HASH_LENGTH_BYTES, HASH_LENGTH_BYTES);
        }
        return new FileChunkManifest(fileId, chunkSize, totalBytes, concatenated);
    }

    /** How many chunks {@code totalSizeBytes} splits into at {@code chunkSizeBytes} - zero-length content has zero chunks. */
    public static int chunkCountFor(final long totalSizeBytes, final int chunkSizeBytes) {
        return (int) ((totalSizeBytes + chunkSizeBytes - 1) / chunkSizeBytes);
    }

    /** @return this entity's primary key, a single-element list containing {@link #fileId} */
    @Override
    public List<String> keysOf() {
        return List.of(this.fileId);
    }

    /** The {@link StoredFile#fileId()} this manifest describes. */
    public String fileId() {
        return this.fileId;
    }

    /** Plaintext bytes per chunk the hashes were computed with. */
    public int chunkSizeBytes() {
        return this.chunkSizeBytes;
    }

    /** The content's total plaintext size in bytes. */
    public long totalSizeBytes() {
        return this.totalSizeBytes;
    }

    /** How many chunks this manifest holds hashes for. */
    public int chunkCount() {
        return chunkCountFor(this.totalSizeBytes, this.chunkSizeBytes);
    }

    /** When this manifest was (re)computed, as epoch milliseconds. */
    public long updatedAtEpochMillis() {
        return this.updatedAtEpochMillis;
    }

    /**
     * Every chunk's SHA-256 as a lowercase hex string, in chunk order - the shape {@code GET
     * /files/{id}/chunk-manifest} serializes, and what a client compares its own local hashes
     * against positionally.
     *
     * @return every chunk hash, hex-encoded, in order
     */
    @NotNull
    public List<String> chunkHashesHex() {
        final byte[] hashes = Base64.getDecoder().decode(this.chunkHashesBase64);
        final List<String> hex = new ArrayList<>(hashes.length / HASH_LENGTH_BYTES);
        for (int offset = 0; offset < hashes.length; offset += HASH_LENGTH_BYTES) {
            hex.add(HexFormat.of().formatHex(hashes, offset, offset + HASH_LENGTH_BYTES));
        }
        return hex;
    }

    /**
     * Every chunk's SHA-256, concatenated in order, base64-encoded - the compact persisted form,
     * exposed so version-delta capture ({@code cloud-driver-extensions-versioning}) can store a
     * version's hash list and later compare it positionally without a second hashing scheme.
     *
     * @return the concatenated hashes, base64-encoded
     */
    @NotNull
    public String chunkHashesBase64() {
        return this.chunkHashesBase64;
    }

    /**
     * Whether chunk {@code index} of the manifest encoded in {@code hashesBase64A} equals the
     * same chunk of {@code hashesBase64B} - positional comparison of two stored hash lists;
     * {@code false} when either list has no chunk {@code index} at all.
     *
     * @param hashesBase64A one manifest's {@link #chunkHashesBase64()}
     * @param hashesBase64B another manifest's {@link #chunkHashesBase64()}
     * @param index the 0-based chunk to compare
     * @return {@code true} iff both lists carry chunk {@code index} and the hashes are identical
     */
    public static boolean chunkHashEquals(@NotNull final String hashesBase64A, @NotNull final String hashesBase64B, final int index) {
        final byte[] hashesA = Base64.getDecoder().decode(hashesBase64A);
        final byte[] hashesB = Base64.getDecoder().decode(hashesBase64B);
        final int offset = index * HASH_LENGTH_BYTES;
        if (offset + HASH_LENGTH_BYTES > hashesA.length || offset + HASH_LENGTH_BYTES > hashesB.length) {
            return false;
        }
        for (int i = 0; i < HASH_LENGTH_BYTES; i++) {
            if (hashesA[offset + i] != hashesB[offset + i]) {
                return false;
            }
        }
        return true;
    }

    /** A fresh SHA-256 {@link MessageDigest} - every JVM this codebase supports provides it. */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@FileChunkManifest: JVM does not provide SHA-256", e);
        }
    }
}
