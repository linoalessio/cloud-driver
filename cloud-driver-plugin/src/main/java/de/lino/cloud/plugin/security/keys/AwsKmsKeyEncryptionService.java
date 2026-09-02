package de.lino.cloud.plugin.security.keys;

import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.security.keys.develop.DatabaseKeyEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.FileKeyEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;

import java.time.Instant;

/**
 * Production {@link KeyEncryptionService} backed by AWS KMS - unlike {@link
 * InMemoryKeyEncryptionService}/{@link FileKeyEncryptionService}/{@link
 * DatabaseKeyEncryptionService}, key-encryption key (KEK) material never
 * leaves AWS's own HSMs: {@link #wrap}/{@link #unwrap} delegate directly to
 * KMS's {@code Encrypt}/{@code Decrypt} operations rather than performing any
 * local AES-wrap cipher work, so this process only ever holds the unwrapped
 * {@link DataEncryptionKey} (as every implementation does) and the opaque
 * ciphertext blob KMS returns - never a raw KEK.
 *
 * <p><strong>Credentials/config resolution:</strong> this class takes a KMS
 * key id (or alias/ARN) and either a pre-built {@link KmsClient} or a {@link
 * Region} directly - it does not read {@code configuration.json} itself,
 * matching {@link DatabaseKeyEncryptionService}/{@link FileKeyEncryptionService}'s
 * own "caller resolves config, this class just takes the resolved value"
 * shape (this class is typically constructed before {@code CloudDriver} exists
 * at all, since it feeds into the {@code EnvelopeEncryptionService} that
 * {@code DefaultCloudDriver.setInstance} is built from - {@link
 * de.lino.cloud.api.CloudDriver#getConfiguration()} is not safely reachable
 * yet at that point). A caller wiring this in (e.g. {@code CloudBootstrap})
 * should resolve the region/key id from new {@code configuration.json} keys
 * (e.g. {@code "aws-kms-region"}/{@code "aws-kms-key-id"}), following the same
 * "optional key, {@code JsonDocument#contains}-checked" convention {@code
 * CloudUser}'s {@code "cloud-user-max-bytes-to-upload"} already uses - see
 * {@code CLAUDE.md}'s "Local dev secrets" section. The AWS access
 * key/secret-key pair itself is deliberately <strong>not</strong> read from
 * {@code configuration.json} at all - this class relies on the AWS SDK's own
 * default credential provider chain (environment variables, {@code
 * ~/.aws/credentials}, an EC2/ECS instance role, ...), the same way every
 * other AWS SDK integration is meant to resolve credentials, rather than
 * inventing a third place in this repo where a secret can be committed by
 * mistake.
 *
 * <p><strong>Rotation.</strong> AWS KMS's own automatic annual key rotation
 * (if enabled on a CMK) rotates backing material transparently under a fixed
 * key id/ARN - it never changes what {@link #activeKeyEncryptionKeyId()}
 * would report and isn't something this class needs to do anything for.
 * {@link #rotate()} instead provisions a genuinely new symmetric CMK (a real
 * {@code CreateKey} call) and switches {@link #wrap} to use it going forward,
 * the same "new KEK id, old ones stay unwrappable" contract the other three
 * implementations provide via a freshly generated local key - here, the new
 * "key id" is a real AWS-managed CMK. This requires the {@code kms:CreateKey}
 * IAM permission in addition to {@code kms:Encrypt}/{@code kms:Decrypt}.
 *
 * <p><strong>Not wired in as {@code CloudBootstrap}'s default</strong> - an
 * operator opts into this explicitly once AWS credentials/a KMS key are
 * actually provisioned for a deployment.
 */
public final class AwsKmsKeyEncryptionService implements KeyEncryptionService {

    /** Value recorded as {@link WrappedKey#wrapAlgorithm()} for every key this service wraps - KMS's Encrypt/Decrypt is opaque, so this is purely a label, never fed back into a JCA {@code Cipher}. */
    private static final String WRAP_ALGORITHM_LABEL = "AWS-KMS";

    /** The AWS KMS client used for every {@code Encrypt}/{@code Decrypt}/{@code CreateKey} call. */
    private final KmsClient kmsClient;

    /** Id (or alias/ARN) of the KMS customer master key currently used for new {@link #wrap} calls. */
    private volatile String activeKeyId;

    /**
     * @param kmsClient a fully configured KMS client (region/credentials already resolved by the caller)
     * @param keyId the id, alias (e.g. {@code alias/cloud-driver-kek}), or ARN of the KMS key to use for new {@link #wrap} calls
     * @throws NullPointerException if {@code kmsClient} or {@code keyId} is {@code null}
     */
    public AwsKmsKeyEncryptionService(@NotNull final KmsClient kmsClient, @NotNull final String keyId) {
        this.kmsClient = Asserts.requireNonNull(kmsClient, "@AwsKmsKeyEncryptionService: kmsClient cannot be null");
        this.activeKeyId = Asserts.requireNonNull(keyId, "@AwsKmsKeyEncryptionService: keyId cannot be null");
    }

