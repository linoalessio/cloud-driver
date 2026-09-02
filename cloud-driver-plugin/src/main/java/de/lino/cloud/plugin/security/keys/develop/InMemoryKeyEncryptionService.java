package de.lino.cloud.plugin.security.keys.develop;

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
 * Key-encryption key (KEK) material is kept as plain byte arrays in process
 * memory, lost on restart. <strong>Not for production use</strong> - wrap a
 * real KMS/HSM instead. Wrapping uses AES Key Wrap with Padding (JCA
 * transformation {@code AESWrapPad}).
 */
public final class InMemoryKeyEncryptionService implements KeyEncryptionService {

    /** JCA cipher transformation used to wrap/unwrap data-encryption keys under a key-encryption key. */
    private static final String WRAP_TRANSFORMATION = "AESWrapPad";

    /** Length, in bytes, of each freshly generated key-encryption key's material. */
    private static final int KEY_ENCRYPTION_KEY_LENGTH_BYTES = CryptoAlgorithm.AES_256_GCM.keyLengthBytes();

    /** Source of the random key material {@link #rotate()} draws from. */
    private final SecureRandom secureRandom = new SecureRandom();

    /** Every retained key-encryption key's raw material, keyed by its id - superseded keys are kept so old wrapped data stays unwrappable. */
    private final Map<String, byte[]> keyEncryptionKeys = new ConcurrentHashMap<>();

    /** Id of the key-encryption key currently used for new {@link #wrap} calls. */
    private volatile String activeKeyId;

    /**
     * Generates and activates a fresh in-memory key-encryption key via {@link #rotate()}.
     */
    public InMemoryKeyEncryptionService() {
        rotate();
    }

    /**
     * Wraps {@code dataEncryptionKey} under the active KEK.
     *
     * @param dataEncryptionKey the key to wrap
     * @return the wrapped key
     * @throws NullPointerException if {@code dataEncryptionKey} is {@code null}
     * @throws KeyWrapException if wrapping fails
     */
    @Override
    public WrappedKey wrap(final DataEncryptionKey dataEncryptionKey) throws KeyWrapException {
        Asserts.requireNonNull(dataEncryptionKey, "@InMemoryKeyEncryptionService.wrap: dataEncryptionKey cannot be null");

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

    /**
     * Unwraps {@code wrappedKey} under its recorded KEK.
     *
     * @param wrappedKey the key to unwrap
     * @return the unwrapped key
     * @throws NullPointerException if {@code wrappedKey} is {@code null}
     * @throws KeyWrapException if the KEK id is unknown or unwrapping fails
     */
    @Override
    public DataEncryptionKey unwrap(final WrappedKey wrappedKey) throws KeyWrapException {
        Asserts.requireNonNull(wrappedKey, "@InMemoryKeyEncryptionService.unwrap: wrappedKey cannot be null");

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

    /** @return the id of the currently active key-encryption key */
    @Override
    public String activeKeyEncryptionKeyId() {
        return activeKeyId;
    }

    /**
     * Generates a fresh KEK and activates it. Previously wrapped keys stay
     * unwrappable - their KEK id is retained.
     *
     * @return the new key's id
     */
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
