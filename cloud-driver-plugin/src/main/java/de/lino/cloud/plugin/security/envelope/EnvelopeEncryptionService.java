package de.lino.cloud.plugin.security.envelope;

import de.lino.cloud.api.security.crypto.AeadEncryptionService;
import de.lino.cloud.api.security.crypto.StreamingAeadEncryptionService;
import de.lino.cloud.plugin.security.crypto.AesGcmEncryptionService;
import de.lino.cloud.plugin.security.crypto.ChunkedAesGcmStreamingService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.DataEncryptionKey;
import de.lino.cloud.plugin.security.keys.develop.DataEncryptionKeyGenerator;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.keys.WrappedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import de.lino.cloud.api.utility.Asserts;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Envelope-encryption facade: a fresh data-encryption key (DEK) protects
 * each payload, and the DEK itself is wrapped by a key-encryption key (KEK)
 * held by a {@link KeyEncryptionService}-backed KMS/HSM. The DEK's raw
 * material is zeroed via {@link DataEncryptionKey#destroy()} as soon as
 * each operation completes.
 *
 * <p>Two entry-point pairs share that scheme: {@link #encrypt}/{@link #decrypt} for payloads that
 * fit in memory as one {@code byte[]} (schema version {@value #SCHEMA_VERSION}), and {@link
 * #encryptStream}/{@link #decryptStream} for payloads of any size (schema version {@value
 * #STREAMING_SCHEMA_VERSION}), which chunk the payload through a {@link
 * StreamingAeadEncryptionService} so memory use stays O(chunk size). The DEK/KEK handling is
 * identical either way - only the payload cipher invocation differs. For the streaming pair the
 * DEK's own {@code byte[]} is destroyed before the entry point returns; the {@link SecretKey}
 * copy JCA requires lives on inside the returned stream until it is closed.
 */
public final class EnvelopeEncryptionService {

    /** Version tag stamped into every {@link EnvelopeEncryptedPayload} produced by {@link #encrypt}. */
    private static final int SCHEMA_VERSION = 1;

    /**
     * Version tag opening every {@link #encryptStream} ciphertext - the first 4 bytes of a stored
     * object, letting a reader dispatch between the one-shot and streaming layouts (see {@code
     * StoredFileContentChannel}).
     */
    public static final int STREAMING_SCHEMA_VERSION = 2;

    /** Hard upper bound on any length prefix inside a streaming header - the header is parsed before anything is authenticated, so a forged prefix must not be able to demand a huge allocation. */
    private static final int MAX_STREAMING_HEADER_FIELD_LENGTH_BYTES = 1 << 16;

    /** Generates the fresh data-encryption key used by each {@link #encrypt} call. */
    private final DataEncryptionKeyGenerator dataEncryptionKeyGenerator;

    /** Encrypts/decrypts payloads under a data-encryption key. */
    private final AeadEncryptionService aeadEncryptionService;

    /** Chunk-encrypts/-decrypts streamed payloads under a data-encryption key. */
    private final StreamingAeadEncryptionService streamingAeadEncryptionService;

    /** Wraps/unwraps data-encryption keys under the active key-encryption key. */
    private final KeyEncryptionService keyEncryptionService;

    /** Algorithm used to generate each fresh data-encryption key. */
    private final CryptoAlgorithm dataEncryptionKeyAlgorithm;

    /**
     * @param dataEncryptionKeyGenerator generates the fresh DEK for each {@link #encrypt} call
     * @param aeadEncryptionService encrypts/decrypts payloads under a DEK
     * @param streamingAeadEncryptionService chunk-encrypts/-decrypts streamed payloads under a DEK
     * @param keyEncryptionService wraps/unwraps DEKs via the KMS/HSM
     * @param dataEncryptionKeyAlgorithm the algorithm freshly generated DEKs use
     * @throws NullPointerException if any argument is {@code null}
     */
    public EnvelopeEncryptionService(@NotNull final DataEncryptionKeyGenerator dataEncryptionKeyGenerator,
                                      @NotNull final AeadEncryptionService aeadEncryptionService,
                                      @NotNull final StreamingAeadEncryptionService streamingAeadEncryptionService,
                                      @NotNull final KeyEncryptionService keyEncryptionService,
                                      @NotNull final CryptoAlgorithm dataEncryptionKeyAlgorithm) {
        this.dataEncryptionKeyGenerator = Asserts.requireNonNull(
                dataEncryptionKeyGenerator, "@EnvelopeEncryptionService: dataEncryptionKeyGenerator cannot be null"
        );
        this.aeadEncryptionService = Asserts.requireNonNull(
                aeadEncryptionService, "@EnvelopeEncryptionService: aeadEncryptionService cannot be null"
        );
        this.streamingAeadEncryptionService = Asserts.requireNonNull(
                streamingAeadEncryptionService, "@EnvelopeEncryptionService: streamingAeadEncryptionService cannot be null"
        );
        this.keyEncryptionService = Asserts.requireNonNull(
                keyEncryptionService, "@EnvelopeEncryptionService: keyEncryptionService cannot be null"
        );
        this.dataEncryptionKeyAlgorithm = Asserts.requireNonNull(
                dataEncryptionKeyAlgorithm, "@EnvelopeEncryptionService: dataEncryptionKeyAlgorithm cannot be null"
        );
    }

    /**
     * Same as the five-argument constructor with a default {@link ChunkedAesGcmStreamingService}
     * built for {@code dataEncryptionKeyAlgorithm} - kept so existing four-argument callers stay
     * source-compatible.
     *
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
        this(dataEncryptionKeyGenerator, aeadEncryptionService,
                new ChunkedAesGcmStreamingService(Asserts.requireNonNull(
                        dataEncryptionKeyAlgorithm, "@EnvelopeEncryptionService: dataEncryptionKeyAlgorithm cannot be null")),
                keyEncryptionService, dataEncryptionKeyAlgorithm);
    }

    /**
     * Convenience constructor: a fresh {@link DataEncryptionKeyGenerator}, {@link
     * AesGcmEncryptionService}, and {@link ChunkedAesGcmStreamingService}, AES-256-GCM DEKs, and
     * the given KMS/HSM.
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

    /**
     * A {@link #encryptStream} result: the ciphertext stream and its exact total length, known
     * up front so a consumer needing a {@code Content-Length} (e.g. {@code
     * ObjectStorageService#putObject(String, InputStream, long)}) can be handed both without the
     * ciphertext ever being materialized.
     *
     * @param ciphertextStream serves the streaming header followed by the chunk frames; closing it closes the plaintext source
     * @param ciphertextLength the exact number of bytes {@code ciphertextStream} will yield
     */
    public record StreamingEncryption(@NotNull InputStream ciphertextStream, long ciphertextLength) {
    }

    /**
     * Streaming counterpart of {@link #encrypt}: encrypts {@code plaintext} under a freshly
     * generated DEK - wrapped via the very same {@link KeyEncryptionService} call - but chunk by
     * chunk through the {@link StreamingAeadEncryptionService}, so memory use is O(chunk size)
     * rather than O(payload size). The wrapped-DEK header (schema version {@value
     * #STREAMING_SCHEMA_VERSION}, the {@link WrappedKey}'s components, the payload algorithm id)
     * is served once at the start of the returned stream; the chunk frames follow. The DEK's raw
     * material is destroyed before this method returns.
     *
     * <p>Nothing is read from {@code plaintext} until the returned stream is drained - the only
     * eager work is generating and wrapping the DEK.
     *
     * @param plaintext the plaintext to encrypt, read to EOF as the returned stream is drained
     * @param plaintextLength {@code plaintext}'s exact total length, in bytes
     * @param associatedDataPrefix caller data (e.g. protocol tag and record id) bound into every
     *                             chunk's associated data; may be {@code null} or empty
     * @return the ciphertext stream and its exact total length
     * @throws NullPointerException if {@code plaintext} is {@code null}
     * @throws IllegalArgumentException if {@code plaintextLength} is negative
     * @throws KeyWrapException if wrapping the freshly generated data-encryption key fails
     */
    @NotNull
    public StreamingEncryption encryptStream(@NotNull final InputStream plaintext, final long plaintextLength,
                                             @Nullable final byte[] associatedDataPrefix) throws KeyWrapException {
        Asserts.requireNonNull(plaintext, "@EnvelopeEncryptionService.encryptStream: plaintext cannot be null");

        final DataEncryptionKey dataEncryptionKey = this.dataEncryptionKeyGenerator.generate(this.dataEncryptionKeyAlgorithm);
        final SecretKey secretKey;
        final WrappedKey wrappedKey;
        try {
            // SecretKeySpec copies the material, so the copy survives the destroy() below and
            // lives exactly as long as the returned stream needs it.
            secretKey = dataEncryptionKey.asSecretKey();
            wrappedKey = this.keyEncryptionService.wrap(dataEncryptionKey);
        } finally {
            dataEncryptionKey.destroy();
        }

        final byte[] header = serializeStreamingHeader(wrappedKey, this.dataEncryptionKeyAlgorithm.id());
        final InputStream chunkFrames = this.streamingAeadEncryptionService.encryptingInputStream(plaintext, secretKey, associatedDataPrefix);
        final long totalLength = header.length + this.streamingAeadEncryptionService.encryptedLength(plaintextLength);
        return new StreamingEncryption(new SequenceInputStream(new ByteArrayInputStream(header), chunkFrames), totalLength);
    }

    /**
     * Reverses {@link #encryptStream}: reads the streaming header from {@code ciphertext}, unwraps
     * its DEK via the KMS/HSM, and returns a stream serving the verified plaintext - each chunk's
     * authentication tag is checked before any of that chunk's bytes are served, and the stream
     * fails closed on truncation (see {@link StreamingAeadEncryptionService}). The DEK's raw
     * material is destroyed before this method returns; closing the returned stream closes {@code
     * ciphertext}.
     *
     * @param ciphertext the ciphertext, positioned at the schema-version tag (i.e. at offset 0 of
     *                    what {@link #encryptStream} produced)
     * @param associatedDataPrefix the same prefix the encrypting side bound in - a mismatch fails
     *                             authentication; may be {@code null} or empty
     * @return the plaintext stream - later chunks' authentication failures surface from its
     *     {@code read} calls as {@link IOException}s caused by {@link AuthenticationFailedException}
     * @throws NullPointerException if {@code ciphertext} is {@code null}
     * @throws KeyWrapException if unwrapping the header's data-encryption key fails
     * @throws AuthenticationFailedException if the header is truncated, its schema version or
     *     algorithm id is unknown, or the first chunk fails verification
     * @throws IOException if reading {@code ciphertext} fails
     */
    @NotNull
    public InputStream decryptStream(@NotNull final InputStream ciphertext, @Nullable final byte[] associatedDataPrefix)
            throws KeyWrapException, AuthenticationFailedException, IOException {
        Asserts.requireNonNull(ciphertext, "@EnvelopeEncryptionService.decryptStream: ciphertext cannot be null");

        final WrappedKey wrappedKey = deserializeStreamingHeader(ciphertext);
        final DataEncryptionKey dataEncryptionKey = this.keyEncryptionService.unwrap(wrappedKey);
        final SecretKey secretKey;
        try {
            secretKey = dataEncryptionKey.asSecretKey();
        } finally {
            dataEncryptionKey.destroy();
        }
        return this.streamingAeadEncryptionService.decryptingInputStream(ciphertext, secretKey, associatedDataPrefix);
    }

    /**
     * A {@link #issueStreamingContentKey} result: the raw key material (for a client that will
     * encrypt content itself) and the streaming header carrying the KEK-wrapped copy of the same
     * key - byte-identical to what {@link #encryptStream} would write, so an object a client
     * produces with this key (header verbatim, then chunk frames) is indistinguishable from a
     * server-encrypted one.
     *
     * @param header the serialized streaming header
     * @param rawKeyMaterial the unwrapped key material - the caller hands it out transiently and drops it
     */
    public record IssuedStreamingContentKey(byte[] header, byte[] rawKeyMaterial) {
    }

    /**
     * Issues a fresh content-encryption key for a payload this process will <b>not</b> encrypt
     * itself (a presigned, client-encrypted upload): generates and wraps a DEK exactly like
     * {@link #encryptStream}, but instead of encrypting anything returns the raw key material
     * alongside the streaming header. The internal {@link DataEncryptionKey} is destroyed before
     * returning; only the returned copy remains, owned by the caller.
     *
     * @return the issued key
     * @throws KeyWrapException if wrapping the freshly generated data-encryption key fails
     */
    @NotNull
    public IssuedStreamingContentKey issueStreamingContentKey() throws KeyWrapException {
        final DataEncryptionKey dataEncryptionKey = this.dataEncryptionKeyGenerator.generate(this.dataEncryptionKeyAlgorithm);
        final byte[] rawKeyMaterial;
        final WrappedKey wrappedKey;
        try {
            rawKeyMaterial = dataEncryptionKey.asSecretKey().getEncoded();
            wrappedKey = this.keyEncryptionService.wrap(dataEncryptionKey);
        } finally {
            dataEncryptionKey.destroy();
        }
        return new IssuedStreamingContentKey(serializeStreamingHeader(wrappedKey, this.dataEncryptionKeyAlgorithm.id()), rawKeyMaterial);
    }

    /**
     * Recovers the raw key material from a streaming header previously produced by {@link
     * #issueStreamingContentKey} (or {@link #encryptStream}), by unwrapping the header's wrapped
     * DEK via the KMS/HSM - the download-side counterpart of {@link #issueStreamingContentKey},
     * for handing a client the key to decrypt a fetched object locally.
     *
     * @param header the streaming header, exactly as originally serialized
     * @return the raw key material - the caller hands it out transiently and drops it
     * @throws NullPointerException if {@code header} is {@code null}
     * @throws KeyWrapException if unwrapping the header's data-encryption key fails
     * @throws AuthenticationFailedException if {@code header} is truncated or malformed
     */
    @NotNull
    public byte[] recoverStreamingContentKey(@NotNull final byte[] header) throws KeyWrapException, AuthenticationFailedException {
        Asserts.requireNonNull(header, "@EnvelopeEncryptionService.recoverStreamingContentKey: header cannot be null");
        final WrappedKey wrappedKey;
        try {
            wrappedKey = deserializeStreamingHeader(new ByteArrayInputStream(header));
        } catch (final IOException e) {
            // Reading from an in-memory array only fails by running out of bytes - malformed, not I/O.
            throw new AuthenticationFailedException(
                    "@EnvelopeEncryptionService.recoverStreamingContentKey: malformed streaming header", e
            );
        }
        final DataEncryptionKey dataEncryptionKey = this.keyEncryptionService.unwrap(wrappedKey);
        try {
            return dataEncryptionKey.asSecretKey().getEncoded();
        } finally {
            dataEncryptionKey.destroy();
        }
    }

    /**
     * Serializes the streaming header {@link #encryptStream} prepends to the chunk frames -
     * {@value #STREAMING_SCHEMA_VERSION}, then the {@link WrappedKey}'s components and the payload
     * algorithm id, every {@code byte[]}/{@code String} (UTF-8) length-prefixed with a 4-byte
     * {@code int} - the same idiom as {@code EnvelopeEncryptedPayloadCodec}'s one-shot layout,
     * which this header's fields deliberately mirror up to (and excluding) the payload itself.
     */
    private static byte[] serializeStreamingHeader(final WrappedKey wrappedKey, final String payloadAlgorithmId) {
        final ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteOutput)) {
            out.writeInt(STREAMING_SCHEMA_VERSION);
            writeHeaderBytes(out, wrappedKey.keyEncryptionKeyId().getBytes(StandardCharsets.UTF_8));
            writeHeaderBytes(out, wrappedKey.wrappedKeyMaterial());
            writeHeaderBytes(out, wrappedKey.wrapAlgorithm().getBytes(StandardCharsets.UTF_8));
            writeHeaderBytes(out, wrappedKey.dataEncryptionKeyAlgorithmId().getBytes(StandardCharsets.UTF_8));
            writeHeaderBytes(out, payloadAlgorithmId.getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            // Writing to an in-memory ByteArrayOutputStream never actually fails - the checked
            // signature is only DataOutputStream's, not a real failure mode here.
            throw new UncheckedIOException("@EnvelopeEncryptionService.serializeStreamingHeader: unexpected I/O failure", e);
        }
        return byteOutput.toByteArray();
    }

    /**
     * Reverses {@link #serializeStreamingHeader}, leaving {@code ciphertext} positioned at the
     * first chunk frame. The header is parsed before anything is authenticated, so it fails
     * closed: truncation, an unexpected schema version, an implausible field length, or an
     * unknown payload algorithm id all reject the stream.
     */
    private static WrappedKey deserializeStreamingHeader(final InputStream ciphertext) throws AuthenticationFailedException, IOException {
        // Deliberately not try-with-resources: closing the DataInputStream would close the
        // caller's ciphertext stream, which the returned plaintext stream still reads from.
        final DataInputStream in = new DataInputStream(ciphertext);
        try {
            final int schemaVersion = in.readInt();
            if (schemaVersion != STREAMING_SCHEMA_VERSION) {
                throw new AuthenticationFailedException(
                        "@EnvelopeEncryptionService.decryptStream: expected streaming schema version "
                                + STREAMING_SCHEMA_VERSION + " but found " + schemaVersion + " - stream rejected", null
                );
            }
            final String keyEncryptionKeyId = new String(readHeaderBytes(in), StandardCharsets.UTF_8);
            final byte[] wrappedKeyMaterial = readHeaderBytes(in);
            final String wrapAlgorithm = new String(readHeaderBytes(in), StandardCharsets.UTF_8);
            final String dataEncryptionKeyAlgorithmId = new String(readHeaderBytes(in), StandardCharsets.UTF_8);
            final String payloadAlgorithmId = new String(readHeaderBytes(in), StandardCharsets.UTF_8);
            // Only recorded for crypto agility today - both approved AES-GCM variants share nonce
            // and tag lengths, so the chunk layout is identical; an unknown id still fails closed.
            CryptoAlgorithm.fromId(payloadAlgorithmId);
            return new WrappedKey(keyEncryptionKeyId, wrappedKeyMaterial, wrapAlgorithm, dataEncryptionKeyAlgorithmId);
        } catch (final EOFException e) {
            throw new AuthenticationFailedException(
                    "@EnvelopeEncryptionService.decryptStream: stream ended inside the streaming header - stream rejected", e
            );
        } catch (final IllegalArgumentException e) {
            throw new AuthenticationFailedException(
                    "@EnvelopeEncryptionService.decryptStream: malformed streaming header - stream rejected", e
            );
        }
    }

    /** Writes one length-prefixed header field. */
    private static void writeHeaderBytes(final DataOutputStream out, final byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    /** Reads one length-prefixed header field, rejecting implausible lengths before allocating. */
    private static byte[] readHeaderBytes(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > MAX_STREAMING_HEADER_FIELD_LENGTH_BYTES) {
            throw new IllegalArgumentException("implausible streaming header field length " + length);
        }
        final byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }
}
