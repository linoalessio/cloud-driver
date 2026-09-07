package de.lino.cloud.api.versioning;

import de.lino.cloud.api.file.StoredFile;

/**
 * One resolved version's full content plus enough descriptive metadata to serve it as a real
 * download (a {@code Content-Disposition} name and type) - {@link
 * FileVersioningService#getVersionContent}'s return shape.
 *
 * @param fileName the source file's {@link StoredFile#fileName()} at the moment this version was captured - may differ from the file's current live name
 * @param contentType the source file's {@link StoredFile#contentType()} at the moment this version was captured
 * @param content this version's raw bytes
 */
public record FileVersionContent(String fileName, String contentType, byte[] content) {
}
