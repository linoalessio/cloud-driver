package de.lino.cloud.plugin.security.hash;

import de.lino.cloud.api.security.hash.HashAlgorithm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import de.lino.cloud.api.utility.Asserts;

/**
 * General-purpose cryptographic hashing restricted to the {@link HashAlgorithm
 * approved algorithms}. Not for password storage - see the {@code password}
 * package for Argon2id.
 */
public final class Hasher {

    /**
     * Not instantiable; all functionality is exposed through static methods.
     */
    private Hasher() {
    }

    /**
     * Hashes {@code data} with {@code algorithm}.
     *
     * @param algorithm the hash algorithm to use
     * @param data the bytes to hash
     * @return the raw digest bytes
     * @throws NullPointerException if {@code algorithm} or {@code data} is {@code null}
     */
    public static byte[] digest(final HashAlgorithm algorithm, final byte[] data) {
        Asserts.requireNonNull(algorithm, "@Hasher.digest: algorithm cannot be null");
        Asserts.requireNonNull(data, "@Hasher.digest: data cannot be null");

        try {
            return MessageDigest.getInstance(algorithm.jcaName()).digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@Hasher.digest: JVM does not provide " + algorithm.jcaName(), e);
        }
    }

    /**
     * {@link #digest(HashAlgorithm, byte[])}, formatted as a lowercase hex string.
     *
     * @param algorithm the hash algorithm to use
     * @param data the bytes to hash
     * @return the digest, as lowercase hex
     * @throws NullPointerException if {@code algorithm} or {@code data} is {@code null}
     */
    public static String hexDigest(final HashAlgorithm algorithm, final byte[] data) {
        return HexFormat.of().formatHex(digest(algorithm, data));
    }
}
