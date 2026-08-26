package de.lino.cloud.api.security.keys;

import de.lino.cloud.api.utility.Asserts;

/**
 * A {@link DataEncryptionKey} after being wrapped (encrypted) by a
 * key-encryption key (KEK) held in a {@link KeyEncryptionService}. Records
 * which KEK version wrapped it ({@link #keyEncryptionKeyId()}) so the correct
 * KEK can be located again for unwrapping even after {@link
 * KeyEncryptionService#rotate() rotation}, and which algorithm the unwrapped
 * DEK itself uses ({@link #dataEncryptionKeyAlgorithmId()}) for crypto
 * agility.
 */
public record WrappedKey(String keyEncryptionKeyId, byte[] wrappedKeyMaterial, String wrapAlgorithm,
                          String dataEncryptionKeyAlgorithmId) {

    public WrappedKey {
        Asserts.requireNonNull(keyEncryptionKeyId, "@WrappedKey: keyEncryptionKeyId cannot be null");
        Asserts.requireNonNull(wrappedKeyMaterial, "@WrappedKey: wrappedKeyMaterial cannot be null");
        Asserts.requireNonNull(wrapAlgorithm, "@WrappedKey: wrapAlgorithm cannot be null");
        Asserts.requireNonNull(dataEncryptionKeyAlgorithmId, "@WrappedKey: dataEncryptionKeyAlgorithmId cannot be null");

        wrappedKeyMaterial = wrappedKeyMaterial.clone();
    }

    /**
     * A defensive copy of the wrapped key material, so neither the caller
     * nor this record can mutate shared state after the fact.
     */
    @Override
    public byte[] wrappedKeyMaterial() {
        return wrappedKeyMaterial.clone();
    }
}
