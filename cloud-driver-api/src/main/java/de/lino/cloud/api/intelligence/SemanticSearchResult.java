package de.lino.cloud.api.intelligence;

import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.search.SearchResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One client-facing semantic-search hit - a {@link SemanticMatch} that has already survived the
 * post-check described in {@link IntelligenceService}'s own Javadoc, enriched with enough metadata
 * for a client to navigate straight to the file without a second lookup.
 *
 * <p>Deliberately shaped like the keyword-search {@link SearchResult} it sits beside (same three
 * leading components, same nullable-{@code folderId}-means-root convention), plus the similarity
 * {@link #score()} a keyword match has no equivalent of - so a client rendering both result kinds
 * can reuse one row component for the parts that overlap.
 *
 * @param storedFileId the {@link StoredFile#fileId()} that matched
 * @param fileName the file's current display name
 * @param folderId the {@link Folder#getFolderId()} the file currently sits in, or {@code null} for the root
 * @param score cosine similarity as reported by the vector store, higher is more similar
 */
public record SemanticSearchResult(@NotNull String storedFileId, @NotNull String fileName,
                                   @Nullable String folderId, double score) {
}
