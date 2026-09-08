package de.lino.cloud.api.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One file's searchable state, as {@link SearchIndexService} indexes/updates it - the caller's
 * own metadata already in hand (nothing here is fetched by the index itself), plus an optional
 * text extract for content indexing (v1 scope: plain-text-ish files only).
 *
 * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} - a document is
 *     only ever matched against a search performed by this same account (per-account index, the
 *     same scoping this codebase's deduplication feature already established)
 * @param storedFileId the {@link de.lino.cloud.api.file.StoredFile#fileId()} this document describes
 * @param fileName the file's current display name - always indexed
 * @param folderId the {@link de.lino.cloud.api.file.Folder#getFolderId()} the file currently sits
 *     in, or {@code null} for the root - carried through to a {@link SearchResult} so a client can
 *     navigate straight to it, not itself searched
 * @param extractedText a bounded text extract of the file's own content, or {@code null} if this
 *     file's content type isn't indexed in v1 (anything other than plain text/JSON/XML/YAML/TOML)
 */
public record SearchDocument(@NotNull String authUserId, @NotNull String storedFileId, @NotNull String fileName,
                              @Nullable String folderId, @Nullable String extractedText) {
}
