package de.lino.cloud.api.file;

import de.lino.cloud.api.utility.Asserts;

/**
 * The client-side decryption material accompanying a {@link PresignedDownloadTicket} for a
 * client-encrypted file: skip the object's first {@link #headerLengthBytes} bytes (the streaming
 * header - the client never unwraps it, the raw key arrives here directly), then decrypt the
 * nonce base and chunk frames under {@link #keyMaterial}, verifying {@link #associatedDataPrefix}
 * plus chunk counter plus final flag as each chunk's associated data - the exact inverse of
 * {@link PresignedUploadEncryption}'s protocol.
 *
 * @param keyMaterial the raw content-encryption key - held only for this one transfer, never persisted by the client
 * @param associatedDataPrefix the associated-data prefix bound into every chunk (UTF-8)
 * @param headerLengthBytes how many leading bytes of the object are the streaming header, to skip
 */
public record PresignedDownloadEncryption(byte[] keyMaterial, String associatedDataPrefix, int headerLengthBytes) {

    /**
     * @throws NullPointerException if {@code keyMaterial} or {@code associatedDataPrefix} is {@code null}
     */
    public PresignedDownloadEncryption {
        Asserts.requireNonNull(keyMaterial, "@PresignedDownloadEncryption: keyMaterial cannot be null");
        Asserts.requireNonNull(associatedDataPrefix, "@PresignedDownloadEncryption: associatedDataPrefix cannot be null");
        keyMaterial = keyMaterial.clone();
    }

    /** @return a defensive copy of the raw content-encryption key */
    @Override
    public byte[] keyMaterial() {
        return keyMaterial.clone();
    }
}
