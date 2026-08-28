package de.lino.cloud.plugin.security.envelope;

import de.lino.cloud.api.security.crypto.AeadEncryptionService;
import de.lino.cloud.plugin.security.crypto.AesGcmEncryptionService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.plugin.security.keys.DataEncryptionKeyGenerator;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import de.lino.cloud.api.utility.Asserts;

/**
 * Envelope-encryption facade: a fresh data-encryption key (DEK) protects
 * each payload, and the DEK itself is wrapped by a key-encryption key (KEK)
 * held by a {@link KeyEncryptionService}-backed KMS/HSM. The DEK's raw
 * material is zeroed via {@link DataEncryptionKey#destroy()} as soon as
 * each operation completes.
 */
public final class EnvelopeEncryptionService {

    private static final int SCHEMA_VERSION = 1;

    private final DataEncryptionKeyGenerator dataEncryptionKeyGenerator;
    private final AeadEncryptionService aeadEncryptionService;
    private final KeyEncryptionService keyEncryptionService;
    private final CryptoAlgorithm dataEncryptionKeyAlgorithm;

    /**
     * @param dataEncryptionKeyGenerator generates the fresh DEK for each {@link #encrypt} call
     * @param aeadEncryptionService encrypts/decrypts payloads under a DEK
     * @param keyEncryptionService wraps/unwraps DEKs via the KMS/HSM
     * @param dataEncryptionKeyAlgorithm the algorithm freshly generated DEKs use
     * @throws NullPointerException if any argument is {@code null}
     */
    public EnvelopeEncryptionService(@NotNull final DataEncryptionKeyGenerator dataEncryptionKeyGenerator,
                                      @NotNull final AeadEncryptionService aeadEncryptionService,
                                      @NotNull final KeyEncryptionService keyEncryptionService,
                                      @NotNull final CryptoAlgorithm dataEncryptionKeyAlgorithm) {
        this.dataEncryptionKeyGenerator = Asserts.requireNonNull(
                dataEncryptionKeyGenerator, "@EnvelopeEncryptionService: dataEncryptionKeyGenerator cannot be null"
        );
        this.aeadEncryptionService = Asserts.requireNonNull(
                aeadEncryptionService, "@EnvelopeEncryptionService: aeadEncryptionService cannot be null"
        );
        this.keyEncryptionService = Asserts.requireNonNull(
                keyEncryptionService, "@EnvelopeEncryptionService: keyEncryptionService cannot be null"
        );
        this.dataEncryptionKeyAlgorithm = Asserts.requireNonNull(
                dataEncryptionKeyAlgorithm, "@EnvelopeEncryptionService: dataEncryptionKeyAlgorithm cannot be null"
        );
    }

    /**
     * Convenience constructor: a fresh {@link DataEncryptionKeyGenerator} and
     * {@link AesGcmEncryptionService}, AES-256-GCM DEKs, and the given KMS/HSM.
     *
     * @param keyEncryptionService wraps/unwraps DEKs via the KMS/HSM
     * @throws NullPointerException if {@code keyEncryptionService} is {@code null}
     */
    public EnvelopeEncryptionService(@NotNull final KeyEncryptionService keyEncryptionService) {
        this(new DataEncryptionKeyGenerator(), new AesGcmEncryptionService(), keyEncryptionService, CryptoAlgorithm.AES_256_GCM);
    }

    /**
     * Encrypts {@code plaintext} under a freshly generated DEK, then wraps
     * that DEK with the active key-encryption key.
     *
     * @param plaintext the bytes to encrypt
     * @param associatedData additional data to authenticate but not encrypt,
     *                       e.g. protocol/version identifiers, tenant or
     *                       record ids; may be {@code null} or empty
     * @return the resulting envelope, carrying both the wrapped DEK and the encrypted payload
     * @throws NullPointerException if {@code plaintext} is {@code null}
     * @throws KeyWrapException if wrapping the freshly generated data-encryption key fails
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
     *
     * @param envelope the envelope to decrypt, as produced by {@link #encrypt}
     * @return the recovered plaintext
     * @throws NullPointerException if {@code envelope} is {@code null}
     * @throws KeyWrapException if unwrapping the envelope's data-encryption key fails
     * @throws AuthenticationFailedException if authentication tag verification fails
     */
    @NotNull
    public byte[] decrypt(@NotNull final EnvelopeEncryptedPayload envelope) throws KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(envelope, "@EnvelopeEncryptionService.decrypt: envelope cannot be null");

        final DataEncryptionKey dataEncryptionKey = keyEncryptionService.unwrap(envelope.wrappedDataEncryptionKey());
        try {
            return aeadEncryptionService.decrypt(envelope.payload(), dataEncryptionKey.asSecretKey());
        } finally {
            dataEncryptionKey.destroy();
        }
    }
}
