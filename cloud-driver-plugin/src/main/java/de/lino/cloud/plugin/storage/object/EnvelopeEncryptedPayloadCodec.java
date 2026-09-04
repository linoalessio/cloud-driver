package de.lino.cloud.plugin.storage.object;

import de.lino.cloud.api.security.crypto.EncryptedPayload;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.keys.WrappedKey;
import de.lino.cloud.api.storage.object.ObjectStorageException;
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
 * written to/read from an {@link de.lino.cloud.api.storage.object.ObjectStorageService} object.
 *
 * <p><b>Why this exists at all - a real deviation from {@code architecture/AWS_S3_IMPL.md}'s own
 * assumption.</b> That handoff document assumed the same ciphertext bytes a {@code StoredFile}'s
 * {@code contentBase64} would otherwise have carried are already produced somewhere on the
 * existing persistence path and can simply be redirected to S3. In reality, {@code
 * SecureEntityChannel} envelope-encrypts an entity's <em>entire</em> serialized JSON (via {@code
 * Serialized#toByteArray()}) as one opaque blob - there is no isolated "ciphertext of just this
 * file's content bytes" produced anywhere in that pipeline to reuse. {@code DefaultFileFactory}
 * instead calls {@code EnvelopeEncryptionService#encrypt}/{@code #decrypt} <b>directly</b> on a
 * file's raw content bytes (the exact same AES-256-GCM/DEK-KEK scheme {@code SecureEntityChannel}
 * uses, just invoked a second time, independently, on a narrower input) - which is the source of
 * the {@link EnvelopeEncryptedPayload} this class serializes for S3 storage.
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
            final byte[] wrappedKeyMaterial = readBytes(in);
            final String wrapAlgorithm = readString(in);
            final String dataEncryptionKeyAlgorithmId = readString(in);
            final String payloadAlgorithmId = readString(in);
            final byte[] nonce = readBytes(in);
            final byte[] ciphertext = readBytes(in);
            final byte[] associatedData = readBytes(in);

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
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static void writeBytes(final DataOutputStream out, final byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        final byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }
}
