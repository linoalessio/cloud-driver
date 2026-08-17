package de.lino.cloud.core.security.crypto;

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
import java.util.Objects;

/**
 * {@link AeadEncryptionService} backed by AES-GCM, per section 5 (DATA
 * ENCRYPTION): AES-256-GCM by default, AES-128-GCM as the documented
 * alternative. Every {@link #encrypt} call draws a fresh nonce from a
 * {@link SecureRandom}, so the same key never sees a repeated nonce/IV.
 *
 * <p>Instances are safe for concurrent use: the only mutable state is the
 * {@link SecureRandom}, and {@link SecureRandom#nextBytes(byte[])} is
 * thread-safe.
 */
public final class AesGcmEncryptionService implements AeadEncryptionService {

    private static final CryptoAlgorithm DEFAULT_ALGORITHM = CryptoAlgorithm.AES_256_GCM;

    private final CryptoAlgorithm algorithm;
    private final SecureRandom secureRandom;

    public AesGcmEncryptionService() {
        this(DEFAULT_ALGORITHM);
    }

    public AesGcmEncryptionService(final CryptoAlgorithm algorithm) {
        this.algorithm = Objects.requireNonNull(algorithm, "@AesGcmEncryptionService: algorithm cannot be null");
        this.secureRandom = new SecureRandom();
    }

    @Override
    public EncryptedPayload encrypt(final byte[] plaintext, final SecretKey key, final byte[] associatedData) {
        Objects.requireNonNull(plaintext, "@AesGcmEncryptionService.encrypt: plaintext cannot be null");
        Objects.requireNonNull(key, "@AesGcmEncryptionService.encrypt: key cannot be null");

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

    @Override
    public byte[] decrypt(final EncryptedPayload payload, final SecretKey key) throws AuthenticationFailedException {
        Objects.requireNonNull(payload, "@AesGcmEncryptionService.decrypt: payload cannot be null");
        Objects.requireNonNull(key, "@AesGcmEncryptionService.decrypt: key cannot be null");

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
