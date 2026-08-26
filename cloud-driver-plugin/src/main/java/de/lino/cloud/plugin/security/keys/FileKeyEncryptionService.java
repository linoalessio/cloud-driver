package de.lino.cloud.plugin.security.keys;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import de.lino.cloud.api.utility.Asserts;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link KeyEncryptionService} for local development: same
 * wrap/unwrap mechanics as {@link InMemoryKeyEncryptionService}, but KEK
 * material is persisted as a {@link JsonDocument} at a given path so it
 * survives JVM restarts (e.g. so a fetch-only run can still unwrap data
 * written by an earlier run).
 *
 * <p><strong>NOT for production use</strong>, for the same reason as {@link
 * InMemoryKeyEncryptionService}: KEK material sits in a plaintext file on
 * disk rather than in a KMS/HSM.
 */
public final class FileKeyEncryptionService implements KeyEncryptionService {

    private static final String WRAP_TRANSFORMATION = "AESWrapPad";
    private static final int KEY_ENCRYPTION_KEY_LENGTH_BYTES = CryptoAlgorithm.AES_256_GCM.keyLengthBytes();
    private static final String ACTIVE_KEY_ID_FIELD = "activeKeyId";
    private static final String KEY_ENCRYPTION_KEYS_FIELD = "keyEncryptionKeys";

    private final Path path;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, byte[]> keyEncryptionKeys = new ConcurrentHashMap<>();
    private volatile String activeKeyId;

    /**
     * Loads existing KEK material from {@code path} if it already exists, otherwise {@link #rotate()}s a fresh one and persists it there.
     *
     * @param path the file KEK material is persisted to/loaded from
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public FileKeyEncryptionService(@NotNull final Path path) {
        this.path = Asserts.requireNonNull(path, "@FileKeyEncryptionService: path cannot be null");

        if (Files.exists(path)) {
            load();
        } else {
            rotate();
        }
    }

    @Override
    public WrappedKey wrap(final DataEncryptionKey dataEncryptionKey) throws KeyWrapException {
        Asserts.requireNonNull(dataEncryptionKey, "@FileKeyEncryptionService.wrap: dataEncryptionKey cannot be null");

        final String keyId = activeKeyId;
        final SecretKey kek = new SecretKeySpec(keyEncryptionKeys.get(keyId), "AES");

        try {
            final Cipher cipher = Cipher.getInstance(WRAP_TRANSFORMATION);
            cipher.init(Cipher.WRAP_MODE, kek);
            final byte[] wrapped = cipher.wrap(dataEncryptionKey.asSecretKey());
            return new WrappedKey(keyId, wrapped, WRAP_TRANSFORMATION, dataEncryptionKey.algorithm().id());
        } catch (final GeneralSecurityException e) {
            throw new KeyWrapException("@FileKeyEncryptionService.wrap: failed to wrap data-encryption key", e);
        }
    }

    @Override
    public DataEncryptionKey unwrap(final WrappedKey wrappedKey) throws KeyWrapException {
        Asserts.requireNonNull(wrappedKey, "@FileKeyEncryptionService.unwrap: wrappedKey cannot be null");

        final byte[] kekMaterial = keyEncryptionKeys.get(wrappedKey.keyEncryptionKeyId());
        if (kekMaterial == null) {
            throw new KeyWrapException(
                    "@FileKeyEncryptionService.unwrap: unknown key-encryption-key id '"
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
            throw new KeyWrapException("@FileKeyEncryptionService.unwrap: failed to unwrap data-encryption key", e);
        }
    }

    @Override
    public String activeKeyEncryptionKeyId() {
        return activeKeyId;
    }

    @Override
    public synchronized String rotate() {
        final byte[] material = new byte[KEY_ENCRYPTION_KEY_LENGTH_BYTES];
        secureRandom.nextBytes(material);

        final String keyId = UUID.randomUUID().toString();
        keyEncryptionKeys.put(keyId, material);
        activeKeyId = keyId;
        persist();
        return keyId;
    }

    private void load() {
        final JsonDocument document = JsonDocument.load(path);
        activeKeyId = document.getString(ACTIVE_KEY_ID_FIELD);

        final JsonDocument keys = document.getMetaData(KEY_ENCRYPTION_KEYS_FIELD);
        if (keys != null) {
            for (final String keyId : keys.getKeys()) {
                keyEncryptionKeys.put(keyId, Base64.getDecoder().decode(keys.getString(keyId)));
            }
        }
    }

    private synchronized void persist() {
        final JsonDocument keys = new JsonDocument();
        keyEncryptionKeys.forEach((keyId, material) -> keys.append(keyId, Base64.getEncoder().encodeToString(material)));

        new JsonDocument()
                .append(ACTIVE_KEY_ID_FIELD, activeKeyId)
                .append(KEY_ENCRYPTION_KEYS_FIELD, keys)
                .write(path);
    }
}
