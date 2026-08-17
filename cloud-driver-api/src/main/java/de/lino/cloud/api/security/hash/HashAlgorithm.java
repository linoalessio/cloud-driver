package de.lino.cloud.api.security.hash;

/**
 * Approved hashing algorithms per section 5 (HASHING) of the security
 * requirements. MD5 and SHA-1 are intentionally not offered here: "MD5 SHALL
 * NOT be used for security purposes" and "SHA-1 SHALL NOT be used for new
 * security designs."
 */
public enum HashAlgorithm {

    SHA_256("SHA-256"),
    SHA_384("SHA-384"),
    SHA_512("SHA-512");

    private final String jcaName;

    HashAlgorithm(final String jcaName) {
        this.jcaName = jcaName;
    }

    public String jcaName() {
        return jcaName;
    }
}
