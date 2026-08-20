package de.lino.cloud.plugin.security.keys;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import de.lino.cloud.api.utility.Asserts;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database-backed {@link KeyEncryptionService}: same wrap/unwrap mechanics as
 * {@link FileKeyEncryptionService}, but key-encryption key (KEK) material is
 * persisted as a single {@link DatabaseEntry} in a {@link DatabaseSection}
 * instead of a local file - so it is not bound to one machine's filesystem
 * and survives being read/written from any process that can reach the
 * configured database (e.g. a replicated Postgres instance behind several
 * extension nodes), the same way meta data already does via {@link
 * EntityDatabaseClient}.
 *
 * <p><strong>NOT for production use</strong>, for the same reason as {@link
 * FileKeyEncryptionService} and {@link InMemoryKeyEncryptionService}: KEK
 * material still sits in plaintext (in a database row rather than a file),
 * not behind a KMS/HSM boundary. Prefer a real KMS/HSM client wherever one is
 * available; use this only where none is, and a shared, restart-durable KEK
 * store is still needed.
 */
public final class DatabaseKeyEncryptionService implements KeyEncryptionService {

    private static final String WRAP_TRANSFORMATION = "AESWrapPad";
    private static final int KEY_ENCRYPTION_KEY_LENGTH_BYTES = CryptoAlgorithm.AES_256_GCM.keyLengthBytes();
    private static final String REGISTRY_ENTRY_ID = "key-encryption-keys";
    private static final String ACTIVE_KEY_ID_FIELD = "activeKeyId";
    private static final String KEY_ENCRYPTION_KEYS_FIELD = "keyEncryptionKeys";

    private final DatabaseSection databaseSection;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, byte[]> keyEncryptionKeys = new ConcurrentHashMap<>();
    private volatile String activeKeyId;

    /**
     * Loads existing KEK material from {@code databaseSection} if a {@value #REGISTRY_ENTRY_ID}
     * entry already exists there, otherwise {@link #rotate()}s a fresh one and persists it there.
     *
     * @param databaseSection the section KEK material is persisted to/loaded from
     * @throws NullPointerException if {@code databaseSection} is {@code null}
     */
    public DatabaseKeyEncryptionService(@NotNull final DatabaseSection databaseSection) {
        this.databaseSection = Asserts.assertNotNull(databaseSection, "@DatabaseKeyEncryptionService: databaseSection cannot be null");

        if (databaseSection.exists(REGISTRY_ENTRY_ID)) {
            load();
        } else {
            rotate();
        }
    }

    @Override
    public WrappedKey wrap(final DataEncryptionKey dataEncryptionKey) throws KeyWrapException {
        Asserts.assertNotNull(dataEncryptionKey, "@DatabaseKeyEncryptionService.wrap: dataEncryptionKey cannot be null");

        final String keyId = activeKeyId;
        final SecretKey kek = new SecretKeySpec(keyEncryptionKeys.get(keyId), "AES");

        try {
            final Cipher cipher = Cipher.getInstance(WRAP_TRANSFORMATION);
            cipher.init(Cipher.WRAP_MODE, kek);
            final byte[] wrapped = cipher.wrap(dataEncryptionKey.asSecretKey());
            return new WrappedKey(keyId, wrapped, WRAP_TRANSFORMATION, dataEncryptionKey.algorithm().id());
        } catch (final GeneralSecurityException e) {
            throw new KeyWrapException("@DatabaseKeyEncryptionService.wrap: failed to wrap data-encryption key", e);
        }
    }

    @Override
    public DataEncryptionKey unwrap(final WrappedKey wrappedKey) throws KeyWrapException {
        Asserts.assertNotNull(wrappedKey, "@DatabaseKeyEncryptionService.unwrap: wrappedKey cannot be null");

        final byte[] kekMaterial = keyEncryptionKeys.get(wrappedKey.keyEncryptionKeyId());
        if (kekMaterial == null) {
            throw new KeyWrapException(
                    "@DatabaseKeyEncryptionService.unwrap: unknown key-encryption-key id '"
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
            throw new KeyWrapException("@DatabaseKeyEncryptionService.unwrap: failed to unwrap data-encryption key", e);
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
        final JsonDocument document = databaseSection.findEntryById(REGISTRY_ENTRY_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "@DatabaseKeyEncryptionService: registry entry '" + REGISTRY_ENTRY_ID + "' disappeared between exists() and findEntryById()"
                ))
                .getDocument();
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

        final JsonDocument document = new JsonDocument()
                .append(ACTIVE_KEY_ID_FIELD, activeKeyId)
                .append(KEY_ENCRYPTION_KEYS_FIELD, keys);

        final DatabaseEntry entry = new DatabaseEntry(REGISTRY_ENTRY_ID, document);
        if (databaseSection.exists(REGISTRY_ENTRY_ID)) {
            databaseSection.update(entry);
        } else {
            databaseSection.insert(entry);
        }
    }
}
