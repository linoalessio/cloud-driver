package de.lino.cloud.api.file.meta;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.utility.Asserts;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A content checksum over a {@link StoredFile}'s plaintext bytes, independent
 * of the AES-256-GCM authentication tag {@link
 * de.lino.cloud.api.security.crypto.AuthenticationFailedException} already
 * guards on the stored ciphertext (section 6, AUTHENTICATED ENCRYPTION AND
 * INTEGRITY). The authentication tag proves the stored envelope was not
 * tampered with; this proves the decrypted plaintext content still matches
 * what was uploaded - two related but distinct guarantees. A caller
 * downloading a {@link StoredFile} via {@link
 * de.lino.cloud.api.factory.DataFactory#fetch} can check both: an
 * authentication failure throws automatically during decryption, while
 * {@link #matches(byte[])} lets the caller additionally verify the
 * downloaded content against this checksum.
 *
 * <p>Restricted to {@link HashAlgorithm}'s approved algorithms, the same as
 * every other hash computed by {@code cloud-driver}.
 */
public record FileChecksum(HashAlgorithm algorithm, String hexDigest) {

    public FileChecksum {
        Asserts.assertNotNull(algorithm, "@FileChecksum: algorithm cannot be null");
        Asserts.assertNotNull(hexDigest, "@FileChecksum: hexDigest cannot be null");

        hexDigest = hexDigest.toLowerCase(Locale.ROOT);
        if (hexDigest.length() != hexLength(algorithm) || !isHex(hexDigest)) {
            throw new IllegalArgumentException(
                    "@FileChecksum: hexDigest must be a " + hexLength(algorithm) + "-character hex string for " + algorithm
            );
        }
    }

    /**
     * Computes the {@code algorithm} checksum of {@code content}.
     *
     * @param algorithm the hash algorithm to checksum with
     * @param content the plaintext bytes to checksum
     * @return the resulting checksum
     * @throws NullPointerException if {@code algorithm} or {@code content} is {@code null}
     */
    public static FileChecksum of(final HashAlgorithm algorithm, final byte[] content) {
        Asserts.assertNotNull(algorithm, "@FileChecksum.of: algorithm cannot be null");
        Asserts.assertNotNull(content, "@FileChecksum.of: content cannot be null");

        try {
            final byte[] digest = MessageDigest.getInstance(algorithm.jcaName()).digest(content);
            return new FileChecksum(algorithm, HexFormat.of().formatHex(digest));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@FileChecksum.of: JVM does not provide " + algorithm.jcaName(), e);
        }
    }

    /**
     * Whether {@code content} checksums to {@link #hexDigest()} under {@link #algorithm()}.
     *
     * @param content the plaintext bytes to verify
     * @return {@code true} if {@code content} checksums to {@link #hexDigest()}
     * @throws NullPointerException if {@code content} is {@code null}
     */
    public boolean matches(final byte[] content) {
        return this.equals(FileChecksum.of(this.algorithm, content));
    }

    private static int hexLength(final HashAlgorithm algorithm) {
        return switch (algorithm) {
            case SHA_256 -> 64;
            case SHA_384 -> 96;
            case SHA_512 -> 128;
        };
    }

    private static boolean isHex(final String value) {
        return value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
