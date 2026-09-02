package de.lino.cloud.plugin.security.keys.develop;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;
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
 * Database-backed {@link KeyEncryptionService}: same wrap/unwrap mechanics
 * as {@link FileKeyEncryptionService}, but key-encryption key (KEK)
 * material is persisted as a single {@link DatabaseEntry} in a {@link
 * DatabaseSection}, shared across every process reaching that database
 * instead of bound to one file. <strong>Not for production use</strong> -
 * KEK material still sits in plaintext, not behind a KMS/HSM.
 */
public final class DatabaseKeyEncryptionService implements KeyEncryptionService {

    /** JCA cipher transformation used to wrap/unwrap data-encryption keys under a key-encryption key. */
    private static final String WRAP_TRANSFORMATION = "AESWrapPad";

    /** Length, in bytes, of each freshly generated key-encryption key's material. */
    private static final int KEY_ENCRYPTION_KEY_LENGTH_BYTES = CryptoAlgorithm.AES_256_GCM.keyLengthBytes();

    /** Primary key of the single {@link DatabaseEntry} the whole key-encryption-key registry is persisted under. */
    private static final String REGISTRY_ENTRY_ID = "key-encryption-keys";

    /** {@link JsonDocument} field name holding the currently active key-encryption key's id. */
    private static final String ACTIVE_KEY_ID_FIELD = "activeKeyId";

    /** {@link JsonDocument} field name holding the map of every retained key-encryption key, keyed by id. */
    private static final String KEY_ENCRYPTION_KEYS_FIELD = "keyEncryptionKeys";

    /** The section {@link #REGISTRY_ENTRY_ID} is persisted to/loaded from. */
    private final DatabaseSection databaseSection;

    /** Source of the random key material {@link #rotate()} draws from. */
    private final SecureRandom secureRandom = new SecureRandom();

    /** Every retained key-encryption key's raw material, keyed by its id - superseded keys are kept so old wrapped data stays unwrappable. */
    private final Map<String, byte[]> keyEncryptionKeys = new ConcurrentHashMap<>();

    /** Id of the key-encryption key currently used for new {@link #wrap} calls. */
    private volatile String activeKeyId;

    /**
     * Loads existing KEK material from {@code databaseSection} if a {@value #REGISTRY_ENTRY_ID}
     * entry already exists there, otherwise {@link #rotate()}s a fresh one and persists it there.
     *
     * @param databaseSection the section KEK material is persisted to/loaded from
     * @throws NullPointerException if {@code databaseSection} is {@code null}
     */
    public DatabaseKeyEncryptionService(@NotNull final DatabaseSection databaseSection) {
        this.databaseSection = Asserts.requireNonNull(databaseSection, "@DatabaseKeyEncryptionService: databaseSection cannot be null");

        if (databaseSection.exists(REGISTRY_ENTRY_ID)) {
            load();
        } else {
            rotate();
        }
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
        Asserts.requireNonNull(dataEncryptionKey, "@DatabaseKeyEncryptionService.wrap: dataEncryptionKey cannot be null");

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
        Asserts.requireNonNull(wrappedKey, "@DatabaseKeyEncryptionService.unwrap: wrappedKey cannot be null");

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

    /** @return the id of the currently active key-encryption key */
    @Override
    public String activeKeyEncryptionKeyId() {
        return activeKeyId;
    }

    /**
     * Generates a fresh KEK, activates it, and persists the updated registry.
     * Previously wrapped keys stay unwrappable - their KEK id is retained.
     *
     * @return the new key's id
     */
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

    /**
     * Loads {@link #REGISTRY_ENTRY_ID}'s document from {@link #databaseSection}
     * and populates {@link #activeKeyId}/{@link #keyEncryptionKeys} from it.
     *
     * @throws IllegalStateException if the registry entry vanishes between the constructor's {@code exists} check and this read
     */
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

    /**
     * Serializes {@link #activeKeyId}/{@link #keyEncryptionKeys} (each key's
     * material base64-encoded) and inserts or updates {@link
     * #REGISTRY_ENTRY_ID}'s document in {@link #databaseSection} with it.
     */
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
