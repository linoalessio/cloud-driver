package de.lino.cloud.api.security.crypto;

import javax.crypto.SecretKey;

/**
 * Authenticated encryption (AEAD) for payloads, independent of transport-layer TLS.
 */
public interface AeadEncryptionService {

    /**
     * Encrypts {@code plaintext} under {@code key}, binding {@code associatedData}
     * into the authentication tag without encrypting it. Uses a fresh nonce per
     * call, never reused with the same key.
     *
     * @param plaintext the data to encrypt
     * @param key the encryption key
     * @param associatedData additional data to authenticate but not encrypt; may be {@code null} or empty
     * @return the encrypted payload, algorithm-tagged for later decryption
     */
    EncryptedPayload encrypt(byte[] plaintext, SecretKey key, byte[] associatedData);

    /**
     * Decrypts {@code payload} with {@code key}, verifying its authentication
     * tag before returning any plaintext.
     *
     * @param payload the payload to decrypt
     * @param key the decryption key
     * @return the decrypted plaintext
     * @throws AuthenticationFailedException if the authentication tag does not verify
     */
    byte[] decrypt(EncryptedPayload payload, SecretKey key) throws AuthenticationFailedException;
}
