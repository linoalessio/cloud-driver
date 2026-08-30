package de.lino.cloud.plugin.security.entity;

import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import de.lino.cloud.api.utility.Asserts;

/**
 * Sends any {@link Serialized} domain entity through {@link
 * EnvelopeEncryptionService}, generically across entity types. Binds the
 * entity's type name and {@link Serialized#primaryKey() primary key} into
 * the authenticated associated data, so a payload can't be silently swapped
 * for a different entity/record - {@link #receive} rejects a mismatch
 * before decrypting.
 */
public final class SecureEntityChannel {

    /** Prefix tag baked into every associated-data value, so a future format change can be distinguished from this one. */
    private static final String PROTOCOL_VERSION = "v1";

    /** Performs the actual envelope encryption/decryption {@link #send}/{@link #receive} wrap. */
    private final EnvelopeEncryptionService envelopeEncryptionService;

    /**
     * @param envelopeEncryptionService the envelope-encryption service backing {@link #send}/{@link #receive}
     * @throws NullPointerException if {@code envelopeEncryptionService} is {@code null}
     */
    public SecureEntityChannel(@NotNull final EnvelopeEncryptionService envelopeEncryptionService) {
        this.envelopeEncryptionService = Asserts.requireNonNull(
                envelopeEncryptionService, "@SecureEntityChannel: envelopeEncryptionService cannot be null"
        );
    }

    /**
     * Serializes and envelope-encrypts {@code entity}, ready for the
     * configured storage backend.
     *
     * @param entity the entity to encrypt
     * @return the resulting envelope
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws KeyWrapException if wrapping the data-encryption key fails
     */
    @NotNull
    public <T extends Serialized> EnvelopeEncryptedPayload send(@NotNull final T entity) throws KeyWrapException {
        Asserts.requireNonNull(entity, "@SecureEntityChannel.send: meta cannot be null");

        final byte[] data = entity.toByteArray();
        final byte[] associatedData = associatedData(entity.getClass(), entity.primaryKey());
        return envelopeEncryptionService.encrypt(data, associatedData);
    }

    /**
     * Decrypts a payload produced by {@link #send} and reconstructs the
     * original entity, rejecting anything that fails authentication or does
     * not match {@code expectedType}.
     *
     * @param envelope the envelope to decrypt
     * @param expectedType the entity type the envelope must carry
     * @return the reconstructed entity
     * @throws NullPointerException if {@code envelope} or {@code expectedType} is {@code null}
     * @throws IllegalArgumentException if the envelope's associated data does not match {@code expectedType}
     * @throws KeyWrapException if unwrapping the data-encryption key fails
     * @throws AuthenticationFailedException if authentication tag verification fails
     */
    @NotNull
    public <T extends Serialized> T receive(@NotNull final EnvelopeEncryptedPayload envelope, @NotNull final Class<T> expectedType)
            throws KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(envelope, "@SecureEntityChannel.receive: envelope cannot be null");
        Asserts.requireNonNull(expectedType, "@SecureEntityChannel.receive: expectedType cannot be null");

        final String associatedData = new String(envelope.payload().associatedData(), StandardCharsets.UTF_8);
        final String typeNamePrefix = PROTOCOL_VERSION + ":" + expectedType.getName() + ":";
        if (!associatedData.startsWith(typeNamePrefix)) {
            throw new IllegalArgumentException(
                    "@SecureEntityChannel.receive: expected meta type " + expectedType.getName()
                            + " but envelope carries '" + associatedData + "'"
            );
        }

        final byte[] data = envelopeEncryptionService.decrypt(envelope);
        return Serialized.fromByteArray(data, expectedType);
    }

    /**
     * Builds the authenticated associated data binding a payload to one
     * entity type and primary key: {@code "<PROTOCOL_VERSION>:<type name>:<primary key>"}.
     *
     * @param type the entity type
     * @param primaryKey the entity's primary key
     * @return the associated-data bytes, UTF-8 encoded
     */
    private static byte[] associatedData(final Class<?> type, final String primaryKey) {
        return (PROTOCOL_VERSION + ":" + type.getName() + ":" + primaryKey).getBytes(StandardCharsets.UTF_8);
    }
}