    /**
     * Convenience constructor building a {@link KmsClient} for {@code region}
     * via the AWS SDK's default credential provider chain - see this class's
     * own Javadoc for why credentials are resolved this way rather than from
     * {@code configuration.json}.
     *
     * @param region the AWS region the KMS key lives in
     * @param keyId the id, alias, or ARN of the KMS key to use for new {@link #wrap} calls
     * @throws NullPointerException if {@code region} or {@code keyId} is {@code null}
     */
    public AwsKmsKeyEncryptionService(@NotNull final Region region, @NotNull final String keyId) {
        this(KmsClient.builder().region(Asserts.requireNonNull(region, "@AwsKmsKeyEncryptionService: region cannot be null")).build(), keyId);
    }

    /**
     * Wraps {@code dataEncryptionKey} via KMS {@code Encrypt} under the active key.
     *
     * @param dataEncryptionKey the key to wrap
     * @return the wrapped key
     * @throws NullPointerException if {@code dataEncryptionKey} is {@code null}
     * @throws KeyWrapException if the KMS call fails
     */
    @Override
    public WrappedKey wrap(final @NotNull DataEncryptionKey dataEncryptionKey) throws KeyWrapException {
        Asserts.requireNonNull(dataEncryptionKey, "@AwsKmsKeyEncryptionService.wrap: dataEncryptionKey cannot be null");

        final String keyId = activeKeyId;
        try {
            final EncryptResponse response = kmsClient.encrypt(EncryptRequest.builder()
                    .keyId(keyId)
                    .plaintext(SdkBytes.fromByteArray(dataEncryptionKey.asSecretKey().getEncoded()))
                    .build());

            return new WrappedKey(
                    response.keyId(),
                    response.ciphertextBlob().asByteArray(),
                    WRAP_ALGORITHM_LABEL,
                    dataEncryptionKey.algorithm().id()
            );
        } catch (final SdkException e) {
            throw new KeyWrapException("@AwsKmsKeyEncryptionService.wrap: KMS Encrypt call failed for key '" + keyId + "'", e);
        }
    }

    /**
     * Unwraps {@code wrappedKey} via KMS {@code Decrypt} - the ciphertext blob
     * KMS returned from {@link #wrap} is self-describing, so no local key
     * lookup by id is needed; {@code wrappedKey.keyEncryptionKeyId()} is
     * passed through as {@code DecryptRequest}'s expected key id purely as a
     * defense-in-depth check against a ciphertext being decrypted under a
     * different key than the one that produced it.
     *
     * @param wrappedKey the key to unwrap
     * @return the unwrapped key
     * @throws NullPointerException if {@code wrappedKey} is {@code null}
     * @throws KeyWrapException if the KMS call fails
     */
    @Override
    public DataEncryptionKey unwrap(final @NotNull WrappedKey wrappedKey) throws KeyWrapException {
        Asserts.requireNonNull(wrappedKey, "@AwsKmsKeyEncryptionService.unwrap: wrappedKey cannot be null");

        try {
            final DecryptResponse response = kmsClient.decrypt(DecryptRequest.builder()
                    .keyId(wrappedKey.keyEncryptionKeyId())
                    .ciphertextBlob(SdkBytes.fromByteArray(wrappedKey.wrappedKeyMaterial()))
                    .build());

            final CryptoAlgorithm dekAlgorithm = CryptoAlgorithm.fromId(wrappedKey.dataEncryptionKeyAlgorithmId());
            return new DataEncryptionKey(dekAlgorithm, response.plaintext().asByteArray());
        } catch (final SdkException e) {
            throw new KeyWrapException(
                    "@AwsKmsKeyEncryptionService.unwrap: KMS Decrypt call failed for key '" + wrappedKey.keyEncryptionKeyId() + "'", e
            );
        }
    }

    /** @return the id of the KMS key currently used for new {@link #wrap} calls */
    @Override
    public String activeKeyEncryptionKeyId() {
        return activeKeyId;
    }

    /**
     * Provisions a brand-new symmetric KMS customer master key (a real {@code
     * CreateKey} call, requiring the {@code kms:CreateKey} IAM permission) and
     * activates it for future {@link #wrap} calls. Previously wrapped keys
     * stay unwrappable - {@link #unwrap} never depends on {@link #activeKeyId},
     * only on the key id recorded in the {@link WrappedKey} itself.
     *
     * @return the newly created KMS key's id
     * @throws software.amazon.awssdk.core.exception.SdkException if the {@code CreateKey} call fails
     */
    @Override
    public synchronized String rotate() {
        final CreateKeyResponse response = kmsClient.createKey(CreateKeyRequest.builder()
                .keySpec(KeySpec.SYMMETRIC_DEFAULT)
                .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                .description("cloud-driver KEK, created by AwsKmsKeyEncryptionService#rotate on " + Instant.now())
                .build());

        final String keyId = response.keyMetadata().keyId();
        activeKeyId = keyId;
        return keyId;
    }
}
