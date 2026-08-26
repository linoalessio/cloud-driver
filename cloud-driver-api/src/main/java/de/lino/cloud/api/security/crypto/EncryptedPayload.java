package de.lino.cloud.api.security.crypto;

import de.lino.cloud.api.utility.Asserts;

/**
 * The result of an {@link AeadEncryptionService} encryption operation: the
 * algorithm used, the per-operation nonce/IV, the ciphertext (with the GCM
 * authentication tag appended), and any associated authenticated data (AAD).
 * Array components are defensively copied on construction and on every
 * accessor call.
 *
 * @param algorithmId identifier of the {@link CryptoAlgorithm} used
 * @param nonce the per-operation nonce/IV
 * @param ciphertext the ciphertext, with the GCM authentication tag appended
 * @param associatedData additional data authenticated but not encrypted
 */
public record EncryptedPayload(String algorithmId, byte[] nonce, byte[] ciphertext, byte[] associatedData) {

    /**
     * @throws NullPointerException if {@code algorithmId}, {@code nonce}, or {@code ciphertext} is {@code null}
     */
    public EncryptedPayload {
        Asserts.requireNonNull(algorithmId, "@EncryptedPayload: algorithmId cannot be null");
        Asserts.requireNonNull(nonce, "@EncryptedPayload: nonce cannot be null");
        Asserts.requireNonNull(ciphertext, "@EncryptedPayload: ciphertext cannot be null");

        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
        associatedData = associatedData == null ? new byte[0] : associatedData.clone();
    }

    /** @return a defensive copy of the nonce/IV */
    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    /** @return a defensive copy of the ciphertext */
    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /** @return a defensive copy of the associated authenticated data */
    @Override
    public byte[] associatedData() {
        return associatedData.clone();
    }
}
