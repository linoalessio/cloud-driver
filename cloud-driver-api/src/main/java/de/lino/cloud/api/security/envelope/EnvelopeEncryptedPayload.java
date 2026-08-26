package de.lino.cloud.api.security.envelope;

import de.lino.cloud.api.security.crypto.EncryptedPayload;
import de.lino.cloud.api.security.keys.WrappedKey;

import de.lino.cloud.api.utility.Asserts;

/**
 * The result of an {@code EnvelopeEncryptionService} (in {@code cloud-driver-plugin})
 * encryption operation: the AES-GCM encrypted payload together with its
 * wrapped data-encryption key (KEK -&gt; wrapped DEK -&gt; encrypted payload).
 *
 * @param schemaVersion envelope format version, allowing the format to evolve later
 * @param wrappedDataEncryptionKey the DEK, wrapped under the active KEK
 * @param payload the AES-GCM encrypted payload
 */
public record EnvelopeEncryptedPayload(int schemaVersion, WrappedKey wrappedDataEncryptionKey, EncryptedPayload payload) {

    /**
     * @throws NullPointerException if {@code wrappedDataEncryptionKey} or {@code payload} is {@code null}
     */
    public EnvelopeEncryptedPayload {
        Asserts.requireNonNull(wrappedDataEncryptionKey, "@EnvelopeEncryptedPayload: wrappedDataEncryptionKey cannot be null");
        Asserts.requireNonNull(payload, "@EnvelopeEncryptedPayload: payload cannot be null");
    }
}
