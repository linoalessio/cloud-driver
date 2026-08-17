package de.lino.cloud.api.security.envelope;

import de.lino.cloud.api.security.crypto.EncryptedPayload;
import de.lino.cloud.api.security.keys.WrappedKey;

import java.util.Objects;

/**
 * The result of an {@link EnvelopeEncryptionService} encryption operation:
 * the AES-GCM encrypted payload together with its wrapped data-encryption
 * key, forming the key hierarchy described in section 13 (KMS/HSM -&gt; KEK
 * -&gt; wrapped DEK -&gt; AES-256-GCM encrypted payload).
 *
 * <p>{@link #schemaVersion()} is carried alongside the cryptographic
 * algorithm identifiers already present on {@link EncryptedPayload} and
 * {@link WrappedKey}, so the on-the-wire/on-disk envelope format itself can
 * evolve - e.g. to add post-quantum key-encapsulation metadata - without
 * breaking the ability to read older envelopes.
 */
public record EnvelopeEncryptedPayload(int schemaVersion, WrappedKey wrappedDataEncryptionKey, EncryptedPayload payload) {

    public EnvelopeEncryptedPayload {
        Objects.requireNonNull(wrappedDataEncryptionKey, "@EnvelopeEncryptedPayload: wrappedDataEncryptionKey cannot be null");
        Objects.requireNonNull(payload, "@EnvelopeEncryptedPayload: payload cannot be null");
    }
}
