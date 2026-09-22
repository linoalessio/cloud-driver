package de.lino.cloud.plugin.s3storage;

import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.security.crypto.EncryptedPayload;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.WrappedKey;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * (De)serializes an {@link EnvelopeEncryptedPayload} to/from a compact binary layout, so it can be
 * written to/read from an {@link ObjectStorageService} object.
 *
 * <p><b>Why this exists at all - not simply redirecting an already-existing ciphertext to S3.</b>
 * A naive design might have assumed the same ciphertext bytes a {@code StoredFile}'s
 * {@code contentBase64} would otherwise have carried are already produced somewhere on the
 * existing persistence path and can simply be redirected to S3. In reality, {@code
 * SecureEntityChannel} envelope-encrypts an entity's <em>entire</em> serialized JSON (via {@code
 * Serialized#toByteArray()}) as one opaque blob - there is no isolated "ciphertext of just this
 * file's content bytes" produced anywhere in that pipeline to reuse. {@code DefaultFileFactory}
 * instead calls {@code EnvelopeEncryptionService#encrypt}/{@code #decrypt} <b>directly</b> on a
 * file's raw content bytes (the exact same AES-256-GCM/DEK-KEK scheme {@code SecureEntityChannel}
 * uses, just invoked a second time, independently, on a narrower input) - which is the source of
 * the {@link EnvelopeEncryptedPayload} this class serializes for S3 s3storage.
 *
 * <p>Plain Gson serialization (the convention used for every {@code Serialized} entity) was
 * deliberately not reused here: Gson has no built-in {@code byte[]}-to-base64 adapter in this
 * codebase (see {@code StoredFile}'s own Javadoc on why {@code contentBase64} exists at all), so a
 * Gson-serialized {@link EnvelopeEncryptedPayload} would reintroduce exactly the "base64 tax" this
 * whole S3 migration exists to remove. This format instead writes each {@code byte[]}/{@code
 * String} component length-prefixed and raw - the same "length-prefixed binary, no JSON/base64"
 * idiom {@code cloud-driver-extensions-backup}'s {@code DatabaseBackupScheduler} already uses for
 * its own per-row export format.
 */
final class EnvelopeEncryptedPayloadCodec {

    /**
     * Largest a metadata field's length prefix may claim to be. The key-encryption-key id, the
     * wrapped key material, the two algorithm ids, the nonce and the associated data are all key
     * ids, algorithm names or a few hundred bytes of wrapped key - none comes anywhere near this.
     *
     * <p>The bound exists because the length is read straight out of the object being parsed,
     * before anything in it has been authenticated, and a presigned upload lets an ordinary
     * account write arbitrary bytes at its own object key: without it a crafted prefix turns one
     * download into a multi-gigabyte allocation, and a negative one throws past this class's own
     * IO-exception contract. The payload's ciphertext is deliberately <em>not</em> bounded by this
     * value - it carries the file's whole content, so it is bounded by the stored object's own
     * length instead.
     */
    private static final int MAX_METADATA_FIELD_LENGTH_BYTES = 1 << 16;

    /** Not instantiable - every method is static. */
    private EnvelopeEncryptedPayloadCodec() {
    }

    /**
     * Serializes {@code envelope} into the compact binary layout this class reads back via {@link
     * #deserialize(byte[])}: {@code schemaVersion}, then each of {@code
     * WrappedKey}'s/{@code EncryptedPayload}'s components, every {@code byte[]}/{@code String}
     * (UTF-8 encoded) length-prefixed with a 4-byte {@code int}.
     *
     * @param envelope the envelope to serialize
     * @return the serialized bytes, ready to hand to {@code ObjectStorageService#putObject}
     * @throws NullPointerException if {@code envelope} is {@code null}
     */
    @NotNull
    static byte[] serialize(@NotNull final EnvelopeEncryptedPayload envelope) {
        final WrappedKey wrappedKey = envelope.wrappedDataEncryptionKey();
        final EncryptedPayload payload = envelope.payload();

        final ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteOutput)) {
            out.writeInt(envelope.schemaVersion());
            writeString(out, wrappedKey.keyEncryptionKeyId());
            writeBytes(out, wrappedKey.wrappedKeyMaterial());
            writeString(out, wrappedKey.wrapAlgorithm());
            writeString(out, wrappedKey.dataEncryptionKeyAlgorithmId());
            writeString(out, payload.algorithmId());
            writeBytes(out, payload.nonce());
            writeBytes(out, payload.ciphertext());
            writeBytes(out, payload.associatedData());
        } catch (final IOException e) {
            // Writing to an in-memory ByteArrayOutputStream never actually fails - the checked
            // signature is only DataOutputStream's, not a real failure mode here.
            throw new UncheckedIOException("@EnvelopeEncryptedPayloadCodec.serialize: unexpected I/O failure", e);
        }
        return byteOutput.toByteArray();
    }

    /**
     * Reverses {@link #serialize(EnvelopeEncryptedPayload)}.
     *
     * @param bytes the serialized bytes, as read back from {@code ObjectStorageService#getObject}
     * @return the reconstructed envelope
     * @throws NullPointerException if {@code bytes} is {@code null}
     * @throws ObjectStorageException if {@code bytes} is not validly-formed, e.g. truncated or corrupted
     */
    @NotNull
    static EnvelopeEncryptedPayload deserialize(@NotNull final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            final int schemaVersion = in.readInt();
            final String keyEncryptionKeyId = readString(in);
            final byte[] wrappedKeyMaterial = readBytes(in, MAX_METADATA_FIELD_LENGTH_BYTES);
            final String wrapAlgorithm = readString(in);
            final String dataEncryptionKeyAlgorithmId = readString(in);
            final String payloadAlgorithmId = readString(in);
            final byte[] nonce = readBytes(in, MAX_METADATA_FIELD_LENGTH_BYTES);
            // The ciphertext is this file's entire content in the one-shot layout, so no fixed cap
            // can apply to it. The object that carries it is already in memory, and no field inside
            // an object can be longer than the object itself - that is the bound.
            final byte[] ciphertext = readBytes(in, bytes.length);
            final byte[] associatedData = readBytes(in, MAX_METADATA_FIELD_LENGTH_BYTES);

            final WrappedKey wrappedKey = new WrappedKey(keyEncryptionKeyId, wrappedKeyMaterial, wrapAlgorithm, dataEncryptionKeyAlgorithmId);
            final EncryptedPayload payload = new EncryptedPayload(payloadAlgorithmId, nonce, ciphertext, associatedData);
            return new EnvelopeEncryptedPayload(schemaVersion, wrappedKey, payload);
        } catch (final IOException e) {
            throw new ObjectStorageException("@EnvelopeEncryptedPayloadCodec.deserialize: malformed/truncated object content", e);
        }
    }

    private static void writeString(final DataOutputStream out, final String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(final DataInputStream in) throws IOException {
        return new String(readBytes(in, MAX_METADATA_FIELD_LENGTH_BYTES), StandardCharsets.UTF_8);
    }

    private static void writeBytes(final DataOutputStream out, final byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    /**
     * Reads one length-prefixed field, rejecting an implausible declared length before allocating.
     *
     * @param in the stream, positioned at the field's 4-byte length prefix
     * @param maximumLength the largest length this particular field may legitimately claim
     * @return the field's bytes
     * @throws IOException if the declared length is negative or exceeds {@code maximumLength}, or
     *     if the stream ends before the field is complete
     */
    private static byte[] readBytes(final DataInputStream in, final int maximumLength) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > maximumLength) {
            throw new IOException("@EnvelopeEncryptedPayloadCodec: implausible payload field length " + length);
        }
        final byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }
}
