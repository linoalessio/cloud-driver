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
 * Sends any {@link Serialized} domain meta - the database-driver-api base
 * class for persistable entities - through {@link EnvelopeEncryptionService}
 * generically: the same channel handles every meta subclass without a
 * type-specific encrypt/decrypt path. {@link Serialized#toByteArray()} and
 * {@link Serialized#fromByteArray(byte[], Class)} already give a JSON-based
 * wire format; this class adds confidentiality and authenticity on top via
 * AES-256-GCM envelope encryption.
 *
 * <p>The meta's type name and {@link Serialized#primaryKey() primary key}
 * are bound into the authenticated associated data (AAD, section 6 of the
 * security requirements: "protocol/version identifiers ... record
 * identifiers"), so a payload cannot be silently swapped for a different
 * meta or record in transit or storage - {@link #receive} rejects a
 * mismatch before doing any decryption work.
 *
 * <p>{@link #send} and {@link #receive} are generic per call, not per
 * instance, so a single channel handles heterogeneous meta types - the
 * shape needed by a global facade such as {@code CloudAPI}.
 */
public final class SecureEntityChannel {

    private static final String PROTOCOL_VERSION = "v1";

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
     * Serializes and envelope-encrypts {@code meta}, ready to hand to the
     * configured storage backend (e.g. as the payload {@code
     * EntityDatabaseClient} writes to a database).
     */
    @NotNull
    public <T extends Serialized> EnvelopeEncryptedPayload send(@NotNull final T entity) throws KeyWrapException {
        Asserts.requireNonNull(entity, "@SecureEntityChannel.send: meta cannot be null");

        final byte[] data = entity.toByteArray();
        final byte[] associatedData = associatedData(entity.getClass(), entity.primaryKey());
        return envelopeEncryptionService.encrypt(data, associatedData);
    }

    /**
     * Decrypts a payload produced by {@link #send(Serialized)} and
     * reconstructs the original meta, rejecting anything that fails
     * authentication or does not match {@code expectedType}.
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

    private static byte[] associatedData(final Class<?> type, final String primaryKey) {
        return (PROTOCOL_VERSION + ":" + type.getName() + ":" + primaryKey).getBytes(StandardCharsets.UTF_8);
    }
}
