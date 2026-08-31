package de.lino.cloud.api.file;

import org.jetbrains.annotations.Nullable;

/**
 * One entry in a file listing: a {@link StoredFile}'s descriptive fields plus its current {@link
 * Folder} placement, deliberately without {@code content} - the shape {@code
 * ICloudUserService#listFileSummaries} returns so a caller can render a file list (name, size,
 * folder) without decrypting/decompressing the full {@link StoredFile} for every entry, the way
 * a {@link FileWithFolder}-based listing does. Fetch the full {@link StoredFile} (via {@code
 * ICloudUserService#getFile}) only once a specific file's actual content is needed.
 *
 * @param fileId the file's unique id, its {@link StoredFile#fileId()}
 * @param fileName the file's original file name
 * @param contentType the file's inferred MIME content type
 * @param sizeBytes the size, in bytes, of the file's original, uncompressed content
 * @param createdAtEpochMilli when the file was first uploaded
 * @param updatedAtEpochMilli when the file's content was last changed
 * @param folderId the {@link Folder#getFolderId()} the file currently sits in, or {@code null} for the root
 */
public record StoredFileSummary(String fileId, String fileName, String contentType, long sizeBytes,
                                 long createdAtEpochMilli, long updatedAtEpochMilli, @Nullable String folderId) {
}
