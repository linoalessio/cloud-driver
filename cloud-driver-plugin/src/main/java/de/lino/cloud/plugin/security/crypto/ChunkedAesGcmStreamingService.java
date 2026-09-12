package de.lino.cloud.plugin.security.crypto;

import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.crypto.StreamingAeadEncryptionService;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * {@link StreamingAeadEncryptionService} backed by chunked AES-GCM (AES-256-GCM by default) - the
 * streaming sibling of {@link AesGcmEncryptionService}, mirroring its constructor/algorithm-selection
 * style. Never feeds more than one {@link #chunkSizeBytes}-sized chunk through the JCA cipher at a
 * time, so memory use is O(chunk size) regardless of payload size.
 *
 * <p><b>Wire format</b> (everything big-endian):
 *
 * <pre>
 * baseNonce                      nonceLength - 8 random bytes, drawn fresh per stream
 * repeated chunk frames:
 *   flags                        1 byte - 0x01 marks the final chunk, 0x00 any other
 *   ciphertextLength             4-byte int
 *   ciphertext                   chunk ciphertext, GCM tag appended
 * </pre>
 *
 * <p>Each chunk is its own independent AES-GCM operation. Its nonce is {@code baseNonce ||
 * bigEndianLong(chunkIndex)} - the monotonic counter guarantees no nonce ever repeats under the
 * same key, without per-chunk randomness. Its associated data is {@code associatedDataPrefix ||
 * bigEndianLong(chunkIndex) || flags} - binding the index defeats chunk reordering/substitution,
 * and binding the final-chunk flag (together with {@link DecryptingInputStream}'s fail-closed
 * "stream must end with a verified final chunk, and nothing may follow it" rule) defeats
 * truncation and extension. A stream always ends with a final chunk: a plaintext whose length is
 * an exact multiple of the chunk size gets an empty final chunk, which keeps {@link
 * #encryptedLength(long)} a closed formula.
 *
 * <p>Safe for concurrent use; each returned stream is single-threaded like any other {@code
 * java.io} stream.
 */
public final class ChunkedAesGcmStreamingService implements StreamingAeadEncryptionService {

    /** The AES-GCM variant used when no {@link CryptoAlgorithm} is given explicitly. */
    private static final CryptoAlgorithm DEFAULT_ALGORITHM = CryptoAlgorithm.AES_256_GCM;

    /** Plaintext bytes per chunk when no explicit chunk size is given - the one process-wide chunk convention, see {@link de.lino.cloud.api.utility.Constraints#CONTENT_CHUNK_SIZE_BYTES}. */
    public static final int DEFAULT_CHUNK_SIZE_BYTES = de.lino.cloud.api.utility.Constraints.CONTENT_CHUNK_SIZE_BYTES;

    /** Bytes of each chunk's nonce taken by the big-endian chunk counter; the rest is the per-stream random base. */
    private static final int CHUNK_COUNTER_LENGTH_BYTES = Long.BYTES;

    /** {@code flags} value marking the final chunk of a stream. */
    private static final byte FLAG_FINAL = 0x01;

    /** {@code flags} value for every chunk before the final one. */
    private static final byte FLAG_NOT_FINAL = 0x00;

    /**
     * Hard upper bound on a single chunk frame's declared ciphertext length during decryption (64
     * MiB) - the length prefix is read before its chunk is authenticated, so without this cap a
     * forged prefix could demand an arbitrarily large allocation.
     */
    private static final int MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES = 1 << 26;

    /** The AES-GCM variant this instance encrypts/decrypts with. */
    private final CryptoAlgorithm algorithm;

    /** Plaintext bytes per chunk on the encrypting side. */
    private final int chunkSizeBytes;

    /** Source of each stream's fresh random {@code baseNonce}. */
    private final SecureRandom secureRandom;

    /**
     * Constructs a service using {@link #DEFAULT_ALGORITHM} (AES-256-GCM) and {@link
     * #DEFAULT_CHUNK_SIZE_BYTES}.
     */
    public ChunkedAesGcmStreamingService() {
        this(DEFAULT_ALGORITHM, DEFAULT_CHUNK_SIZE_BYTES);
    }

    /**
     * Constructs a service using {@code algorithm} and {@link #DEFAULT_CHUNK_SIZE_BYTES}.
     *
     * @param algorithm the AES-GCM variant to encrypt/decrypt with
     * @throws NullPointerException if {@code algorithm} is {@code null}
     */
    public ChunkedAesGcmStreamingService(@NotNull final CryptoAlgorithm algorithm) {
        this(algorithm, DEFAULT_CHUNK_SIZE_BYTES);
    }

    /**
     * @param algorithm the AES-GCM variant to encrypt/decrypt with
     * @param chunkSizeBytes plaintext bytes per chunk on the encrypting side
     * @throws NullPointerException if {@code algorithm} is {@code null}
     * @throws IllegalArgumentException if {@code chunkSizeBytes} is not positive, exceeds {@link
     *     #MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES} once the tag is added, or {@code algorithm}'s nonce
     *     is too short to fit the {@link #CHUNK_COUNTER_LENGTH_BYTES}-byte chunk counter
     */
    public ChunkedAesGcmStreamingService(@NotNull final CryptoAlgorithm algorithm, final int chunkSizeBytes) {
        this.algorithm = Asserts.requireNonNull(algorithm, "@ChunkedAesGcmStreamingService: algorithm cannot be null");
        if (chunkSizeBytes <= 0 || chunkSizeBytes + this.tagLengthBytes() > MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "@ChunkedAesGcmStreamingService: chunkSizeBytes must be in [1, "
                            + (MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES - this.tagLengthBytes()) + "], got " + chunkSizeBytes
            );
        }
        if (this.baseNonceLengthBytes() <= 0) {
            throw new IllegalArgumentException(
                    "@ChunkedAesGcmStreamingService: algorithm '" + algorithm.id() + "' nonce ("
                            + algorithm.nonceLengthBytes() + " bytes) cannot fit an " + CHUNK_COUNTER_LENGTH_BYTES
                            + "-byte chunk counter"
            );
        }
        this.chunkSizeBytes = chunkSizeBytes;
        this.secureRandom = new SecureRandom();
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public OutputStream encryptingOutputStream(@NotNull final OutputStream sink, @NotNull final SecretKey key,
                                               @Nullable final byte[] associatedDataPrefix) {
        Asserts.requireNonNull(sink, "@ChunkedAesGcmStreamingService.encryptingOutputStream: sink cannot be null");
        Asserts.requireNonNull(key, "@ChunkedAesGcmStreamingService.encryptingOutputStream: key cannot be null");
        return new EncryptingOutputStream(sink, key, normalizePrefix(associatedDataPrefix));
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public InputStream encryptingInputStream(@NotNull final InputStream plaintext, @NotNull final SecretKey key,
                                             @Nullable final byte[] associatedDataPrefix) {
        Asserts.requireNonNull(plaintext, "@ChunkedAesGcmStreamingService.encryptingInputStream: plaintext cannot be null");
        Asserts.requireNonNull(key, "@ChunkedAesGcmStreamingService.encryptingInputStream: key cannot be null");
        return new EncryptingInputStream(plaintext, key, normalizePrefix(associatedDataPrefix));
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public InputStream decryptingInputStream(@NotNull final InputStream source, @NotNull final SecretKey key,
                                             @Nullable final byte[] associatedDataPrefix)
            throws AuthenticationFailedException, IOException {
        Asserts.requireNonNull(source, "@ChunkedAesGcmStreamingService.decryptingInputStream: source cannot be null");
        Asserts.requireNonNull(key, "@ChunkedAesGcmStreamingService.decryptingInputStream: key cannot be null");
        return new DecryptingInputStream(source, key, normalizePrefix(associatedDataPrefix));
    }

    /** {@inheritDoc} */
    @Override
    public long encryptedLength(final long plaintextLength) {
        if (plaintextLength < 0) {
            throw new IllegalArgumentException(
                    "@ChunkedAesGcmStreamingService.encryptedLength: plaintextLength cannot be negative, got " + plaintextLength
            );
        }
        // Full chunks, plus one final chunk holding the (possibly empty) remainder - see the
        // class Javadoc for why an exact-multiple plaintext still ends with an empty final chunk.
        final long frames = plaintextLength / this.chunkSizeBytes + 1;
        final long perFrameOverhead = 1L + Integer.BYTES + this.tagLengthBytes();
        return this.baseNonceLengthBytes() + frames * perFrameOverhead + plaintextLength;
    }

    /** Plaintext bytes per chunk on the encrypting side - what a compatible external encryptor (e.g. a presigned-upload client) must chunk with for {@link #encryptedLength(long)} to hold. */
    public int chunkSizeBytes() {
        return this.chunkSizeBytes;
    }

    /** Length, in bytes, of each stream's random nonce base: the algorithm's nonce length minus the chunk counter's. */
    private int baseNonceLengthBytes() {
        return this.algorithm.nonceLengthBytes() - CHUNK_COUNTER_LENGTH_BYTES;
    }

    /** Length, in bytes, of the GCM authentication tag appended to each chunk's ciphertext. */
    private int tagLengthBytes() {
        return this.algorithm.tagLengthBits() / Byte.SIZE;
    }

    /** {@code null}-tolerant copy of the caller's associated-data prefix. */
    private static byte[] normalizePrefix(final byte[] associatedDataPrefix) {
        return associatedDataPrefix == null ? new byte[0] : associatedDataPrefix.clone();
    }

    /** This chunk's nonce: {@code baseNonce || bigEndianLong(chunkIndex)}. */
    private byte[] chunkNonce(final byte[] baseNonce, final long chunkIndex) {
        return ByteBuffer.allocate(this.algorithm.nonceLengthBytes()).put(baseNonce).putLong(chunkIndex).array();
    }

    /** This chunk's associated data: {@code associatedDataPrefix || bigEndianLong(chunkIndex) || flags}. */
    private static byte[] chunkAssociatedData(final byte[] associatedDataPrefix, final long chunkIndex, final byte flags) {
        return ByteBuffer.allocate(associatedDataPrefix.length + Long.BYTES + 1)
                .put(associatedDataPrefix).putLong(chunkIndex).put(flags).array();
    }

    /**
     * Encrypts one chunk: {@code plaintext[0..length)} under {@code key}, nonce and associated
     * data derived from {@code chunkIndex}/{@code flags} as this class's Javadoc describes.
     */
    private byte[] encryptChunk(final SecretKey key, final byte[] baseNonce, final long chunkIndex, final byte flags,
                                final byte[] associatedDataPrefix, final byte[] plaintext, final int length) {
        try {
            final Cipher cipher = Cipher.getInstance(this.algorithm.transformation());
            cipher.init(Cipher.ENCRYPT_MODE, key,
                    new GCMParameterSpec(this.algorithm.tagLengthBits(), this.chunkNonce(baseNonce, chunkIndex)));
            cipher.updateAAD(chunkAssociatedData(associatedDataPrefix, chunkIndex, flags));
            return cipher.doFinal(plaintext, 0, length);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(
                    "@ChunkedAesGcmStreamingService.encryptChunk: failed to encrypt chunk " + chunkIndex, e
            );
        }
    }

    /**
     * Decrypts and verifies one chunk - the inverse of {@link #encryptChunk}.
     *
     * @throws AuthenticationFailedException if the chunk's authentication tag does not verify
     */
    private byte[] decryptChunk(final SecretKey key, final byte[] baseNonce, final long chunkIndex, final byte flags,
                                final byte[] associatedDataPrefix, final byte[] ciphertext) throws AuthenticationFailedException {
        try {
            final Cipher cipher = Cipher.getInstance(this.algorithm.transformation());
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(this.algorithm.tagLengthBits(), this.chunkNonce(baseNonce, chunkIndex)));
            cipher.updateAAD(chunkAssociatedData(associatedDataPrefix, chunkIndex, flags));
            return cipher.doFinal(ciphertext);
        } catch (final AEADBadTagException e) {
            throw new AuthenticationFailedException(
                    "@ChunkedAesGcmStreamingService.decryptChunk: authentication tag verification failed for chunk "
                            + chunkIndex + " - stream rejected", e
            );
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(
                    "@ChunkedAesGcmStreamingService.decryptChunk: failed to decrypt chunk " + chunkIndex, e
            );
        }
    }

    /**
     * Push-based encryptor: buffers plaintext up to one chunk, emits each full chunk eagerly as
     * {@link #FLAG_NOT_FINAL} and the (possibly empty) remainder as {@link #FLAG_FINAL} on {@link
     * #close()} - so the frame sequence is identical to {@link EncryptingInputStream}'s and {@link
     * #encryptedLength(long)} holds for both.
     */
    private final class EncryptingOutputStream extends OutputStream {

        /** Where header and chunk frames are written. */
        private final DataOutputStream sink;
        /** The key every chunk is encrypted under. */
        private final SecretKey key;
        /** The caller's associated-data prefix, bound into every chunk. */
        private final byte[] associatedDataPrefix;
        /** This stream's random nonce base, written to {@link #sink} before the first frame. */
        private final byte[] baseNonce;
        /** Plaintext buffered for the chunk currently being filled. */
        private final byte[] buffer;
        /** How many bytes of {@link #buffer} are filled. */
        private int buffered;
        /** Index of the next chunk to emit. */
        private long chunkIndex;
        /** Whether {@link #baseNonce} has been written to {@link #sink} yet. */
        private boolean baseNonceWritten;
        /** Whether {@link #close()} has run. */
        private boolean closed;

        private EncryptingOutputStream(final OutputStream sink, final SecretKey key, final byte[] associatedDataPrefix) {
            this.sink = new DataOutputStream(sink);
            this.key = key;
            this.associatedDataPrefix = associatedDataPrefix;
            this.baseNonce = new byte[ChunkedAesGcmStreamingService.this.baseNonceLengthBytes()];
            ChunkedAesGcmStreamingService.this.secureRandom.nextBytes(this.baseNonce);
            this.buffer = new byte[ChunkedAesGcmStreamingService.this.chunkSizeBytes];
        }

        /** See {@link OutputStream#write(int)}. */
        @Override
        public void write(final int b) throws IOException {
            this.write(new byte[]{(byte) b}, 0, 1);
        }

        /** See {@link OutputStream#write(byte[], int, int)}. */
        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            Asserts.requireNonNull(bytes, "@ChunkedAesGcmStreamingService$EncryptingOutputStream.write: bytes cannot be null");
            this.ensureOpen();
            int position = offset;
            int remaining = length;
            while (remaining > 0) {
                final int copied = Math.min(this.buffer.length - this.buffered, remaining);
                System.arraycopy(bytes, position, this.buffer, this.buffered, copied);
                this.buffered += copied;
                position += copied;
                remaining -= copied;
                if (this.buffered == this.buffer.length) {
                    this.emitChunk(FLAG_NOT_FINAL);
                }
            }
        }

        /** Flushes {@code sink} only - plaintext buffered for the current chunk cannot be emitted before the chunk completes. */
        @Override
        public void flush() throws IOException {
            this.ensureOpen();
            this.sink.flush();
        }

        /** Emits the final chunk (possibly empty), zeroes the plaintext buffer, and closes the sink. Idempotent. */
        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try {
                this.emitChunk(FLAG_FINAL);
                Arrays.fill(this.buffer, (byte) 0);
            } finally {
                this.sink.close();
            }
        }

        /** Encrypts {@link #buffer}'s current content as one chunk frame and writes it to {@link #sink}. */
        private void emitChunk(final byte flags) throws IOException {
            if (!this.baseNonceWritten) {
                this.sink.write(this.baseNonce);
                this.baseNonceWritten = true;
            }
            final byte[] ciphertext = ChunkedAesGcmStreamingService.this.encryptChunk(
                    this.key, this.baseNonce, this.chunkIndex, flags, this.associatedDataPrefix, this.buffer, this.buffered
            );
            this.sink.writeByte(flags);
            this.sink.writeInt(ciphertext.length);
            this.sink.write(ciphertext);
            this.chunkIndex++;
            this.buffered = 0;
        }

        /** @throws IOException if this stream is already closed */
        private void ensureOpen() throws IOException {
            if (this.closed) {
                throw new IOException("@ChunkedAesGcmStreamingService$EncryptingOutputStream: stream already closed");
            }
        }
    }

    /**
     * Pull-based encryptor: serves the nonce base, then reads {@code plaintext} one chunk at a
     * time and serves each chunk's frame - a chunk read short of {@link #chunkSizeBytes} means
     * the source hit EOF and becomes the {@link #FLAG_FINAL} frame (empty for an exact-multiple
     * plaintext), matching {@link EncryptingOutputStream}'s frame sequence exactly.
     */
    private final class EncryptingInputStream extends InputStream {

        /** The plaintext being encrypted. */
        private final InputStream plaintext;
        /** The key every chunk is encrypted under. */
        private final SecretKey key;
        /** The caller's associated-data prefix, bound into every chunk. */
        private final byte[] associatedDataPrefix;
        /** This stream's random nonce base - also the first bytes served. */
        private final byte[] baseNonce;
        /** The bytes currently being served ({@link #baseNonce} first, then one frame at a time). */
        private byte[] current;
        /** Read position within {@link #current}. */
        private int position;
        /** Index of the next chunk to encrypt. */
        private long chunkIndex;
        /** Whether the {@link #FLAG_FINAL} frame has been produced - nothing follows it. */
        private boolean finalEmitted;

        private EncryptingInputStream(final InputStream plaintext, final SecretKey key, final byte[] associatedDataPrefix) {
            this.plaintext = plaintext;
            this.key = key;
            this.associatedDataPrefix = associatedDataPrefix;
            this.baseNonce = new byte[ChunkedAesGcmStreamingService.this.baseNonceLengthBytes()];
            ChunkedAesGcmStreamingService.this.secureRandom.nextBytes(this.baseNonce);
            this.current = this.baseNonce;
        }

        /** See {@link InputStream#read()}. */
        @Override
        public int read() throws IOException {
            final byte[] single = new byte[1];
            final int read = this.read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xFF;
        }

        /** See {@link InputStream#read(byte[], int, int)}. */
        @Override
        public int read(final byte[] into, final int offset, final int length) throws IOException {
            Asserts.requireNonNull(into, "@ChunkedAesGcmStreamingService$EncryptingInputStream.read: into cannot be null");
            if (length == 0) {
                return 0;
            }
            if (this.position == this.current.length && !this.nextFrame()) {
                return -1;
            }
            final int served = Math.min(length, this.current.length - this.position);
            System.arraycopy(this.current, this.position, into, offset, served);
            this.position += served;
            return served;
        }

        /** Closes the plaintext source. */
        @Override
        public void close() throws IOException {
            this.plaintext.close();
        }

        /**
         * Encrypts the next chunk into {@link #current}.
         *
         * @return {@code false} if the final frame was already emitted - the stream is at EOF
         */
        private boolean nextFrame() throws IOException {
            if (this.finalEmitted) {
                return false;
            }
            final byte[] chunk = this.plaintext.readNBytes(ChunkedAesGcmStreamingService.this.chunkSizeBytes);
            // A short read only ever means EOF (readNBytes blocks until the requested count or
            // EOF), so a full chunk stays FLAG_NOT_FINAL - the next round serves the remainder,
            // or an empty final chunk if the plaintext length was an exact multiple.
            final byte flags = chunk.length < ChunkedAesGcmStreamingService.this.chunkSizeBytes ? FLAG_FINAL : FLAG_NOT_FINAL;
            final byte[] ciphertext = ChunkedAesGcmStreamingService.this.encryptChunk(
                    this.key, this.baseNonce, this.chunkIndex, flags, this.associatedDataPrefix, chunk, chunk.length
            );
            this.current = ByteBuffer.allocate(1 + Integer.BYTES + ciphertext.length)
                    .put(flags).putInt(ciphertext.length).put(ciphertext).array();
            this.position = 0;
            this.chunkIndex++;
            this.finalEmitted = flags == FLAG_FINAL;
            return true;
        }
    }

    /**
     * Decryptor: reads the nonce base and the first chunk eagerly (so blatant tampering surfaces
     * as {@link #decryptingInputStream}'s declared {@link AuthenticationFailedException}), then
     * verifies each further chunk in full before serving any of its plaintext. Fails closed: EOF
     * anywhere before a verified {@link #FLAG_FINAL} chunk - or any byte after it - rejects the
     * stream.
     */
    private final class DecryptingInputStream extends InputStream {

        /** The ciphertext being decrypted. */
        private final DataInputStream source;
        /** The key every chunk is verified/decrypted under. */
        private final SecretKey key;
        /** The associated-data prefix every chunk must have been bound to. */
        private final byte[] associatedDataPrefix;
        /** The stream's nonce base, read from {@link #source} up front. */
        private final byte[] baseNonce;
        /** The verified plaintext of the chunk currently being served. */
        private byte[] current;
        /** Read position within {@link #current}. */
        private int position;
        /** Index of the next chunk to read. */
        private long chunkIndex;
        /** Whether the verified {@link #FLAG_FINAL} chunk has been consumed. */
        private boolean finalSeen;

        private DecryptingInputStream(final InputStream source, final SecretKey key, final byte[] associatedDataPrefix)
                throws AuthenticationFailedException, IOException {
            this.source = new DataInputStream(source);
            this.key = key;
            this.associatedDataPrefix = associatedDataPrefix;
            this.baseNonce = new byte[ChunkedAesGcmStreamingService.this.baseNonceLengthBytes()];
            try {
                this.source.readFully(this.baseNonce);
            } catch (final EOFException e) {
                throw truncated("stream ended inside the nonce base", e);
            }
            this.loadNextChunk();
        }

        /** See {@link InputStream#read()}. */
        @Override
        public int read() throws IOException {
            final byte[] single = new byte[1];
            final int read = this.read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xFF;
        }

        /**
         * See {@link InputStream#read(byte[], int, int)}. A chunk that fails verification aborts
         * the read with an {@link IOException} caused by {@link AuthenticationFailedException} -
         * see {@link StreamingAeadEncryptionService}'s exception contract.
         */
        @Override
        public int read(final byte[] into, final int offset, final int length) throws IOException {
            Asserts.requireNonNull(into, "@ChunkedAesGcmStreamingService$DecryptingInputStream.read: into cannot be null");
            if (length == 0) {
                return 0;
            }
            while (this.position == this.current.length) {
                if (this.finalSeen) {
                    return -1;
                }
                try {
                    this.loadNextChunk();
                } catch (final AuthenticationFailedException e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
            final int served = Math.min(length, this.current.length - this.position);
            System.arraycopy(this.current, this.position, into, offset, served);
            this.position += served;
            return served;
        }

        /** Closes the ciphertext source. */
        @Override
        public void close() throws IOException {
            this.source.close();
        }

        /** Reads, verifies, and decrypts the next chunk frame into {@link #current}. */
        private void loadNextChunk() throws AuthenticationFailedException, IOException {
            final int flagsRead = this.source.read();
            if (flagsRead == -1) {
                throw truncated("stream ended before a final chunk was seen", null);
            }
            final byte flags = (byte) flagsRead;
            if (flags != FLAG_FINAL && flags != FLAG_NOT_FINAL) {
                throw truncated("unknown chunk flags value " + flagsRead, null);
            }
            final int ciphertextLength;
            try {
                ciphertextLength = this.source.readInt();
            } catch (final EOFException e) {
                throw truncated("stream ended inside chunk " + this.chunkIndex + "'s length prefix", e);
            }
            final int tagLengthBytes = ChunkedAesGcmStreamingService.this.tagLengthBytes();
            if (ciphertextLength < tagLengthBytes || ciphertextLength > MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES) {
                throw truncated("implausible chunk ciphertext length " + ciphertextLength, null);
            }
            final byte[] ciphertext = new byte[ciphertextLength];
            try {
                this.source.readFully(ciphertext);
            } catch (final EOFException e) {
                throw truncated("stream ended inside chunk " + this.chunkIndex + "'s ciphertext", e);
            }
            this.current = ChunkedAesGcmStreamingService.this.decryptChunk(
                    this.key, this.baseNonce, this.chunkIndex, flags, this.associatedDataPrefix, ciphertext
            );
            this.position = 0;
            this.chunkIndex++;
            if (flags == FLAG_FINAL) {
                this.finalSeen = true;
                if (this.source.read() != -1) {
                    throw truncated("trailing data after the final chunk", null);
                }
            }
        }

        /** Builds the fail-closed rejection for a truncated/malformed stream. */
        private AuthenticationFailedException truncated(final String reason, final Throwable cause) {
            return new AuthenticationFailedException(
                    "@ChunkedAesGcmStreamingService$DecryptingInputStream: " + reason + " - stream rejected", cause
            );
        }
    }
}
