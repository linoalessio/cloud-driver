package de.lino.cloud.plugin.security.keys;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.keys.DataEncryptionKey;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Generates random {@link DataEncryptionKey data-encryption keys} for
 * envelope encryption, per section 4: "A randomly generated data-encryption
 * key (DEK) SHALL encrypt the data."
 */
public final class DataEncryptionKeyGenerator {

    private final SecureRandom secureRandom = new SecureRandom ();

    public DataEncryptionKey generate(final CryptoAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "@DataEncryptionKeyGenerator.generate: algorithm cannot be null");
        final byte[] material = new byte[algorithm.keyLengthBytes()];
        secureRandom.nextBytes(material);
        return new DataEncryptionKey(algorithm, material);
    }

    public DataEncryptionKey generate() {
        return generate(CryptoAlgorithm.AES_256_GCM);
    }
}
