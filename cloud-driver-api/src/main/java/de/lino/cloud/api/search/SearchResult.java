package de.lino.cloud.api.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One match returned by {@link SearchIndexService#search}, carrying enough metadata for a client
 * to navigate straight to the file it describes without a second lookup.
 *
 * @param storedFileId the {@link de.lino.cloud.api.file.StoredFile#fileId()} that matched
 * @param fileName the file's current display name
 * @param folderId the {@link de.lino.cloud.api.file.Folder#getFolderId()} the file currently sits in, or {@code null} for the root
 */
public record SearchResult(@NotNull String storedFileId, @NotNull String fileName, @Nullable String folderId) {
}
