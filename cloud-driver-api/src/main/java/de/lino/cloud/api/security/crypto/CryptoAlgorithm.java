package de.lino.cloud.api.security.crypto;

/**
 * Approved authenticated-encryption algorithms per section 5 (DATA ENCRYPTION)
 * of the security requirements. {@link #AES_256_GCM} is the mandated default;
 * {@link #AES_128_GCM} is kept only as the documented alternative "where
 * appropriate". Every algorithm here uses AES-GCM (authenticated encryption);
 * ECB and proprietary schemes are intentionally not representable.
 *
 * <p>The {@link #id()} is stored alongside encrypted payloads (see
 * {@link EncryptedPayload}) rather than relying on positional/implicit
 * encoding, so that the algorithm used for a given payload can always be
 * recovered and future algorithm/version migrations - including a future
 * move to NIST post-quantum primitives - do not require redesigning stored
 * data formats.
 */
public enum CryptoAlgorithm {

    AES_256_GCM("AES-256-GCM", "AES/GCM/NoPadding", 32, 12, 128),
    AES_128_GCM("AES-128-GCM", "AES/GCM/NoPadding", 16, 12, 128);

    private final String id;
    private final String transformation;
    private final int keyLengthBytes;
    private final int nonceLengthBytes;
    private final int tagLengthBits;

    CryptoAlgorithm(final String id, final String transformation, final int keyLengthBytes,
                     final int nonceLengthBytes, final int tagLengthBits) {
        this.id = id;
        this.transformation = transformation;
        this.keyLengthBytes = keyLengthBytes;
        this.nonceLengthBytes = nonceLengthBytes;
        this.tagLengthBits = tagLengthBits;
    }

    /**
     * Stable identifier persisted with encrypted payloads for crypto agility.
     */
    public String id() {
        return id;
    }

    /**
     * The JCA cipher transformation implementing this algorithm.
     */
    public String transformation() {
        return transformation;
    }

    public int keyLengthBytes() {
        return keyLengthBytes;
    }

    /**
     * Length, in bytes, of the nonce/IV a single encryption operation must use.
     * A fresh, unpredictable nonce of this length SHALL be generated for every
     * encryption and SHALL NEVER be reused with the same key.
     */
    public int nonceLengthBytes() {
        return nonceLengthBytes;
    }

    public int tagLengthBits() {
        return tagLengthBits;
    }

    public static CryptoAlgorithm fromId(final String id) {
        for (final CryptoAlgorithm algorithm : values()) {
            if (algorithm.id.equals(id)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("@CryptoAlgorithm.fromId: unknown/unsupported algorithm identifier '" + id + "'");
    }
}
