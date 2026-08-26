package de.lino.cloud.api.security.hash;

/**
 * Approved hashing algorithms: SHA-256/384/512 only. MD5 and SHA-1 are
 * intentionally not representable.
 */
public enum HashAlgorithm {

    /**
     * SHA-256, a 256-bit digest.
     */
    SHA_256("SHA-256"),

    /**
     * SHA-384, a 384-bit digest.
     */
    SHA_384("SHA-384"),

    /**
     * SHA-512, a 512-bit digest.
     */
    SHA_512("SHA-512");

    private final String jcaName;

    /**
     * @param jcaName the JCA {@link java.security.MessageDigest} algorithm name
     */
    HashAlgorithm(final String jcaName) {
        this.jcaName = jcaName;
    }

    /**
     * The JCA {@link java.security.MessageDigest} algorithm name for this hash algorithm.
     */
    public String jcaName() {
        return jcaName;
    }
}
