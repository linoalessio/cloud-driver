package de.lino.cloud.api.file;

import de.lino.cloud.api.s3storage.ContentKeyService;
import de.lino.cloud.api.utility.Asserts;

/**
 * The client-side encryption material accompanying a {@link PresignedUploadTicket} - everything
 * the uploading client needs to produce a stored object byte-identical in layout to a
 * server-encrypted one (see {@link ContentKeyService}'s protocol description): encrypt with
 * {@link #keyMaterial} chunk by chunk ({@link #chunkSizeBytes} plaintext bytes each), bind {@link
 * #associatedDataPrefix} plus chunk counter plus final flag into each chunk's associated data,
 * and write {@link #header} verbatim at offset 0. The resulting object is exactly {@link
 * #objectLengthBytes} bytes - what the server verifies the completed upload against.
 *
 * @param keyMaterial the raw content-encryption key - held only for this one transfer, never persisted by the client
 * @param header the streaming header to write at offset 0 of the uploaded object, byte-for-byte
 * @param associatedDataPrefix the associated-data prefix to bind into every chunk (UTF-8)
 * @param chunkSizeBytes plaintext bytes per chunk
 * @param objectLengthBytes the exact total object length the declared plaintext size must produce
 */
public record PresignedUploadEncryption(byte[] keyMaterial, byte[] header, String associatedDataPrefix,
                                         int chunkSizeBytes, long objectLengthBytes) {

    /**
     * @throws NullPointerException if {@code keyMaterial}, {@code header}, or {@code associatedDataPrefix} is {@code null}
     */
    public PresignedUploadEncryption {
        Asserts.requireNonNull(keyMaterial, "@PresignedUploadEncryption: keyMaterial cannot be null");
        Asserts.requireNonNull(header, "@PresignedUploadEncryption: header cannot be null");
        Asserts.requireNonNull(associatedDataPrefix, "@PresignedUploadEncryption: associatedDataPrefix cannot be null");
        keyMaterial = keyMaterial.clone();
        header = header.clone();
    }

    /** @return a defensive copy of the raw content-encryption key */
    @Override
    public byte[] keyMaterial() {
        return keyMaterial.clone();
    }

    /** @return a defensive copy of the streaming header */
    @Override
    public byte[] header() {
        return header.clone();
    }
}
