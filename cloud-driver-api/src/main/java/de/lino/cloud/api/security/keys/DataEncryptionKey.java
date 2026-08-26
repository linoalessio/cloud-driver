package de.lino.cloud.api.security.keys;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import de.lino.cloud.api.utility.Asserts;

/**
 * A randomly generated data-encryption key (DEK), per section 4 (KEY
 * MANAGEMENT). A DEK is short-lived: it protects a single payload (or a
 * single envelope-encryption operation), is wrapped by a
 * {@link KeyEncryptionService}-managed key-encryption key (KEK) for storage
 * or transmission, and its raw material SHOULD be discarded as soon as it is
 * no longer needed - see {@link #destroy()}.
 */
public final class DataEncryptionKey {

    private final CryptoAlgorithm algorithm;
    private final byte[] keyMaterial;

    /**
     * @param algorithm the algorithm this key material is used with
     * @param keyMaterial the raw key bytes
     * @throws NullPointerException if {@code algorithm} or {@code keyMaterial} is {@code null}
     */
    public DataEncryptionKey(final CryptoAlgorithm algorithm, final byte[] keyMaterial) {
        this.algorithm = Asserts.requireNonNull(algorithm, "@DataEncryptionKey: algorithm cannot be null");
        this.keyMaterial = Asserts.requireNonNull(keyMaterial, "@DataEncryptionKey: keyMaterial cannot be null");
    }

    /**
     * The algorithm this key's material is used with.
     */
    public CryptoAlgorithm algorithm() {
        return algorithm;
    }

    /**
     * This key's raw material as a JCA {@link SecretKey}, ready to hand to a {@link javax.crypto.Cipher}.
     */
    public SecretKey asSecretKey() {
        return new SecretKeySpec(keyMaterial, "AES");
    }

    /**
     * Zeroes the raw key material in place. Call once the key is no longer
     * needed (e.g. after the surrounding encrypt/decrypt call returns) so it
     * does not linger in the heap for longer than necessary.
     */
    public void destroy() {
        Arrays.fill(keyMaterial, (byte) 0);
    }
}
