package de.lino.cloud.extensions.search;

import de.lino.cloud.api.search.SearchDocument;
import de.lino.cloud.api.search.SearchIndexService;
import de.lino.cloud.api.search.SearchResult;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The one {@link SearchIndexService} implementation for v1 (section 5, {@code
 * architecture/MICRO.md}) - a plain, in-process, per-account {@code Map<storedFileId,
 * SearchDocument>} searched by a linear scan over the querying account's own documents, rather
 * than a real inverted-index library (Lucene, etc.) - the doc's own "do not over-engineer this in
 * v1" instruction, and this codebase's existing precedent of accepting an O(n)-per-account scan
 * for a derived/bounded-size collection (the exact same trade-off {@code StoredFileOwnership}'s
 * own full-section scans already make, just held in memory here instead of re-decrypted from the
 * database on every call).
 *
 * <p><b>Not for production at any real scale, and not persisted - lost on restart, same "not for
 * production" trade-off {@code InMemoryKeyEncryptionService}/{@code InMemoryPendingUploadCache}
 * already document.</b> Safe specifically because {@link SearchIndexService} is a fully derived
 * index (see its own Javadoc) - nothing is lost that a real reindex couldn't rebuild, there simply
 * is no reindex-everything command yet (a natural follow-up, not required for this pass since a
 * restart only means search results are briefly empty for files uploaded before the restart,
 * never wrong/stale). {@link SearchIndexService} is an interface specifically so a persistent/
 * real-inverted-index implementation can replace this one later without touching any call site -
 * the same "contract in {@code cloud-driver-api}, swappable implementation(s)" shape {@code
 * KeyEncryptionService}/{@code ObjectStorageService} already established.
 */
public final class InMemorySearchIndexService implements SearchIndexService {

    /** Splits a query/filename/text extract into lowercase tokens - any run of non-alphanumeric characters is a separator. */
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** How much a token match in {@link SearchDocument#fileName()} outweighs one in {@link SearchDocument#extractedText()} - a filename hit is a stronger signal of relevance. */
    private static final int FILENAME_MATCH_WEIGHT = 2;

    /** {@code authUserId -> (storedFileId -> document)} - one inner map per account, so a search never has to filter out another account's documents. */
    private final Map<String, Map<String, SearchDocument>> documentsByUser = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public void indexFile(@NotNull final SearchDocument document) {
        this.documentsForUser(document.authUserId()).put(document.storedFileId(), document);
    }

    /** {@inheritDoc} */
    @Override
    public void updateMetadata(@NotNull final String authUserId, @NotNull final String storedFileId,
                                @NotNull final String fileName, final String folderId) {
        this.documentsForUser(authUserId).computeIfPresent(storedFileId, (id, existing) ->
                new SearchDocument(authUserId, storedFileId, fileName, folderId, existing.extractedText()));
    }

    /** {@inheritDoc} */
    @Override
    public void removeFile(@NotNull final String authUserId, @NotNull final String storedFileId) {
        final Map<String, SearchDocument> documents = this.documentsByUser.get(authUserId);
        if (documents != null) documents.remove(storedFileId);
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public List<SearchResult> search(@NotNull final String authUserId, @NotNull final String query, final int limit) {
        final List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        final Map<String, SearchDocument> documents = this.documentsByUser.get(authUserId);
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        final List<ScoredDocument> matches = new ArrayList<>();
        for (final SearchDocument document : documents.values()) {
            final int score = scoreDocument(document, queryTokens);
            if (score > 0) matches.add(new ScoredDocument(document, score));
        }

        return matches.stream()
                .sorted(Comparator.comparingInt((ScoredDocument scored) -> scored.score).reversed())
                .limit(Math.max(0, limit))
                .map(scored -> new SearchResult(scored.document.storedFileId(), scored.document.fileName(), scored.document.folderId()))
                .toList();
    }

    /**
     * Any query token found as a substring of {@code document}'s (lowercased) {@link
     * SearchDocument#fileName()} or {@link SearchDocument#extractedText()} counts toward the
     * score - an "OR" match (at least one token hit is enough to appear in results), ranked by
     * total weighted hits, rather than requiring every token to match ("AND") - more forgiving for
     * a plain search box, where a multi-word query missing one word from a genuinely relevant file
     * shouldn't hide it entirely.
     */
    private static int scoreDocument(final SearchDocument document, final List<String> queryTokens) {
        final String lowerFileName = document.fileName().toLowerCase(Locale.ROOT);
        final String lowerContent = document.extractedText() == null ? null : document.extractedText().toLowerCase(Locale.ROOT);

        int score = 0;
        for (final String token : queryTokens) {
            if (lowerFileName.contains(token)) score += FILENAME_MATCH_WEIGHT;
            if (lowerContent != null && lowerContent.contains(token)) score += 1;
        }
        return score;
    }

    /**
     * Splits {@code text} into lowercase tokens on any run of non-alphanumeric characters,
     * dropping empty tokens - the same simple tokenization used for both indexing (implicitly,
     * since {@link #scoreDocument} tokenizes the query and does substring containment against the
     * raw lowercased text, not a token-indexed structure) and query parsing here.
     */
    private static List<String> tokenize(final String text) {
        return TOKEN_SEPARATOR.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private Map<String, SearchDocument> documentsForUser(final String authUserId) {
        return this.documentsByUser.computeIfAbsent(authUserId, ignored -> new ConcurrentHashMap<>());
    }

    /** Pairs a {@link SearchDocument} with its computed relevance score, purely so {@link #search} can sort by it. */
    private record ScoredDocument(SearchDocument document, int score) {
    }

}
