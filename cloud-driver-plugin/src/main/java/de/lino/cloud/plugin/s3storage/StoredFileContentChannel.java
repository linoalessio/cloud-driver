package de.lino.cloud.plugin.s3storage;

import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encrypts/decrypts a {@link de.lino.cloud.api.file.StoredFile}'s raw content bytes for s3storage in
 * an {@link ObjectStorageService} object, generically across
 * whichever implementation is configured - the {@code s3storage.object} package's equivalent of
 * {@code de.lino.cloud.plugin.security.entity.SecureEntityChannel}, adapted for a file's content
 * bytes specifically rather than a whole {@code Serialized} entity's JSON.
 *
 * <p>Binds this file's id into the authenticated associated data (AAD), the same way {@code
 * SecureEntityChannel} binds an entity's type name and primary key - {@link #receive} rejects a
 * payload whose AAD doesn't match the {@code fileId} it was asked to decrypt for, so an object
 * fetched under one file's key can't be silently substituted for another's.
 *
 * <p><b>Deliberately independent of {@code SecureEntityChannel}</b> - see {@link
 * EnvelopeEncryptedPayloadCodec}'s own Javadoc for why: {@code SecureEntityChannel} only ever
 * encrypts a whole entity's serialized JSON, and there is no ciphertext of just a file's content
 * bytes to reuse from that path. This class calls the very same {@link EnvelopeEncryptionService}
 * instance directly instead - same AES-256-GCM/DEK-KEK scheme, same KMS/HSM-backed {@code
 * KeyEncryptionService}, just invoked a second time on a narrower input.
 *
 * <p><b>Two stored layouts coexist.</b> {@link #send(String, byte[])} produces the original
 * one-shot layout (schema version 1, {@link EnvelopeEncryptedPayloadCodec}); {@link #sendStream}
 * produces the chunked streaming layout ({@link EnvelopeEncryptionService#STREAMING_SCHEMA_VERSION},
 * whose memory use is O(chunk size) rather than O(file size)). Both start with a 4-byte
 * schema-version tag, and every receive method dispatches on it - so objects written before the
 * streaming layout existed stay readable forever, and either receive path accepts either layout.
 */
public final class StoredFileContentChannel {

    /** Prefix tag baked into every one-shot (schema version 1) associated-data value, mirroring {@code SecureEntityChannel}'s own {@code PROTOCOL_VERSION} convention. */
    private static final String PROTOCOL_VERSION = "s3-content-v1";

    /** Prefix tag bound into every chunk of a streaming (schema version 2) object's associated data. */
    private static final String STREAMING_PROTOCOL_VERSION = "s3-content-v2";

    /** Performs the actual envelope encryption/decryption {@link #send}/{@link #receive} wrap. */
    private final EnvelopeEncryptionService envelopeEncryptionService;

    /**
     * @param envelopeEncryptionService the envelope-encryption service backing {@link #send}/{@link #receive}
     * @throws NullPointerException if {@code envelopeEncryptionService} is {@code null}
     */
    public StoredFileContentChannel(@NotNull final EnvelopeEncryptionService envelopeEncryptionService) {
        this.envelopeEncryptionService = Asserts.requireNonNull(
                envelopeEncryptionService, "@StoredFileContentChannel: envelopeEncryptionService cannot be null"
        );
    }

    /**
     * Envelope-encrypts {@code rawBytes} (a file's {@link de.lino.cloud.api.file.StoredFile#rawStorableBytes()}
     * - DEFLATE-compressed if applicable, not yet encrypted) and serializes the result into bytes
     * ready to hand to {@code ObjectStorageService#putObject}.
     *
     * @param fileId the id of the file {@code rawBytes} belongs to, bound into the AAD
     * @param rawBytes the not-yet-encrypted, compressed-if-applicable content bytes
     * @return the serialized, encrypted bytes to store
     * @throws NullPointerException if {@code fileId} or {@code rawBytes} is {@code null}
     * @throws KeyWrapException if wrapping the freshly generated data-encryption key fails
     */
    @NotNull
    public byte[] send(@NotNull final String fileId, @NotNull final byte[] rawBytes) throws KeyWrapException {
        Asserts.requireNonNull(fileId, "@StoredFileContentChannel.send: fileId cannot be null");
        Asserts.requireNonNull(rawBytes, "@StoredFileContentChannel.send: rawBytes cannot be null");

        final EnvelopeEncryptedPayload envelope = envelopeEncryptionService.encrypt(rawBytes, associatedData(fileId));
        return EnvelopeEncryptedPayloadCodec.serialize(envelope);
    }

    /**
     * Reverses {@link #send}: deserializes {@code storedBytes} (as read back from {@code
     * ObjectStorageService#getObject}), rejects a mismatched {@code fileId}, and decrypts the
     * result. Dispatches on the leading schema-version tag, so it accepts a streaming-layout
     * object (as written by {@link #sendStream}) as well as the one-shot layout.
     *
     * @param fileId the id of the file {@code storedBytes} is expected to belong to
     * @param storedBytes the serialized, encrypted bytes read back from object s3storage
     * @return the recovered raw (compressed-if-applicable, not-yet-decompressed) content bytes -
     *     see {@link de.lino.cloud.api.file.StoredFile#decompressIfNeeded(byte[])} for the remaining step
     * @throws NullPointerException if {@code fileId} or {@code storedBytes} is {@code null}
     * @throws ObjectStorageException if {@code storedBytes} is malformed or belongs to a different file
     * @throws KeyWrapException if unwrapping the envelope's data-encryption key fails
     * @throws AuthenticationFailedException if authentication tag verification fails
     */
    @NotNull
    public byte[] receive(@NotNull final String fileId, @NotNull final byte[] storedBytes)
            throws KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(fileId, "@StoredFileContentChannel.receive: fileId cannot be null");
        Asserts.requireNonNull(storedBytes, "@StoredFileContentChannel.receive: storedBytes cannot be null");

        if (isStreamingLayout(storedBytes)) {
            return this.receiveFully(fileId, new ByteArrayInputStream(storedBytes));
        }

        final EnvelopeEncryptedPayload envelope = EnvelopeEncryptedPayloadCodec.deserialize(storedBytes);

        final String associatedData = new String(envelope.payload().associatedData(), StandardCharsets.UTF_8);
        final String expectedPrefix = PROTOCOL_VERSION + ":" + fileId;
        if (!associatedData.equals(expectedPrefix)) {
            throw new ObjectStorageException(
                    "@StoredFileContentChannel.receive: expected content for file '" + fileId
                            + "' but the stored object's associated data was '" + associatedData + "'"
            );
        }

        return envelopeEncryptionService.decrypt(envelope);
    }

    /**
     * A {@link #sendStream} result, ready to hand to {@code
     * ObjectStorageService#putObject(String, InputStream, long)} as-is: the encrypted content
     * stream and the exact number of bytes it will yield.
     *
     * @param content serves the encrypted streaming-layout object; closing it closes the raw-content source
     * @param contentLength the exact number of bytes {@code content} will yield
     */
    public record StreamingPayload(@NotNull InputStream content, long contentLength) {
    }

    /**
     * Streaming counterpart of {@link #send(String, byte[])}: envelope-encrypts {@code
     * rawContent} (a file's raw storable bytes - DEFLATE-compressed if applicable, not yet
     * encrypted) chunk by chunk via {@link EnvelopeEncryptionService#encryptStream}, binding
     * {@code fileId} into every chunk's associated data. Neither the plaintext nor the ciphertext
     * is ever materialized as one array - memory use is O(chunk size) regardless of file size.
     *
     * <p>Nothing is read from {@code rawContent} until the returned stream is drained; the only
     * eager work is generating and wrapping the data-encryption key.
     *
     * @param fileId the id of the file {@code rawContent} belongs to, bound into every chunk's associated data
     * @param rawContent the not-yet-encrypted, compressed-if-applicable content bytes
     * @param rawContentLength {@code rawContent}'s exact total length, in bytes
     * @return the encrypted content stream and its exact total length
     * @throws NullPointerException if {@code fileId} or {@code rawContent} is {@code null}
     * @throws IllegalArgumentException if {@code rawContentLength} is negative
     * @throws KeyWrapException if wrapping the freshly generated data-encryption key fails
     */
    @NotNull
    public StreamingPayload sendStream(@NotNull final String fileId, @NotNull final InputStream rawContent,
                                       final long rawContentLength) throws KeyWrapException {
        Asserts.requireNonNull(fileId, "@StoredFileContentChannel.sendStream: fileId cannot be null");
        Asserts.requireNonNull(rawContent, "@StoredFileContentChannel.sendStream: rawContent cannot be null");

        final EnvelopeEncryptionService.StreamingEncryption encryption = this.envelopeEncryptionService.encryptStream(
                rawContent, rawContentLength, streamingAssociatedDataPrefix(fileId)
        );
        return new StreamingPayload(encryption.ciphertextStream(), encryption.ciphertextLength());
    }

    /**
     * Streaming counterpart of {@link #receive(String, byte[])}: wraps {@code storedContent} (as
     * read back from {@code ObjectStorageService#getObjectStream}) so that reading the returned
     * stream yields the verified raw content bytes. Dispatches on the leading schema-version tag:
     * a streaming-layout object decrypts chunk by chunk (O(chunk size) memory); a one-shot-layout
     * object is buffered and handed to {@link #receive(String, byte[])}, since that layout cannot
     * be decrypted incrementally.
     *
     * @param fileId the id of the file {@code storedContent} is expected to belong to
     * @param storedContent the serialized, encrypted bytes read back from object s3storage
     * @return the raw-content stream - closing it closes {@code storedContent}; a later chunk's
     *     authentication failure surfaces from its {@code read} calls as an {@link IOException}
     *     caused by {@link AuthenticationFailedException} (see {@link
     *     de.lino.cloud.api.security.crypto.StreamingAeadEncryptionService}'s exception contract,
     *     or use {@link #receiveFully} to have that unwrapped)
     * @throws NullPointerException if {@code fileId} or {@code storedContent} is {@code null}
     * @throws ObjectStorageException if {@code storedContent} is malformed or belongs to a different file
     * @throws KeyWrapException if unwrapping the object's data-encryption key fails
     * @throws AuthenticationFailedException if the header or first chunk fails verification
     * @throws IOException if reading {@code storedContent} fails
     */
    @NotNull
    public InputStream receiveStream(@NotNull final String fileId, @NotNull final InputStream storedContent)
            throws KeyWrapException, AuthenticationFailedException, IOException {
        Asserts.requireNonNull(fileId, "@StoredFileContentChannel.receiveStream: fileId cannot be null");
        Asserts.requireNonNull(storedContent, "@StoredFileContentChannel.receiveStream: storedContent cannot be null");

        final PushbackInputStream pushback = new PushbackInputStream(storedContent, Integer.BYTES);
        final byte[] versionTag = new byte[Integer.BYTES];
        final int read = pushback.readNBytes(versionTag, 0, versionTag.length);
        if (read < versionTag.length) {
            throw new ObjectStorageException(
                    "@StoredFileContentChannel.receiveStream: stored object for file '" + fileId
                            + "' is shorter than its schema-version tag", new EOFException()
            );
        }
        pushback.unread(versionTag);

        if (ByteBuffer.wrap(versionTag).getInt() == EnvelopeEncryptionService.STREAMING_SCHEMA_VERSION) {
            return this.envelopeEncryptionService.decryptStream(pushback, streamingAssociatedDataPrefix(fileId));
        }
        // One-shot layout: no incremental decryption exists for it, so fall back to the buffered
        // path - exactly what every object written before the streaming layout requires anyway.
        return new ByteArrayInputStream(this.receive(fileId, pushback.readAllBytes()));
    }

    /**
     * Drains {@link #receiveStream} into a {@code byte[]} - for callers that need the whole raw
     * content in memory anyway (e.g. to decompress it), sparing them the stream plumbing and the
     * {@link IOException}-cause unwrapping the streaming read contract otherwise requires. The
     * ciphertext is still never materialized as one array; only the plaintext is.
     *
     * @param fileId the id of the file {@code storedContent} is expected to belong to
     * @param storedContent the serialized, encrypted bytes read back from object s3storage
     * @return the recovered raw (compressed-if-applicable, not-yet-decompressed) content bytes
     * @throws NullPointerException if {@code fileId} or {@code storedContent} is {@code null}
     * @throws ObjectStorageException if {@code storedContent} is malformed, belongs to a different
     *     file, or reading it fails with a plain I/O error
     * @throws KeyWrapException if unwrapping the object's data-encryption key fails
     * @throws AuthenticationFailedException if authentication verification fails anywhere in the stream
     */
    @NotNull
    public byte[] receiveFully(@NotNull final String fileId, @NotNull final InputStream storedContent)
            throws KeyWrapException, AuthenticationFailedException {
        try (InputStream rawContent = this.receiveStream(fileId, storedContent)) {
            return rawContent.readAllBytes();
        } catch (final IOException e) {
            if (e.getCause() instanceof final AuthenticationFailedException authenticationFailure) {
                throw authenticationFailure;
            }
            throw new ObjectStorageException(
                    "@StoredFileContentChannel.receiveFully: failed reading stored content for file '" + fileId + "'", e
            );
        }
    }

    /**
     * Whether {@code storedBytes} opens with the streaming layout's schema-version tag - see this
     * class's Javadoc on the two coexisting layouts.
     */
    private static boolean isStreamingLayout(final byte[] storedBytes) {
        return storedBytes.length >= Integer.BYTES
                && ByteBuffer.wrap(storedBytes, 0, Integer.BYTES).getInt() == EnvelopeEncryptionService.STREAMING_SCHEMA_VERSION;
    }

    /**
     * Builds the authenticated associated data binding a payload to one file id: {@code
     * "<PROTOCOL_VERSION>:<fileId>"}.
     */
    private static byte[] associatedData(final String fileId) {
        return (PROTOCOL_VERSION + ":" + fileId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the associated-data prefix bound into every chunk of a streaming-layout object:
     * {@code "<STREAMING_PROTOCOL_VERSION>:<fileId>"} - the chunk index and final-chunk flag are
     * appended per chunk by the streaming service itself.
     */
    private static byte[] streamingAssociatedDataPrefix(final String fileId) {
        return streamingAssociatedDataPrefixString(fileId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The associated-data prefix for {@code fileId}'s streaming-layout content, as a string - the
     * exact value {@link #sendStream}/{@link #receiveStream} bind, exposed so a compatible
     * external encryptor (a presigned-upload client, via {@code StreamingContentKeyService}) can
     * bind the very same prefix and produce objects this channel decrypts.
     *
     * @param fileId the id of the file the content belongs to
     * @return the prefix string, {@code "s3-content-v2:<fileId>"}
     * @throws NullPointerException if {@code fileId} is {@code null}
     */
    @NotNull
    public static String streamingAssociatedDataPrefixString(@NotNull final String fileId) {
        Asserts.requireNonNull(fileId, "@StoredFileContentChannel.streamingAssociatedDataPrefixString: fileId cannot be null");
        return STREAMING_PROTOCOL_VERSION + ":" + fileId;
    }
}
