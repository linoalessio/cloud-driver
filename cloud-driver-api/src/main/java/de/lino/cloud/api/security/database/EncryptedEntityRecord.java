package de.lino.cloud.api.security.database;

import de.lino.cloud.api.security.crypto.EncryptedPayload;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.WrappedKey;

import java.util.Base64;
import java.util.Objects;

/**
 * Storage format for an {@link EnvelopeEncryptedPayload}: every binary field
 * is base64-encoded so the envelope can be stored as a plain JSON document
 * under a {@code database-driver-api} {@code DatabaseEntry} (section 9, DATA
 * AT REST - highly sensitive data uses application-level AES-256-GCM
 * encryption in addition to the database's own storage encryption, so the
 * database only ever sees this ciphertext-bearing representation, never
 * plaintext).
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

    public static EncryptedEntityRecord from(final EnvelopeEncryptedPayload envelope) {
        Objects.requireNonNull(envelope, "@EncryptedEntityRecord.from: envelope cannot be null");

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
