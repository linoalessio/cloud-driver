package de.lino.cloud.plugin.s3storage;

import de.lino.cloud.api.s3storage.ContentKeyService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.security.crypto.ChunkedAesGcmStreamingService;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import org.jetbrains.annotations.NotNull;

/**
 * Production {@link ContentKeyService}, backed by the deployment's one {@link
 * EnvelopeEncryptionService} - issued keys are wrapped under the very same KEK protecting every
 * server-encrypted payload, and the associated-data prefix/chunk parameters come from the same
 * {@link StoredFileContentChannel}/{@link ChunkedAesGcmStreamingService} conventions, so an
 * object a presigned-upload client encrypts with an issued key is byte-identical in layout to
 * one {@code StoredFileContentChannel#sendStream} produces and decrypts through the exact same
 * receive path.
 */
public final class StreamingContentKeyService implements ContentKeyService {

    /** Issues/recovers the wrapped keys - the same instance protecting every server-encrypted payload. */
    private final EnvelopeEncryptionService envelopeEncryptionService;

    /** Supplies the chunk size and the exact ciphertext-length formula {@link #objectLength} promises. */
    private final ChunkedAesGcmStreamingService streamingService;

    /**
     * Uses the default {@link ChunkedAesGcmStreamingService} (AES-256-GCM, 1 MiB chunks) - the
     * same defaults {@link EnvelopeEncryptionService}'s own convenience constructors use, keeping
     * {@link #chunkSizeBytes()}/{@link #objectLength} consistent with what a server-side encrypt
     * of the same content would produce.
     *
     * @param envelopeEncryptionService the deployment's envelope-encryption service
     * @throws NullPointerException if {@code envelopeEncryptionService} is {@code null}
     */
    public StreamingContentKeyService(@NotNull final EnvelopeEncryptionService envelopeEncryptionService) {
        this(envelopeEncryptionService, new ChunkedAesGcmStreamingService());
    }

    /**
     * @param envelopeEncryptionService the deployment's envelope-encryption service
     * @param streamingService supplies the chunk size/length formula clients must encrypt against
     * @throws NullPointerException if either argument is {@code null}
     */
    public StreamingContentKeyService(@NotNull final EnvelopeEncryptionService envelopeEncryptionService,
                                      @NotNull final ChunkedAesGcmStreamingService streamingService) {
        this.envelopeEncryptionService = Asserts.requireNonNull(
                envelopeEncryptionService, "@StreamingContentKeyService: envelopeEncryptionService cannot be null"
        );
        this.streamingService = Asserts.requireNonNull(
                streamingService, "@StreamingContentKeyService: streamingService cannot be null"
        );
    }

    /** {@inheritDoc} Delegates to {@link EnvelopeEncryptionService#issueStreamingContentKey()}. */
    @NotNull
    @Override
    public IssuedContentKey issueContentKey() throws KeyWrapException {
        final EnvelopeEncryptionService.IssuedStreamingContentKey issued = this.envelopeEncryptionService.issueStreamingContentKey();
        return new IssuedContentKey(issued.header(), issued.rawKeyMaterial());
    }

    /** {@inheritDoc} Delegates to {@link EnvelopeEncryptionService#recoverStreamingContentKey(byte[])}. */
    @Override
    public byte[] recoverContentKey(@NotNull final byte[] header) throws KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(header, "@StreamingContentKeyService.recoverContentKey: header cannot be null");
        return this.envelopeEncryptionService.recoverStreamingContentKey(header);
    }

    /** {@inheritDoc} Delegates to {@link StoredFileContentChannel#streamingAssociatedDataPrefixString(String)}. */
    @NotNull
    @Override
    public String associatedDataPrefix(@NotNull final String fileId) {
        return StoredFileContentChannel.streamingAssociatedDataPrefixString(fileId);
    }

    /** {@inheritDoc} */
    @Override
    public int chunkSizeBytes() {
        return this.streamingService.chunkSizeBytes();
    }

    /** {@inheritDoc} {@code headerLengthBytes} plus {@link ChunkedAesGcmStreamingService#encryptedLength(long)}. */
    @Override
    public long objectLength(final int headerLengthBytes, final long plaintextLength) {
        if (headerLengthBytes < 0) {
            throw new IllegalArgumentException(
                    "@StreamingContentKeyService.objectLength: headerLengthBytes cannot be negative, got " + headerLengthBytes
            );
        }
        return headerLengthBytes + this.streamingService.encryptedLength(plaintextLength);
    }
}
