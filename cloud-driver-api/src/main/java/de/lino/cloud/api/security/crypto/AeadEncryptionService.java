package de.lino.cloud.api.security.crypto;

import javax.crypto.SecretKey;

/**
 * Application-level authenticated encryption per section 2B (DATA/PAYLOAD
 * SECURITY): sensitive payloads are encrypted with an authenticated cipher
 * before transmission, independently of transport-layer TLS.
 */
public interface AeadEncryptionService {

    /**
     * Encrypts {@code plaintext} under {@code key}, binding {@code associatedData}
     * into the authentication tag without encrypting it. A fresh, unpredictable
     * nonce is generated for this call; it is never reused across calls with the
     * same key.
     *
     * @param plaintext the data to encrypt
     * @param key the encryption key
     * @param associatedData additional data to authenticate but not encrypt
     *                       (e.g. protocol/version identifiers, tenant or record
     *                       ids); may be {@code null} or empty
     * @return the encrypted payload, algorithm-tagged for later decryption
     */
    EncryptedPayload encrypt(byte[] plaintext, SecretKey key, byte[] associatedData);

    /**
     * Decrypts {@code payload} with {@code key}, verifying its authentication
     * tag before returning any plaintext.
     *
     * @throws AuthenticationFailedException if the authentication tag does not
     *         verify; the payload SHALL be treated as rejected/untrusted
     */
    byte[] decrypt(EncryptedPayload payload, SecretKey key) throws AuthenticationFailedException;
}
