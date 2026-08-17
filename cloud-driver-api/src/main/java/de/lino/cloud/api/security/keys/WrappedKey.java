package de.lino.cloud.api.security.keys;

import java.util.Objects;

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
        Objects.requireNonNull(keyEncryptionKeyId, "@WrappedKey: keyEncryptionKeyId cannot be null");
        Objects.requireNonNull(wrappedKeyMaterial, "@WrappedKey: wrappedKeyMaterial cannot be null");
        Objects.requireNonNull(wrapAlgorithm, "@WrappedKey: wrapAlgorithm cannot be null");
        Objects.requireNonNull(dataEncryptionKeyAlgorithmId, "@WrappedKey: dataEncryptionKeyAlgorithmId cannot be null");

        wrappedKeyMaterial = wrappedKeyMaterial.clone();
    }

    @Override
    public byte[] wrappedKeyMaterial() {
        return wrappedKeyMaterial.clone();
    }
}
