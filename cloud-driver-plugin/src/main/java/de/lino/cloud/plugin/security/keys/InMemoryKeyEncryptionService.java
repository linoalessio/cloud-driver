package de.lino.cloud.plugin.security.keys;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import de.lino.cloud.api.utility.Asserts;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link KeyEncryptionService} for local development and tests.
 *
 * <p><strong>NOT for production use.</strong> Per sections 4 and 8 of the
 * security requirements, master/key-encryption-key material SHALL be managed
 * by a KMS or HSM in production, and STRATO-specific capabilities SHALL be
 * verified before production deployment. This class keeps key-encryption key
 * (KEK) material as plain byte arrays in process memory - it exists only to
 * exercise the {@link KeyEncryptionService} contract end-to-end before a real
 * KMS/HSM integration (e.g. AWS KMS, Azure Key Vault, HashiCorp Vault, or a
 * PKCS#11 HSM) is wired in for the target deployment.
 *
 * <p>Wrapping uses AES Key Wrap with Padding (RFC 5649 / NIST SP 800-38F,
 * JCA transformation {@code AESWrapPad}), the approved key-wrap mechanism
 * called for in section 5 (KEY WRAPPING / KEY MANAGEMENT) when a KMS/HSM
 * envelope-encryption call is not available.
 */
public final class InMemoryKeyEncryptionService implements KeyEncryptionService {

    private static final String WRAP_TRANSFORMATION = "AESWrapPad";
    private static final int KEY_ENCRYPTION_KEY_LENGTH_BYTES = CryptoAlgorithm.AES_256_GCM.keyLengthBytes();

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, byte[]> keyEncryptionKeys = new ConcurrentHashMap<>();
    private volatile String activeKeyId;

    public InMemoryKeyEncryptionService() {
        rotate();
    }

    @Override
    public WrappedKey wrap(final DataEncryptionKey dataEncryptionKey) throws KeyWrapException {
        Asserts.assertNotNull(dataEncryptionKey, "@InMemoryKeyEncryptionService.wrap: dataEncryptionKey cannot be null");

        final String keyId = activeKeyId;
        final SecretKey kek = new SecretKeySpec(keyEncryptionKeys.get(keyId), "AES");

        try {
            final Cipher cipher = Cipher.getInstance(WRAP_TRANSFORMATION);
            cipher.init(Cipher.WRAP_MODE, kek);
            final byte[] wrapped = cipher.wrap(dataEncryptionKey.asSecretKey());
            return new WrappedKey(keyId, wrapped, WRAP_TRANSFORMATION, dataEncryptionKey.algorithm().id());
        } catch (final GeneralSecurityException e) {
            throw new KeyWrapException("@InMemoryKeyEncryptionService.wrap: failed to wrap data-encryption key", e);
        }
    }

    @Override
    public DataEncryptionKey unwrap(final WrappedKey wrappedKey) throws KeyWrapException {
        Asserts.assertNotNull(wrappedKey, "@InMemoryKeyEncryptionService.unwrap: wrappedKey cannot be null");

        final byte[] kekMaterial = keyEncryptionKeys.get(wrappedKey.keyEncryptionKeyId());
        if (kekMaterial == null) {
            throw new KeyWrapException(
                    "@InMemoryKeyEncryptionService.unwrap: unknown key-encryption-key id '"
                            + wrappedKey.keyEncryptionKeyId() + "'", null
            );
        }

        try {
            final Cipher cipher = Cipher.getInstance(wrappedKey.wrapAlgorithm());
            cipher.init(Cipher.UNWRAP_MODE, new SecretKeySpec(kekMaterial, "AES"));
            final SecretKey unwrapped = (SecretKey) cipher.unwrap(wrappedKey.wrappedKeyMaterial(), "AES", Cipher.SECRET_KEY);
            final CryptoAlgorithm dekAlgorithm = CryptoAlgorithm.fromId(wrappedKey.dataEncryptionKeyAlgorithmId());
            return new DataEncryptionKey(dekAlgorithm, unwrapped.getEncoded());
        } catch (final GeneralSecurityException e) {
            throw new KeyWrapException("@InMemoryKeyEncryptionService.unwrap: failed to unwrap data-encryption key", e);
        }
    }

    @Override
    public String activeKeyEncryptionKeyId() {
        return activeKeyId;
    }

    @Override
    public String rotate() {
        final byte[] material = new byte[KEY_ENCRYPTION_KEY_LENGTH_BYTES];
        secureRandom.nextBytes(material);

        final String keyId = UUID.randomUUID().toString();
        keyEncryptionKeys.put(keyId, material);
        activeKeyId = keyId;
        return keyId;
    }
}
