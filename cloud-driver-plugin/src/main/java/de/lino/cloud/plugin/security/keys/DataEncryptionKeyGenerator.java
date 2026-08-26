package de.lino.cloud.plugin.security.keys;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.keys.DataEncryptionKey;

import java.security.SecureRandom;
import de.lino.cloud.api.utility.Asserts;

/**
 * Generates random {@link DataEncryptionKey data-encryption keys} for
 * envelope encryption, per section 4: "A randomly generated data-encryption
 * key (DEK) SHALL encrypt the data."
 */
public final class DataEncryptionKeyGenerator {

    private final SecureRandom secureRandom = new SecureRandom ();

    /**
     * Generates a fresh, random {@link DataEncryptionKey} of {@code algorithm}'s key length.
     *
     * @param algorithm the algorithm the generated key's material will be used with
     * @return the generated key
     * @throws NullPointerException if {@code algorithm} is {@code null}
     */
    public DataEncryptionKey generate(final CryptoAlgorithm algorithm) {
        Asserts.requireNonNull(algorithm, "@DataEncryptionKeyGenerator.generate: algorithm cannot be null");
        final byte[] material = new byte[algorithm.keyLengthBytes()];
        secureRandom.nextBytes(material);
        return new DataEncryptionKey(algorithm, material);
    }

    /**
     * {@link #generate(CryptoAlgorithm)} for {@link CryptoAlgorithm#AES_256_GCM}.
     *
     * @return the generated key
     */
    public DataEncryptionKey generate() {
        return generate(CryptoAlgorithm.AES_256_GCM);
    }
}
