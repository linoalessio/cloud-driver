package de.lino.cloud.api.security.database;

import de.lino.cloud.api.security.crypto.EncryptedPayload;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.WrappedKey;

import java.util.Base64;
import de.lino.cloud.api.utility.Asserts;

/**
 * Storage format for an {@link EnvelopeEncryptedPayload}: every binary field
 * is base64-encoded so the envelope can be stored as a plain JSON document.
 *
 * @param schemaVersion envelope format version
 * @param algorithm identifier of the {@link EncryptedPayload}'s algorithm
 * @param nonce base64-encoded nonce/IV
 * @param ciphertext base64-encoded ciphertext
 * @param associatedData base64-encoded associated authenticated data
 * @param keyEncryptionKeyId identifier of the KEK version that wrapped the DEK
 * @param wrapAlgorithm algorithm used to wrap the DEK
 * @param dataEncryptionKeyAlgorithmId identifier of the unwrapped DEK's algorithm
 * @param wrappedDataEncryptionKey base64-encoded wrapped DEK material
 */
public record EncryptedEntityRecord(
        int schemaVersion,
        String algorithm,
        String nonce,
        String ciphertext,
        String associatedData,
        String keyEncryptionKeyId,
        String wrapAlgorithm,
        String dataEncryptionKeyAlgorithmId,
        String wrappedDataEncryptionKey
) {

    /**
     * Converts {@code envelope} into its base64-encoded storage representation.
     *
     * @param envelope the envelope to convert
     * @return the resulting storage record
     * @throws NullPointerException if {@code envelope} is {@code null}
     */
    public static EncryptedEntityRecord from(final EnvelopeEncryptedPayload envelope) {
        Asserts.requireNonNull(envelope, "@EncryptedEntityRecord.from: envelope cannot be null");

        final Base64.Encoder base64 = Base64.getEncoder();
        final EncryptedPayload payload = envelope.payload();
        final WrappedKey wrappedKey = envelope.wrappedDataEncryptionKey();

        return new EncryptedEntityRecord(
                envelope.schemaVersion(),
                payload.algorithmId(),
                base64.encodeToString(payload.nonce()),
                base64.encodeToString(payload.ciphertext()),
                base64.encodeToString(payload.associatedData()),
                wrappedKey.keyEncryptionKeyId(),
                wrappedKey.wrapAlgorithm(),
                wrappedKey.dataEncryptionKeyAlgorithmId(),
                base64.encodeToString(wrappedKey.wrappedKeyMaterial())
        );
    }

    /**
     * Reverses {@link #from(EnvelopeEncryptedPayload)}, base64-decoding this
     * record's fields back into an {@link EnvelopeEncryptedPayload}.
     *
     * @return the decoded envelope
     */
    public EnvelopeEncryptedPayload toEnvelope() {
        final Base64.Decoder base64 = Base64.getDecoder();

        final EncryptedPayload payload = new EncryptedPayload(
                algorithm, base64.decode(nonce), base64.decode(ciphertext), base64.decode(associatedData)
        );
        final WrappedKey wrappedKey = new WrappedKey(
                keyEncryptionKeyId, base64.decode(wrappedDataEncryptionKey), wrapAlgorithm, dataEncryptionKeyAlgorithmId
        );
        return new EnvelopeEncryptedPayload(schemaVersion, wrappedKey, payload);
    }
}
