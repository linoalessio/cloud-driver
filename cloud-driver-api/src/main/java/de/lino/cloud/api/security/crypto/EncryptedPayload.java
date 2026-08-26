package de.lino.cloud.api.security.crypto;

import de.lino.cloud.api.utility.Asserts;

/**
 * The result of an {@link AeadEncryptionService} encryption operation: the
 * algorithm used (see {@link CryptoAlgorithm#id()}), the per-operation
 * nonce/IV, the ciphertext (with the GCM authentication tag appended, as
 * produced by {@code javax.crypto.Cipher}), and the associated authenticated
 * data (AAD, if any) that was bound into the authentication tag.
 *
 * <p>Storing the algorithm identifier with the payload (section 10, POST-QUANTUM
 * READINESS) allows the algorithm to be rotated or migrated later without
 * breaking the ability to decrypt older data.
 *
 * <p>Array components are defensively copied on construction and on every
 * accessor call so that neither the caller nor this record can mutate shared
 * state after the fact.
 */
public record EncryptedPayload(String algorithmId, byte[] nonce, byte[] ciphertext, byte[] associatedData) {

    public EncryptedPayload {
        Asserts.requireNonNull(algorithmId, "@EncryptedPayload: algorithmId cannot be null");
        Asserts.requireNonNull(nonce, "@EncryptedPayload: nonce cannot be null");
        Asserts.requireNonNull(ciphertext, "@EncryptedPayload: ciphertext cannot be null");

        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
        associatedData = associatedData == null ? new byte[0] : associatedData.clone();
    }

    /**
     * A defensive copy of the nonce/IV, so neither the caller nor this record can mutate shared state after the fact.
     */
    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    /**
     * A defensive copy of the ciphertext, so neither the caller nor this record can mutate shared state after the fact.
     */
    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /**
     * A defensive copy of the associated authenticated data, so neither the caller nor this record can mutate shared state after the fact.
     */
    @Override
    public byte[] associatedData() {
        return associatedData.clone();
    }
}
