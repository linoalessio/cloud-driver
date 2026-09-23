package de.lino.cloud.api.security.hash;

import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the primary key an entity keyed on a credential or an e-mail address is stored under.
 *
 * <p>An entity's primary key lands in its row's {@code id} column verbatim, and that column is
 * never encrypted: envelope encryption covers the payload, not the key. The primary key is also
 * bound into the payload's authenticated data, so it is present in the row a second time. A
 * primary key derived from a refresh token or an account address would therefore sit in the
 * database - and in every backup archive - in the clear, readable without the KMS-held key that
 * protects everything else. Such a key is stored as the digest this class produces instead, and
 * the raw value lives only inside the encrypted payload.
 *
 * <p>Unsalted and unstretched deliberately. The lookup has to be deterministic - the presenter
 * supplies the value and the server hashes it before a single O(1) point read - and the inputs
 * carry their own entropy (48 bytes of {@code SecureRandom} for a token) or are supplied in full
 * by the caller (an address), so there is nothing a work factor would buy and no reason to make
 * every refresh pay for one.
 */
public final class LookupKeyDigest {

    /** How many characters {@link #hexOf(String)} produces: a SHA-256 digest in lowercase hex. */
    private static final int DIGEST_LENGTH = 64;

    /** Not instantiable - a pure namespace for {@link #hexOf(String)}/{@link #isDigest(String)}. */
    private LookupKeyDigest() {
    }

    /**
     * The primary key a row looked up by {@code value} is stored under.
     *
     * @param value the credential or address a row is looked up by
     * @return the lowercase-hex SHA-256 of {@code value}, the id its row is stored under
     */
    @NotNull
    public static String hexOf(@NonNull final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(HashAlgorithm.SHA_256.jcaName())
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("@LookupKeyDigest.hexOf: JVM does not provide "
                    + HashAlgorithm.SHA_256.jcaName(), impossible);
        }
    }

    /**
     * Whether {@code candidate} has the exact shape {@link #hexOf(String)} produces - 64
     * lowercase hexadecimal characters. Used where a presented value must be kept out of a
     * lookup that would otherwise treat a stored key as if it were the value it was derived
     * from.
     *
     * @param candidate any string, {@code null} included
     * @return {@code true} if it has the exact shape {@link #hexOf(String)} produces
     */
    public static boolean isDigest(final String candidate) {
        if (candidate == null || candidate.length() != DIGEST_LENGTH) {
            return false;
        }
        for (int index = 0; index < DIGEST_LENGTH; index++) {
            final char character = candidate.charAt(index);
            final boolean hexadecimal = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
            if (!hexadecimal) {
                return false;
            }
        }
        return true;
    }

}
