package de.lino.cloud.api.file.meta;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Asserts;

import java.time.Instant;

/**
 * A {@link StoredFile}'s descriptive attributes without its content - see
 * {@link StoredFile#metadata()}.
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
