package de.lino.cloud.platform.rest.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * Client-side implementation of the server's chunked AES-256-GCM content scheme, for presigned
 * direct-to-client transfers: the server issues a per-file content key over the authenticated
 * API ({@code POST /files/upload-url}'s {@code encryption} object) and this class produces/reads
 * the exact stored-object layout the server's own {@code ChunkedAesGcmStreamingService} (in
 * {@code cloud-driver-plugin}) does - deliberately reimplemented here rather than depended on,
 * since client modules never depend on server modules.
 *
 * <p><b>Wire format</b> (everything big-endian), after the server-supplied header written
 * verbatim at offset 0:
 *
 * <pre>
 * baseNonce                      4 random bytes (12-byte GCM nonce minus the 8-byte counter)
 * repeated chunk frames:
 *   flags                        1 byte - 0x01 marks the final chunk, 0x00 any other
 *   ciphertextLength             4-byte int
 *   ciphertext                   chunk ciphertext, 16-byte GCM tag appended
 * </pre>
 *
 * <p>Chunk {@code i}'s nonce is {@code baseNonce || bigEndianLong(i)}; its associated data is
 * {@code associatedDataPrefix (UTF-8) || bigEndianLong(i) || flags}. The stream always ends with
 * a final-flagged chunk (empty when the plaintext length is an exact chunk-size multiple), and
 * {@link #decrypt} fails closed - truncation, reordering, or trailing data all reject the stream
 * rather than yielding a shorter or altered "valid" file.
 */
public final class ChunkedContentCipher {

    /** GCM nonce length, in bytes. */
    private static final int NONCE_LENGTH_BYTES = 12;

    /** Bytes of each chunk's nonce taken by the big-endian chunk counter; the rest is the per-stream random base. */
    private static final int CHUNK_COUNTER_LENGTH_BYTES = Long.BYTES;

    /** Length, in bytes, of each stream's random nonce base. */
    private static final int BASE_NONCE_LENGTH_BYTES = NONCE_LENGTH_BYTES - CHUNK_COUNTER_LENGTH_BYTES;

    /** GCM authentication tag length, in bits/bytes. */
    private static final int TAG_LENGTH_BITS = 128;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / Byte.SIZE;

    /** {@code flags} value marking the final chunk of a stream. */
    private static final byte FLAG_FINAL = 0x01;

    /** {@code flags} value for every chunk before the final one. */
    private static final byte FLAG_NOT_FINAL = 0x00;

    /** Hard upper bound on a declared chunk ciphertext length during decryption (64 MiB) - read before authentication, so it must not be able to demand a huge allocation. */
    private static final int MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES = 1 << 26;

    /** Source of each stream's fresh random {@code baseNonce}. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Not instantiable - every method is static. */
    private ChunkedContentCipher() {
    }

    /**
     * Encrypts {@code plaintext} into {@code sink} as one complete stored object: {@code header}
     * verbatim, then the nonce base and chunk frames. Memory use is O({@code chunkSizeBytes}).
     * Neither stream is closed.
     *
     * @param plaintext the file's plaintext, read to EOF
     * @param sink where the stored object's bytes are written
     * @param keyMaterial the raw content key from the server's {@code encryption.contentKeyBase64}
     * @param header the server-supplied header ({@code encryption.headerBase64}), written verbatim first
     * @param associatedDataPrefix the server-supplied {@code encryption.associatedDataPrefix}
     * @param chunkSizeBytes the server-supplied {@code encryption.chunkSizeBytes}
     * @throws IOException if reading {@code plaintext} or writing {@code sink} fails
     * @throws GeneralSecurityException if the JVM's AES-GCM rejects the parameters
     */
    public static void encrypt(final InputStream plaintext, final OutputStream sink, final byte[] keyMaterial,
                               final byte[] header, final String associatedDataPrefix, final int chunkSizeBytes)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(header, "header");
        if (chunkSizeBytes <= 0) {
            throw new IllegalArgumentException("chunkSizeBytes must be positive, got " + chunkSizeBytes);
        }

        final SecretKey key = new SecretKeySpec(keyMaterial, "AES");
        final byte[] prefix = prefixBytes(associatedDataPrefix);
        final byte[] baseNonce = new byte[BASE_NONCE_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(baseNonce);

        final DataOutputStream out = new DataOutputStream(sink);
        out.write(header);
        out.write(baseNonce);

        long chunkIndex = 0;
        while (true) {
            final byte[] chunk = plaintext.readNBytes(chunkSizeBytes);
            // A short read only ever means EOF, so a full chunk stays non-final - the next round
            // serves the remainder, or an empty final chunk on an exact chunk-size multiple.
            final byte flags = chunk.length < chunkSizeBytes ? FLAG_FINAL : FLAG_NOT_FINAL;
            final byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, key, baseNonce, chunkIndex, flags, prefix, chunk);
            out.writeByte(flags);
            out.writeInt(ciphertext.length);
            out.write(ciphertext);
            chunkIndex++;
            if (flags == FLAG_FINAL) {
                out.flush();
                return;
            }
        }
    }

    /**
     * Decrypts a stored object fetched from a presigned download URL into {@code sink}, verifying
     * every chunk's authentication tag before writing any of its plaintext. The leading {@code
     * headerLengthBytes} bytes (the server's wrapped-key header - the raw key arrives via the API
     * instead) are skipped, not interpreted. Fails closed: truncation anywhere, an altered or
     * reordered chunk, or trailing data all throw before {@code sink} sees unverified bytes.
     * Neither stream is closed.
     *
     * @param storedObject the fetched object's bytes, read to EOF
     * @param sink where the verified plaintext is written
     * @param keyMaterial the raw content key from the server's {@code encryption.contentKeyBase64}
     * @param associatedDataPrefix the server-supplied {@code encryption.associatedDataPrefix}
     * @param headerLengthBytes the server-supplied {@code encryption.headerLengthBytes}
     * @throws IOException if reading {@code storedObject} or writing {@code sink} fails, or the
     *     stream is truncated/malformed
     * @throws GeneralSecurityException if a chunk fails authentication ({@link AEADBadTagException})
     *     or the JVM's AES-GCM rejects the parameters
     */
    public static void decrypt(final InputStream storedObject, final OutputStream sink, final byte[] keyMaterial,
                               final String associatedDataPrefix, final int headerLengthBytes)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(storedObject, "storedObject");
        Objects.requireNonNull(sink, "sink");
        if (headerLengthBytes < 0) {
            throw new IllegalArgumentException("headerLengthBytes cannot be negative, got " + headerLengthBytes);
        }

        final SecretKey key = new SecretKeySpec(keyMaterial, "AES");
        final byte[] prefix = prefixBytes(associatedDataPrefix);

        final DataInputStream in = new DataInputStream(storedObject);
        try {
            in.skipNBytes(headerLengthBytes);
            final byte[] baseNonce = new byte[BASE_NONCE_LENGTH_BYTES];
            in.readFully(baseNonce);

            long chunkIndex = 0;
            while (true) {
                final int flagsRead = in.read();
                if (flagsRead == -1) {
                    throw new EOFException("stream ended before a final chunk was seen - rejected");
                }
                final byte flags = (byte) flagsRead;
                if (flags != FLAG_FINAL && flags != FLAG_NOT_FINAL) {
                    throw new IOException("unknown chunk flags value " + flagsRead + " - rejected");
                }
                final int ciphertextLength = in.readInt();
                if (ciphertextLength < TAG_LENGTH_BYTES || ciphertextLength > MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES) {
                    throw new IOException("implausible chunk ciphertext length " + ciphertextLength + " - rejected");
                }
                final byte[] ciphertext = new byte[ciphertextLength];
                in.readFully(ciphertext);
                sink.write(crypt(Cipher.DECRYPT_MODE, key, baseNonce, chunkIndex, flags, prefix, ciphertext));
                chunkIndex++;
                if (flags == FLAG_FINAL) {
                    if (in.read() != -1) {
                        throw new IOException("trailing data after the final chunk - rejected");
                    }
                    sink.flush();
                    return;
                }
            }
        } catch (final EOFException e) {
            throw new EOFException("stream truncated mid-chunk - rejected");
        }
    }

    /**
     * The exact stored-object length a plaintext of {@code plaintextLength} bytes produces -
     * mirrors the server's own formula, letting the client verify its encrypted file against the
     * ticket's {@code encryption.objectLengthBytes} before uploading.
     *
     * @param headerLengthBytes the server-supplied header's length
     * @param plaintextLength the plaintext's length, in bytes
     * @param chunkSizeBytes the server-supplied chunk size
     * @return the stored object's exact total length, in bytes
     */
    public static long encryptedLength(final int headerLengthBytes, final long plaintextLength, final int chunkSizeBytes) {
        if (headerLengthBytes < 0 || plaintextLength < 0 || chunkSizeBytes <= 0) {
            throw new IllegalArgumentException("invalid lengths: header=" + headerLengthBytes
                    + ", plaintext=" + plaintextLength + ", chunk=" + chunkSizeBytes);
        }
        final long frames = plaintextLength / chunkSizeBytes + 1;
        final long perFrameOverhead = 1L + Integer.BYTES + TAG_LENGTH_BYTES;
        return (long) headerLengthBytes + BASE_NONCE_LENGTH_BYTES + frames * perFrameOverhead + plaintextLength;
    }

    /** One chunk's AES-GCM operation - encrypt or decrypt, per {@code mode}. */
    private static byte[] crypt(final int mode, final SecretKey key, final byte[] baseNonce, final long chunkIndex,
                                final byte flags, final byte[] prefix, final byte[] input) throws GeneralSecurityException {
        final byte[] nonce = ByteBuffer.allocate(NONCE_LENGTH_BYTES).put(baseNonce).putLong(chunkIndex).array();
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        cipher.updateAAD(ByteBuffer.allocate(prefix.length + Long.BYTES + 1).put(prefix).putLong(chunkIndex).put(flags).array());
        return cipher.doFinal(input);
    }

    /** {@code null}-tolerant UTF-8 bytes of the associated-data prefix. */
    private static byte[] prefixBytes(final String associatedDataPrefix) {
        return associatedDataPrefix == null ? new byte[0] : associatedDataPrefix.getBytes(StandardCharsets.UTF_8);
    }
}
