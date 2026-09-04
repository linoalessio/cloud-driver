package de.lino.cloud.plugin.storage.object;

import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * Encrypts/decrypts a {@link de.lino.cloud.api.file.StoredFile}'s raw content bytes for storage in
 * an {@link ObjectStorageService} object, generically across
 * whichever implementation is configured - the {@code storage.object} package's equivalent of
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
 */
public final class StoredFileContentChannel {

    /** Prefix tag baked into every associated-data value, mirroring {@code SecureEntityChannel}'s own {@code PROTOCOL_VERSION} convention. */
    private static final String PROTOCOL_VERSION = "s3-content-v1";

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
     * result.
     *
     * @param fileId the id of the file {@code storedBytes} is expected to belong to
     * @param storedBytes the serialized, encrypted bytes read back from object storage
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
     * Builds the authenticated associated data binding a payload to one file id: {@code
     * "<PROTOCOL_VERSION>:<fileId>"}.
     */
    private static byte[] associatedData(final String fileId) {
        return (PROTOCOL_VERSION + ":" + fileId).getBytes(StandardCharsets.UTF_8);
    }
}
