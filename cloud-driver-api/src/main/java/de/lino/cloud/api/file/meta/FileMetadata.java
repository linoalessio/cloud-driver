package de.lino.cloud.api.file.meta;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Asserts;

import java.time.Instant;

/**
 * A {@link StoredFile}'s descriptive attributes without its content - see
 * {@link StoredFile#metadata()}.
 *
 * @param fileId the file's unique id, matching {@link StoredFile#fileId()}
 * @param fileName the file's original file name, as uploaded
 * @param contentType the file's MIME content type, as inferred by {@link StoredFile#contentType()}
 * @param sizeBytes the size, in bytes, of the file's original, uncompressed content; must not be negative
 * @param checksum the plaintext checksum the file's content must match on every future download
 * @param createdAt when the file was first uploaded
 * @param updatedAt when the file's content was last changed
 */
public record FileMetadata(
        String fileId,
        String fileName,
        String contentType,
        long sizeBytes,
        FileChecksum checksum,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Validates that no component is {@code null} and {@code sizeBytes} isn't negative.
     *
     * @throws NullPointerException if any reference-typed component is {@code null}
     * @throws IllegalArgumentException if {@code sizeBytes} is negative
     */
    public FileMetadata {
        Asserts.requireNonNull(fileId, "@FileMetadata: fileId cannot be null");
        Asserts.requireNonNull(fileName, "@FileMetadata: fileName cannot be null");
        Asserts.requireNonNull(contentType, "@FileMetadata: contentType cannot be null");
        Asserts.requireNonNull(checksum, "@FileMetadata: checksum cannot be null");
        Asserts.requireNonNull(createdAt, "@FileMetadata: createdAt cannot be null");
        Asserts.requireNonNull(updatedAt, "@FileMetadata: updatedAt cannot be null");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("@FileMetadata: sizeBytes cannot be negative, got " + sizeBytes);
        }
    }

}
