package de.lino.cloud.api.security.crypto;

/**
 * Approved AES-GCM authenticated-encryption algorithms. {@link #AES_256_GCM}
 * is the mandated default; {@link #AES_128_GCM} is kept as an alternative.
 * The {@link #id()} is stored alongside encrypted payloads (see
 * {@link EncryptedPayload}) so the algorithm used for a given payload can
 * always be recovered.
 */
public enum CryptoAlgorithm {

    /** AES-256-GCM, a 32-byte (256-bit) key. */
    AES_256_GCM("AES-256-GCM", "AES/GCM/NoPadding", 32, 12, 128),

    /** AES-128-GCM, a 16-byte (128-bit) key. */
    AES_128_GCM("AES-128-GCM", "AES/GCM/NoPadding", 16, 12, 128);

    private final String id;
    private final String transformation;
    private final int keyLengthBytes;
    private final int nonceLengthBytes;
    private final int tagLengthBits;

    /**
     * @param id the stable identifier persisted alongside encrypted payloads
     * @param transformation the JCA cipher transformation implementing this algorithm
     * @param keyLengthBytes the key length, in bytes
     * @param nonceLengthBytes the nonce/IV length, in bytes, a single encryption operation must use
     * @param tagLengthBits the GCM authentication tag length, in bits
     */
    CryptoAlgorithm(final String id, final String transformation, final int keyLengthBytes,
                     final int nonceLengthBytes, final int tagLengthBits) {
        this.id = id;
        this.transformation = transformation;
        this.keyLengthBytes = keyLengthBytes;
        this.nonceLengthBytes = nonceLengthBytes;
        this.tagLengthBits = tagLengthBits;
    }

    /** Stable identifier persisted with encrypted payloads for crypto agility. */
    public String id() {
        return id;
    }

    /** The JCA cipher transformation implementing this algorithm. */
    public String transformation() {
        return transformation;
    }

    /** Length, in bytes, of the key this algorithm uses. */
    public int keyLengthBytes() {
        return keyLengthBytes;
    }

    /** Length, in bytes, of the nonce/IV; a fresh one is required per encryption, never reused with the same key. */
    public int nonceLengthBytes() {
        return nonceLengthBytes;
    }

    /** Length, in bits, of the GCM authentication tag this algorithm produces. */
    public int tagLengthBits() {
        return tagLengthBits;
    }

    /**
     * Looks up the {@link CryptoAlgorithm} whose {@link #id()} equals {@code id}.
     *
     * @param id the stable identifier to look up
     * @return the matching algorithm
     * @throws IllegalArgumentException if no algorithm has that identifier
     */
    public static CryptoAlgorithm fromId(final String id) {
        for (final CryptoAlgorithm algorithm : values()) {
            if (algorithm.id.equals(id)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("@CryptoAlgorithm.fromId: unknown/unsupported algorithm identifier '" + id + "'");
    }
}
