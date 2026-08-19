package de.lino.cloud.api.file;

import de.lino.cloud.api.utility.Asserts;

import java.time.Instant;

/**
 * A {@link StoredFile}'s descriptive attributes without its content - see
 * {@link StoredFile#metadata()} - for callers that only need to know what a
 * file is (name, type, size, checksum, timestamps) without holding its full,
 * decrypted content in memory.
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
        Asserts.assertNotNull(fileId, "@FileMetadata: fileId cannot be null");
        Asserts.assertNotNull(fileName, "@FileMetadata: fileName cannot be null");
        Asserts.assertNotNull(contentType, "@FileMetadata: contentType cannot be null");
        Asserts.assertNotNull(checksum, "@FileMetadata: checksum cannot be null");
        Asserts.assertNotNull(createdAt, "@FileMetadata: createdAt cannot be null");
        Asserts.assertNotNull(updatedAt, "@FileMetadata: updatedAt cannot be null");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("@FileMetadata: sizeBytes cannot be negative, got " + sizeBytes);
        }
    }

}
