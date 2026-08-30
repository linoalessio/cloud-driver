package de.lino.cloud.plugin.security.crypto;

import de.lino.cloud.api.security.crypto.AeadEncryptionService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.crypto.EncryptedPayload;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import de.lino.cloud.api.utility.Asserts;

/**
 * {@link AeadEncryptionService} backed by AES-GCM (AES-256-GCM by default).
 * Every {@link #encrypt} call draws a fresh nonce, so the same key never
 * sees a repeated nonce/IV. Safe for concurrent use.
 */
public final class AesGcmEncryptionService implements AeadEncryptionService {

    /** The AES-GCM variant used when no {@link CryptoAlgorithm} is given explicitly. */
    private static final CryptoAlgorithm DEFAULT_ALGORITHM = CryptoAlgorithm.AES_256_GCM;

    /** The AES-GCM variant this instance encrypts/decrypts with. */
    private final CryptoAlgorithm algorithm;

    /** Source of fresh, unpredictable nonces for {@link #encrypt}. */
    private final SecureRandom secureRandom;

    /**
     * Constructs a service using {@link #DEFAULT_ALGORITHM} (AES-256-GCM).
     */
    public AesGcmEncryptionService() {
        this(DEFAULT_ALGORITHM);
    }

    /**
     * @param algorithm the AES-GCM variant to encrypt/decrypt with
     * @throws NullPointerException if {@code algorithm} is {@code null}
     */
    public AesGcmEncryptionService(final CryptoAlgorithm algorithm) {
        this.algorithm = Asserts.requireNonNull(algorithm, "@AesGcmEncryptionService: algorithm cannot be null");
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypts {@code plaintext} under {@code key} with a freshly generated nonce.
     *
     * @param plaintext the bytes to encrypt
     * @param key the key to encrypt with
     * @param associatedData additional data to authenticate but not encrypt; may be {@code null}
     * @return the resulting payload
     * @throws NullPointerException if {@code plaintext} or {@code key} is {@code null}
     */
    @Override
    public EncryptedPayload encrypt(final byte[] plaintext, final SecretKey key, final byte[] associatedData) {
        Asserts.requireNonNull(plaintext, "@AesGcmEncryptionService.encrypt: plaintext cannot be null");
        Asserts.requireNonNull(key, "@AesGcmEncryptionService.encrypt: key cannot be null");

        // Unique, unpredictable nonce for every operation - never reused with the same key.
        final byte[] nonce = new byte[algorithm.nonceLengthBytes()];
        secureRandom.nextBytes(nonce);

        try {
            final Cipher cipher = Cipher.getInstance(algorithm.transformation());
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(algorithm.tagLengthBits(), nonce));
            if (associatedData != null && associatedData.length > 0) {
                cipher.updateAAD(associatedData);
            }
            final byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedPayload(algorithm.id(), nonce, ciphertext, associatedData);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("@AesGcmEncryptionService.encrypt: failed to encrypt payload", e);
        }
    }

    /**
     * Decrypts {@code payload} under {@code key}, verifying the authentication tag.
     *
     * @param payload the payload to decrypt
     * @param key the key to decrypt with
     * @return the recovered plaintext
     * @throws NullPointerException if {@code payload} or {@code key} is {@code null}
     * @throws AuthenticationFailedException if the authentication tag verification fails
     */
    @Override
    public byte[] decrypt(final EncryptedPayload payload, final SecretKey key) throws AuthenticationFailedException {
        Asserts.requireNonNull(payload, "@AesGcmEncryptionService.decrypt: payload cannot be null");
        Asserts.requireNonNull(key, "@AesGcmEncryptionService.decrypt: key cannot be null");

        final CryptoAlgorithm payloadAlgorithm = CryptoAlgorithm.fromId(payload.algorithmId());

        try {
            final Cipher cipher = Cipher.getInstance(payloadAlgorithm.transformation());
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(payloadAlgorithm.tagLengthBits(), payload.nonce()));

            final byte[] associatedData = payload.associatedData();
            if (associatedData.length > 0) {
                cipher.updateAAD(associatedData);
            }

            return cipher.doFinal(payload.ciphertext());
        } catch (final AEADBadTagException e) {
            throw new AuthenticationFailedException(
                    "@AesGcmEncryptionService.decrypt: authentication tag verification failed - payload rejected", e
            );
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("@AesGcmEncryptionService.decrypt: failed to decrypt payload", e);
        }
    }
}
