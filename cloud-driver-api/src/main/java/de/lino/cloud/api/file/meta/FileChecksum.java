package de.lino.cloud.api.file.meta;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.utility.Asserts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A content checksum over a {@link StoredFile}'s plaintext bytes,
 * independent of the AES-256-GCM authentication tag already guarding the
 * stored ciphertext: the tag proves the envelope wasn't tampered with, this
 * proves the decrypted content still matches what was uploaded. Restricted
 * to {@link HashAlgorithm}'s approved algorithms.
 *
 * @param algorithm the hash algorithm {@link #hexDigest} was computed with
 * @param hexDigest the lowercase hex-encoded digest; must match {@code algorithm}'s expected length
 */
public record FileChecksum(HashAlgorithm algorithm, String hexDigest) {

    /**
     * Validates {@code algorithm}/{@code hexDigest} and lowercases {@code hexDigest}.
     *
     * @throws NullPointerException if {@code algorithm} or {@code hexDigest} is {@code null}
     * @throws IllegalArgumentException if {@code hexDigest} isn't a hex string of the length {@code algorithm} expects
     */
    public FileChecksum {
        Asserts.requireNonNull(algorithm, "@FileChecksum: algorithm cannot be null");
        Asserts.requireNonNull(hexDigest, "@FileChecksum: hexDigest cannot be null");

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
        Asserts.requireNonNull(algorithm, "@FileChecksum.of: algorithm cannot be null");
        Asserts.requireNonNull(content, "@FileChecksum.of: content cannot be null");

        try {
            final byte[] digest = MessageDigest.getInstance(algorithm.jcaName()).digest(content);
            return new FileChecksum(algorithm, HexFormat.of().formatHex(digest));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@FileChecksum.of: JVM does not provide " + algorithm.jcaName(), e);
        }
    }

    /**
     * Computes the {@code algorithm} checksum of everything {@code content} yields, reading it
     * in fixed-size buffers - the streaming counterpart of {@link #of(HashAlgorithm, byte[])},
     * for content too large to hold in memory as one array (e.g. a scratch-file-backed upload,
     * see {@code CloudUserService#uploadFile}'s {@code Path} overload). The stream is drained
     * fully but deliberately not closed - the caller owns it.
     *
     * @param algorithm the hash algorithm to checksum with
     * @param content the plaintext byte stream to checksum; drained fully, not closed
     * @return the resulting checksum
     * @throws NullPointerException if {@code algorithm} or {@code content} is {@code null}
     * @throws UncheckedIOException if reading {@code content} fails
     */
    public static FileChecksum of(final HashAlgorithm algorithm, final InputStream content) {
        Asserts.requireNonNull(algorithm, "@FileChecksum.of: algorithm cannot be null");
        Asserts.requireNonNull(content, "@FileChecksum.of: content cannot be null");

        try {
            final MessageDigest digest = MessageDigest.getInstance(algorithm.jcaName());
            final byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = content.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return new FileChecksum(algorithm, HexFormat.of().formatHex(digest.digest()));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@FileChecksum.of: JVM does not provide " + algorithm.jcaName(), e);
        } catch (final IOException e) {
            throw new UncheckedIOException("@FileChecksum.of: failed reading the content stream to checksum", e);
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

    /**
     * The exact hex-digest string length {@code algorithm} produces.
     *
     * @param algorithm the hash algorithm to look up the expected digest length for
     * @return the expected hex-digest length, in characters
     */
    private static int hexLength(final HashAlgorithm algorithm) {
        return switch (algorithm) {
            case SHA_256 -> 64;
            case SHA_384 -> 96;
            case SHA_512 -> 128;
        };
    }

    /**
     * Whether {@code value} consists only of lowercase hex digit characters.
     *
     * @param value the string to check
     * @return {@code true} if every character in {@code value} is {@code 0-9} or {@code a-f}
     */
    private static boolean isHex(final String value) {
        return value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
