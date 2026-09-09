package de.lino.cloud.api.intelligence;

import org.jetbrains.annotations.NotNull;

/**
 * One raw hit as reported by the Python service's own {@code POST /search}, before any
 * access re-check has run.
 *
 * <p><b>Never trust this type as an authorization result.</b> A {@link SemanticMatch} says only
 * "the vector store considers this id similar to the query" - it carries no statement whatsoever
 * about whether the searching account may actually see that file. Every match is re-checked
 * against the caller's real, current ownership/share state before it can reach a client (see
 * {@link IntelligenceService}'s own Javadoc for the full two-stage invariant); the client-facing
 * result type is {@link SemanticSearchResult}, built only from matches that survive that check.
 *
 * @param storedFileId the {@link de.lino.cloud.api.file.StoredFile#fileId()} the vector store matched
 * @param score cosine similarity, higher is more similar
 */
public record SemanticMatch(@NotNull String storedFileId, double score) {
}
