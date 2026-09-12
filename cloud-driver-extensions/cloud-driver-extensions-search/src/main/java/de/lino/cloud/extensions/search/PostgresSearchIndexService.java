package de.lino.cloud.extensions.search;

import de.lino.cloud.api.search.SearchDocument;
import de.lino.cloud.api.search.SearchIndexService;
import de.lino.cloud.api.search.SearchResult;
import de.lino.database.database.sql.SQLExecution;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Persistent, indexed {@link SearchIndexService} backed by a Postgres {@code tsvector}/GIN table
 * - replaces {@link InMemorySearchIndexService} on every Postgres-backed
 * deployment: the index survives restarts (no cold "search is broken" window, no lost content
 * extracts), queries run against a real GIN index instead of a per-account linear scan, and -
 * since Postgres is already the shared store every instance talks to - the index is inherently
 * shared across instances - a multi-instance deployment needs no per-instance index rebuild.
 *
 * <p><b>A deliberate, signed-off exception to "nothing plaintext ever reaches the database"</b>
 * (a deliberate, documented decision - see {@code docs/security.md}): this table stores file names, folder ids,
 * and search lexemes <em>derived</em> from extracted file content as plaintext, because that is
 * what makes a database-side {@code tsvector} index possible at all. It never stores the raw
 * content itself, and everything here is derived, rebuildable state. It is also this codebase's
 * one deliberate exception to "never hand-create tables" - the {@code database-driver} layer's
 * entity tables are {@code id TEXT, data BYTEA} only, which cannot carry a {@code tsvector}
 * column or GIN index.
 *
 * <p><b>Connection model:</b> every statement runs through the {@code database-driver} layer's
 * own shared {@link SQLExecution} HikariCP pool ({@code SQLDatabaseProvider#getSqlExecution()},
 * added in 1.3.16 as exactly this feature's raw-SQL escape hatch) - no second connection, no
 * pool of this class's own, and thread safety comes from the pool itself. {@link SQLExecution}'s
 * helpers already swallow-and-log failures and answer defaults, which happens to be precisely
 * {@link SearchIndexService}'s own "never throw, degrade" contract.
 *
 * <p><b>Query semantics</b> mirror {@link InMemorySearchIndexService}'s forgiving search-box
 * behavior: the query is tokenized the same way, each token becomes a prefix match ({@code
 * token:*}), tokens are OR-ed, and filename hits outrank content hits (ranked {@code
 * ts_rank(name) * 2 + ts_rank(content)}, the same 2:1 weighting the in-memory implementation
 * used). The {@code simple} text-search configuration is used deliberately - no
 * language-specific stemming assumptions for mixed-language filenames/content.
 */
public final class PostgresSearchIndexService implements SearchIndexService {

    /** The hand-created index table - see this class's Javadoc for why hand-created is correct here. */
    private static final String TABLE = "cloud_driver_search_index";

    /** Splits a query into lowercase tokens - identical to {@link InMemorySearchIndexService}'s tokenization, so both implementations understand the same queries. */
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** The {@code database-driver} layer's shared connection pool every statement here runs through. */
    private final SQLExecution sqlExecution;

    /**
     * Creates the index table/GIN indexes if they don't exist yet and verifies the table is
     * actually usable.
     *
     * @param sqlExecution the shared connection pool ({@code SQLDatabaseProvider#getSqlExecution()});
     *     owned by the provider - this class never shuts it down
     * @throws IllegalStateException if the schema could not be created/reached - the caller
     *     ({@code CloudSearchExtension}) treats that as "fall back to the in-memory index"
     */
    public PostgresSearchIndexService(@NotNull final SQLExecution sqlExecution) {
        this.sqlExecution = sqlExecution;
        // Split name/content vectors, so updateMetadata can rewrite the filename half without
        // ever needing the content text back (which is deliberately not stored).
        this.sqlExecution.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "auth_user_id TEXT NOT NULL, "
                + "stored_file_id TEXT NOT NULL, "
                + "file_name TEXT NOT NULL, "
                + "folder_id TEXT, "
                + "name_vector tsvector NOT NULL, "
                + "content_vector tsvector NOT NULL DEFAULT ''::tsvector, "
                + "PRIMARY KEY (auth_user_id, stored_file_id))");
        this.sqlExecution.executeUpdate("CREATE INDEX IF NOT EXISTS " + TABLE + "_name_idx ON " + TABLE
                + " USING GIN (name_vector)");
        this.sqlExecution.executeUpdate("CREATE INDEX IF NOT EXISTS " + TABLE + "_content_idx ON " + TABLE
                + " USING GIN (content_vector)");
        // SQLExecution swallows failures (per its own contract), so probe the table once to turn
        // a broken/absent schema into a construction failure the extension can fall back on -
        // COUNT(*) can never legitimately answer the -1 sentinel.
        if (this.countDocuments() < 0) {
            throw new IllegalStateException(
                    "@PostgresSearchIndexService: '" + TABLE + "' is not usable after schema setup - is this database really PostgreSQL?"
            );
        }
    }

    /**
     * {@inheritDoc} Upserted by {@code (auth_user_id, stored_file_id)}. A document carrying no
     * {@link SearchDocument#extractedText()} leaves an existing row's {@code content_vector}
     * untouched (it has nothing better to replace it with - this is what makes the startup
     * metadata-only backfill non-destructive to previously indexed content, an improvement over
     * the in-memory index it replaces); a document carrying text replaces it.
     */
    @Override
    public void indexFile(@NotNull final SearchDocument document) {
        final boolean hasText = document.extractedText() != null;
        this.sqlExecution.executeUpdate(
                "INSERT INTO " + TABLE
                        + " (auth_user_id, stored_file_id, file_name, folder_id, name_vector, content_vector)"
                        + " VALUES (?, ?, ?, ?, to_tsvector('simple', ?), to_tsvector('simple', ?))"
                        + " ON CONFLICT (auth_user_id, stored_file_id) DO UPDATE SET"
                        + " file_name = EXCLUDED.file_name, folder_id = EXCLUDED.folder_id, name_vector = EXCLUDED.name_vector"
                        + (hasText ? ", content_vector = EXCLUDED.content_vector" : ""),
                document.authUserId(), document.storedFileId(), document.fileName(), document.folderId(),
                document.fileName(), hasText ? document.extractedText() : "");
    }

    /** {@inheritDoc} Rewrites only the filename half - see the constructor on the split vectors. */
    @Override
    public void updateMetadata(@NotNull final String authUserId, @NotNull final String storedFileId,
                                @NotNull final String fileName, final String folderId) {
        this.sqlExecution.executeUpdate(
                "UPDATE " + TABLE + " SET file_name = ?, folder_id = ?, name_vector = to_tsvector('simple', ?)"
                        + " WHERE auth_user_id = ? AND stored_file_id = ?",
                fileName, folderId, fileName, authUserId, storedFileId);
    }

    /** {@inheritDoc} */
    @Override
    public void removeFile(@NotNull final String authUserId, @NotNull final String storedFileId) {
        this.sqlExecution.executeUpdate(
                "DELETE FROM " + TABLE + " WHERE auth_user_id = ? AND stored_file_id = ?",
                authUserId, storedFileId);
    }

    /** {@inheritDoc} Ranked by the same 2:1 filename-over-content weighting the in-memory index used. */
    @NotNull
    @Override
    public List<SearchResult> search(@NotNull final String authUserId, @NotNull final String query, final int limit) {
        final String tsQuery = buildPrefixTsQuery(query);
        if (tsQuery == null || limit <= 0) {
            return List.of();
        }
        return this.sqlExecution.executeQuery(
                "SELECT stored_file_id, file_name, folder_id"
                        + " FROM " + TABLE + ", to_tsquery('simple', ?) query"
                        + " WHERE auth_user_id = ? AND (name_vector @@ query OR content_vector @@ query)"
                        + " ORDER BY ts_rank(name_vector, query) * 2 + ts_rank(content_vector, query) DESC"
                        + " LIMIT ?",
                PostgresSearchIndexService::readResults, List.of(),
                tsQuery, authUserId, limit);
    }

    /** {@inheritDoc} {@code -1} (unknown) if the count query fails, per the interface's contract for it. */
    @Override
    public int indexedDocumentCount() {
        return this.countDocuments();
    }

    /** {@code SELECT COUNT(*)} over the whole table, {@code -1} on any failure. */
    private int countDocuments() {
        return this.sqlExecution.executeQuery("SELECT COUNT(*) FROM " + TABLE, resultSet -> {
            try {
                return resultSet.next() ? resultSet.getInt(1) : -1;
            } catch (final SQLException readFailed) {
                throw new IllegalStateException(readFailed); // caught by SQLExecution, mapped to the default
            }
        }, -1);
    }

    /** Maps {@link #search}'s result set - wraps the checked {@link SQLException} so {@link SQLExecution} maps a read failure to the default (empty). */
    private static List<SearchResult> readResults(final ResultSet resultSet) {
        try {
            final List<SearchResult> results = new ArrayList<>();
            while (resultSet.next()) {
                results.add(new SearchResult(
                        resultSet.getString("stored_file_id"),
                        resultSet.getString("file_name"),
                        resultSet.getString("folder_id")));
            }
            return results;
        } catch (final SQLException readFailed) {
            throw new IllegalStateException(readFailed);
        }
    }

    /**
     * Builds the {@code to_tsquery('simple', ...)} input for {@code query}: each token (same
     * tokenization as {@link InMemorySearchIndexService}) becomes a prefix match ({@code
     * token:*}), OR-ed together - forgiving search-box semantics, where one matching word of a
     * multi-word query is enough to surface a result. Tokens are alphanumeric-only by
     * construction, so nothing here can escape into tsquery syntax.
     *
     * @param query the caller's raw search text
     * @return the tsquery string, or {@code null} if {@code query} yields no tokens at all
     */
    @Nullable
    static String buildPrefixTsQuery(@NotNull final String query) {
        final List<String> tokens = TOKEN_SEPARATOR.splitAsStream(query.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .toList();
        if (tokens.isEmpty()) {
            return null;
        }
        return String.join(" | ", tokens.stream().map(token -> token + ":*").toList());
    }
}
