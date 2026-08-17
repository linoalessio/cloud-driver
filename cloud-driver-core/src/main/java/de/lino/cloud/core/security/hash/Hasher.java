package de.lino.cloud.core.security.hash;

import de.lino.cloud.api.security.hash.HashAlgorithm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * General-purpose cryptographic hashing restricted to the {@link HashAlgorithm
 * approved algorithms}. Not for password storage - see the {@code password}
 * package for Argon2id.
 */
public final class Hasher {

    private Hasher() {
    }

    public static byte[] digest(final HashAlgorithm algorithm, final byte[] data) {
        Objects.requireNonNull(algorithm, "@Hasher.digest: algorithm cannot be null");
        Objects.requireNonNull(data, "@Hasher.digest: data cannot be null");

        try {
            return MessageDigest.getInstance(algorithm.jcaName()).digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@Hasher.digest: JVM does not provide " + algorithm.jcaName(), e);
        }
    }

    public static String hexDigest(final HashAlgorithm algorithm, final byte[] data) {
        return HexFormat.of().formatHex(digest(algorithm, data));
    }
}
