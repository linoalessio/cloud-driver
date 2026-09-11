package de.lino.cloud.api.security.crypto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Authenticated encryption (AEAD) for payloads too large to hold in memory as one {@code byte[]}
 * - the streaming sibling of {@link AeadEncryptionService}, which stays the right choice for
 * small payloads (entity JSON, a wrapped key) that fit comfortably in the heap.
 *
 * <p>An implementation splits the plaintext into fixed-size chunks and encrypts each chunk as its
 * own independent AEAD operation (the STREAM construction, as used by libsodium's {@code
 * crypto_secretstream}, age, and Tink's {@code StreamingAead}), so memory use is O(chunk size)
 * regardless of payload size. Beyond per-chunk confidentiality/integrity, an implementation must
 * defend the <em>sequence</em> itself:
 *
 * <ul>
 *     <li><b>No nonce reuse:</b> each chunk's nonce is derived from a per-stream random base plus
 *     a monotonic chunk counter - never repeated under the same key.</li>
 *     <li><b>No reordering/substitution:</b> the chunk's index (and the caller's {@code
 *     associatedDataPrefix}) is bound into each chunk's associated data, so a chunk cannot be
 *     moved, duplicated, or swapped in from another stream.</li>
 *     <li><b>No truncation:</b> the last chunk is explicitly flagged as final (also bound into its
 *     associated data), and decryption fails closed if the stream ends before a final chunk was
 *     seen - a shortened ciphertext never decrypts to a shorter, "valid" plaintext.</li>
 * </ul>
 *
 * <p><b>Exception contract for the returned streams:</b> {@link java.io.InputStream#read()}/{@link
 * java.io.OutputStream#write(int)} can only throw {@link IOException}, so an authentication
 * failure detected mid-stream surfaces as an {@link IOException} whose {@link
 * Throwable#getCause() cause} is an {@link AuthenticationFailedException} - callers draining a
 * decrypting stream should unwrap that cause. A failure detectable eagerly (the very first chunk)
 * is thrown directly from {@link #decryptingInputStream} as the declared checked exception.
 */
public interface StreamingAeadEncryptionService {

    /**
     * Wraps {@code sink} so that plaintext written to the returned stream is chunk-encrypted under
     * {@code key} and the resulting ciphertext (stream header plus chunk frames) is written
     * through to {@code sink}. Closing the returned stream encrypts and writes the final chunk
     * (possibly empty) and then closes {@code sink} - a stream abandoned without {@code close()}
     * produces a truncated ciphertext that will (by design) fail decryption.
     *
     * @param sink where the ciphertext is written
     * @param key the key to encrypt with
     * @param associatedDataPrefix caller data (e.g. a protocol tag and record id) bound into every
     *                             chunk's associated data; may be {@code null} or empty
     * @return the plaintext-accepting stream; total ciphertext written equals
     *     {@link #encryptedLength(long)} of the total plaintext written
     */
    @NotNull
    OutputStream encryptingOutputStream(@NotNull OutputStream sink, @NotNull SecretKey key, @Nullable byte[] associatedDataPrefix);

    /**
     * Pull-based counterpart of {@link #encryptingOutputStream}: wraps {@code plaintext} so that
     * reading the returned stream yields the ciphertext (stream header plus chunk frames) of the
     * bytes read from {@code plaintext}, encrypted under {@code key}. Composes directly with
     * consumers that pull an {@link InputStream}, e.g. {@code
     * ObjectStorageService#putObject(String, InputStream, long)}. Closing the returned stream
     * closes {@code plaintext}.
     *
     * @param plaintext the plaintext to encrypt, read to EOF as the returned stream is drained
     * @param key the key to encrypt with
     * @param associatedDataPrefix caller data bound into every chunk's associated data; may be
     *                             {@code null} or empty
     * @return the ciphertext stream; its total length equals {@link #encryptedLength(long)} of
     *     {@code plaintext}'s total length
     */
    @NotNull
    InputStream encryptingInputStream(@NotNull InputStream plaintext, @NotNull SecretKey key, @Nullable byte[] associatedDataPrefix);

    /**
     * Wraps {@code source} (a ciphertext as produced by either encrypting stream) so that reading
     * the returned stream yields the verified plaintext. Each chunk's authentication tag is
     * verified before any of that chunk's plaintext is served; the first chunk is read and
     * verified eagerly by this call itself. Closing the returned stream closes {@code source}.
     *
     * @param source the ciphertext to decrypt
     * @param key the key to decrypt with
     * @param associatedDataPrefix the same prefix the encrypting side bound in - a mismatch fails
     *                             authentication; may be {@code null} or empty
     * @return the plaintext stream - later chunks' authentication failures surface from its
     *     {@code read} calls as {@link IOException}s caused by {@link AuthenticationFailedException}
     * @throws AuthenticationFailedException if the first chunk fails verification, or the stream
     *     is truncated/malformed before it
     * @throws IOException if reading {@code source} fails
     */
    @NotNull
    InputStream decryptingInputStream(@NotNull InputStream source, @NotNull SecretKey key, @Nullable byte[] associatedDataPrefix)
            throws AuthenticationFailedException, IOException;

    /**
     * The exact ciphertext length (stream header plus all chunk frames) this service produces for
     * a plaintext of {@code plaintextLength} bytes - deterministic, so a caller can promise an
     * exact {@code Content-Length} to a downstream consumer before encryption has run.
     *
     * @param plaintextLength the plaintext's length, in bytes
     * @return the resulting ciphertext's length, in bytes
     * @throws IllegalArgumentException if {@code plaintextLength} is negative
     */
    long encryptedLength(long plaintextLength);
}
