package de.lino.cloud.api.security.keys;

import de.lino.cloud.api.utility.Asserts;

/**
 * A {@link DataEncryptionKey} after being wrapped (encrypted) by a
 * key-encryption key (KEK) held in a {@link KeyEncryptionService}.
 *
 * @param keyEncryptionKeyId identifier of the KEK version that wrapped this key,
 *                            so the correct KEK can be located again after {@link KeyEncryptionService#rotate() rotation}
 * @param wrappedKeyMaterial the wrapped (encrypted) DEK material
 * @param wrapAlgorithm algorithm used to wrap the DEK
 * @param dataEncryptionKeyAlgorithmId identifier of the algorithm the unwrapped DEK itself uses
 */
public record WrappedKey(String keyEncryptionKeyId, byte[] wrappedKeyMaterial, String wrapAlgorithm,
                          String dataEncryptionKeyAlgorithmId) {

    /**
     * @throws NullPointerException if any component is {@code null}
     */
    public WrappedKey {
        Asserts.requireNonNull(keyEncryptionKeyId, "@WrappedKey: keyEncryptionKeyId cannot be null");
        Asserts.requireNonNull(wrappedKeyMaterial, "@WrappedKey: wrappedKeyMaterial cannot be null");
        Asserts.requireNonNull(wrapAlgorithm, "@WrappedKey: wrapAlgorithm cannot be null");
        Asserts.requireNonNull(dataEncryptionKeyAlgorithmId, "@WrappedKey: dataEncryptionKeyAlgorithmId cannot be null");

        wrappedKeyMaterial = wrappedKeyMaterial.clone();
    }

    /** @return a defensive copy of the wrapped key material */
    @Override
    public byte[] wrappedKeyMaterial() {
        return wrappedKeyMaterial.clone();
    }
}
