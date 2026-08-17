package de.lino.cloud.core.security.envelope;

import de.lino.cloud.api.security.crypto.AeadEncryptionService;
import de.lino.cloud.core.security.crypto.AesGcmEncryptionService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.core.security.keys.DataEncryptionKeyGenerator;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Envelope-encryption facade tying together {@link DataEncryptionKeyGenerator},
 * an {@link AeadEncryptionService}, and a {@link KeyEncryptionService}-backed
 * KMS/HSM, per sections 4 and 13: a fresh data-encryption key (DEK) protects
 * each payload with AES-256-GCM, and the DEK itself is wrapped by a
 * key-encryption key (KEK) held by the KMS/HSM - "Envelope encryption SHALL
 * be used for high-value or highly sensitive data."
 *
 * <p>The DEK's raw material is zeroed via {@link DataEncryptionKey#destroy()}
 * as soon as each operation completes, whether it succeeds or fails.
 */
public final class EnvelopeEncryptionService {

    private static final int SCHEMA_VERSION = 1;

    private final DataEncryptionKeyGenerator dataEncryptionKeyGenerator;
    private final AeadEncryptionService aeadEncryptionService;
    private final KeyEncryptionService keyEncryptionService;
    private final CryptoAlgorithm dataEncryptionKeyAlgorithm;

    public EnvelopeEncryptionService(@NotNull final DataEncryptionKeyGenerator dataEncryptionKeyGenerator,
                                      @NotNull final AeadEncryptionService aeadEncryptionService,
                                      @NotNull final KeyEncryptionService keyEncryptionService,
                                      @NotNull final CryptoAlgorithm dataEncryptionKeyAlgorithm) {
        this.dataEncryptionKeyGenerator = Objects.requireNonNull(
                dataEncryptionKeyGenerator, "@EnvelopeEncryptionService: dataEncryptionKeyGenerator cannot be null"
        );
        this.aeadEncryptionService = Objects.requireNonNull(
                aeadEncryptionService, "@EnvelopeEncryptionService: aeadEncryptionService cannot be null"
        );
        this.keyEncryptionService = Objects.requireNonNull(
                keyEncryptionService, "@EnvelopeEncryptionService: keyEncryptionService cannot be null"
        );
        this.dataEncryptionKeyAlgorithm = Objects.requireNonNull(
                dataEncryptionKeyAlgorithm, "@EnvelopeEncryptionService: dataEncryptionKeyAlgorithm cannot be null"
        );
    }

    public EnvelopeEncryptionService(@NotNull final KeyEncryptionService keyEncryptionService) {
        this(new DataEncryptionKeyGenerator(), new AesGcmEncryptionService(), keyEncryptionService, CryptoAlgorithm.AES_256_GCM);
    }

    /**
     * Encrypts {@code plaintext} under a freshly generated DEK, then wraps
     * that DEK with the active key-encryption key.
     *
     * @param associatedData additional data to authenticate but not encrypt,
     *                       e.g. protocol/version identifiers, tenant or
     *                       record ids; may be {@code null} or empty
     */
    @NotNull
    public EnvelopeEncryptedPayload encrypt(@NotNull final byte[] plaintext, @Nullable final byte[] associatedData) throws KeyWrapException {
        final DataEncryptionKey dataEncryptionKey = dataEncryptionKeyGenerator.generate(dataEncryptionKeyAlgorithm);
        try {
            final var encryptedPayload = aeadEncryptionService.encrypt(plaintext, dataEncryptionKey.asSecretKey(), associatedData);
            final WrappedKey wrappedKey = keyEncryptionService.wrap(dataEncryptionKey);
            return new EnvelopeEncryptedPayload(SCHEMA_VERSION, wrappedKey, encryptedPayload);
        } finally {
            dataEncryptionKey.destroy();
        }
    }

    /**
     * Unwraps the envelope's DEK via the KMS/HSM and decrypts its payload,
     * verifying the authentication tag before returning any plaintext.
     */
    @NotNull
    public byte[] decrypt(@NotNull final EnvelopeEncryptedPayload envelope) throws KeyWrapException, AuthenticationFailedException {
        Objects.requireNonNull(envelope, "@EnvelopeEncryptionService.decrypt: envelope cannot be null");

        final DataEncryptionKey dataEncryptionKey = keyEncryptionService.unwrap(envelope.wrappedDataEncryptionKey());
        try {
            return aeadEncryptionService.decrypt(envelope.payload(), dataEncryptionKey.asSecretKey());
        } finally {
            dataEncryptionKey.destroy();
        }
    }
}
