package de.lino.cloud.api.s3storage;

import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

/**
 * Issues and recovers per-file content-encryption keys for presigned, direct-to-client transfers
 * - the piece that removes {@link PresignedTransferService}'s former "content is only protected
 * by the object store's own server-side encryption" deviation. The server issues a fresh
 * data-encryption key (wrapped under the {@link KeyEncryptionService}-held KEK exactly like every
 * server-encrypted payload), hands the raw key transiently to the authenticated client over TLS,
 * and the client encrypts/decrypts the content itself with the same chunked-AEAD scheme ({@code
 * StreamingAeadEncryptionService}) the server uses - so a presigned-path object on disk is
 * byte-identical in layout to a server-encrypted one, and the app-controlled DEK/KEK guarantee
 * holds uniformly across every upload path.
 *
 * <p>Same "contract in {@code cloud-driver-api}, production implementation in {@code
 * cloud-driver-plugin}" shape as {@link PresignedTransferService}, and deliberately a separate
 * interface from it: presigning moves no key material, and key issuance moves no bytes.
 *
 * <p><b>The client-side protocol an issued key implies:</b> the client writes {@link
 * IssuedContentKey#header()} verbatim at offset 0 of the uploaded object, then chunk frames
 * encrypted under {@link IssuedContentKey#keyMaterial()} with AES-256-GCM: per-chunk nonce =
 * a fresh random base ({@code nonce length - 8} bytes, written once after the header) followed by
 * the big-endian 8-byte chunk counter; per-chunk associated data = {@link
 * #associatedDataPrefix(String)} (UTF-8) followed by the big-endian 8-byte chunk counter and a
 * 1-byte final-chunk flag; each frame is {@code [1 flag byte][4-byte ciphertext length][ciphertext
 * + 16-byte tag]}, and the stream always ends with a (possibly empty) final-flagged chunk. See
 * {@code ChunkedAesGcmStreamingService}'s Javadoc for the authoritative layout.
 */
public interface ContentKeyService {

    /**
     * A freshly issued content key: the raw key material the client encrypts with (handed over
     * the authenticated channel, never persisted anywhere), and the serialized streaming header
     * (schema version, KEK-wrapped copy of the same key, algorithm ids) the client writes
     * verbatim at the start of the uploaded object - the same header a server-side {@code
     * EnvelopeEncryptionService#encryptStream} call would have written.
     *
     * @param header the streaming header to write at offset 0 of the object, byte-for-byte
     * @param keyMaterial the raw content-encryption key
     */
    record IssuedContentKey(byte[] header, byte[] keyMaterial) {

        /**
         * @throws NullPointerException if {@code header} or {@code keyMaterial} is {@code null}
         */
        public IssuedContentKey {
            Asserts.requireNonNull(header, "@IssuedContentKey: header cannot be null");
            Asserts.requireNonNull(keyMaterial, "@IssuedContentKey: keyMaterial cannot be null");
            header = header.clone();
            keyMaterial = keyMaterial.clone();
        }

        /** @return a defensive copy of the streaming header */
        @Override
        public byte[] header() {
            return header.clone();
        }

        /** @return a defensive copy of the raw key material */
        @Override
        public byte[] keyMaterial() {
            return keyMaterial.clone();
        }
    }

    /**
     * Issues a fresh content-encryption key: generates it, wraps it under the active KEK, and
     * returns both the raw material (for the client) and the streaming header carrying the
     * wrapped copy (for the object). This process holds the raw material only transiently -
     * durable recovery goes through {@link #recoverContentKey(byte[])} against the header.
     *
     * @return the issued key
     * @throws KeyWrapException if wrapping the freshly generated key fails
     */
    @NotNull
    IssuedContentKey issueContentKey() throws KeyWrapException;

    /**
     * Recovers the raw content-encryption key from a previously issued {@link
     * IssuedContentKey#header()} (as persisted alongside the file's metadata), by unwrapping the
     * header's wrapped key via the KMS/HSM - the download-side counterpart of {@link
     * #issueContentKey()}.
     *
     * @param header the streaming header, exactly as issued
     * @return the raw key material - the caller hands it to the downloading client and drops it
     * @throws KeyWrapException if unwrapping the header's key fails
     * @throws AuthenticationFailedException if {@code header} is truncated or malformed
     */
    byte[] recoverContentKey(@NotNull byte[] header) throws KeyWrapException, AuthenticationFailedException;

    /**
     * The associated-data prefix the client must bind into every chunk for {@code fileId}'s
     * content - identical to what the server-side content channel binds, so either side can
     * decrypt the other's objects.
     *
     * @param fileId the file the content belongs to
     * @return the prefix, as a UTF-8-encodable string
     */
    @NotNull
    String associatedDataPrefix(@NotNull String fileId);

    /**
     * The chunk size, in plaintext bytes, the client must encrypt with - fixed per deployment so
     * {@link #objectLength(int, long)} is exact.
     */
    int chunkSizeBytes();

    /**
     * The exact total stored-object length (header plus nonce base plus all chunk frames) a
     * plaintext of {@code plaintextLength} bytes produces under {@link #chunkSizeBytes()} - what
     * the object store must report for a completed upload, letting the server verify a client's
     * upload without ever fetching it.
     *
     * @param headerLengthBytes the issued {@link IssuedContentKey#header()}'s length
     * @param plaintextLength the plaintext's length, in bytes
     * @return the stored object's exact total length, in bytes
     * @throws IllegalArgumentException if {@code headerLengthBytes} or {@code plaintextLength} is negative
     */
    long objectLength(int headerLengthBytes, long plaintextLength);
}
