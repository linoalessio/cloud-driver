package de.lino.cloud.extensions.search;

import de.lino.cloud.api.search.SearchDocument;
import de.lino.cloud.api.search.SearchResult;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.sql.SQLExecution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone, runnable worked example (not an {@code mvn test} target - see "Testing" in {@code
 * CLAUDE.md}) exercising {@link PostgresSearchIndexService} end to end against a real, local
 * Postgres (default: trust-authenticated {@code localhost:5432}, database {@code
 * cloud_driver_search_sample} - create it first: {@code createdb cloud_driver_search_sample}),
 * printing pass/fail per check to stdout and exiting non-zero on any failure. Override
 * host/port/user/password/database via the {@code SEARCH_SAMPLE_PG_*} environment variables.
 *
 * <ul>
 *     <li>schema auto-creation, indexing, and prefix search with the 2:1 filename-over-content
 *     ranking</li>
 *     <li>per-account scoping (one account never sees another's documents)</li>
 *     <li>{@code updateMetadata} rewrites the filename half only - content keeps matching</li>
 *     <li>a metadata-only re-index (the startup backfill's shape) preserves a previously
 *     indexed content vector</li>
 *     <li>{@code removeFile}, {@code indexedDocumentCount}, and {@code buildPrefixTsQuery}'s
 *     edge cases</li>
 * </ul>
 */
public final class PostgresSearchIndexSample {

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private PostgresSearchIndexSample() {
    }

    /**
     * Runs every check described in this class's own Javadoc and reports pass/fail to stdout.
     *
     * @param args unused
     * @throws Exception on any unexpected failure - the sample makes no attempt to continue past one
     */
    public static void main(final String[] args) throws Exception {

        final String host = env("SEARCH_SAMPLE_PG_HOST", "127.0.0.1");
        final int port = Integer.parseInt(env("SEARCH_SAMPLE_PG_PORT", "5432"));
        final String user = env("SEARCH_SAMPLE_PG_USER", System.getProperty("user.name"));
        final String password = env("SEARCH_SAMPLE_PG_PASSWORD", "");
        final String database = env("SEARCH_SAMPLE_PG_DATABASE", "cloud_driver_search_sample");

        // JsonDocument#write (used by Credentials' constructor) resolves the process-wide
        // FileProvider - install the default one, same as every other standalone sample.
        new de.lino.database.database.file.DefaultFileProvider();

        final Path scratchCredentialsFile = Files.createTempFile("search-sample-credentials-", ".json");
        Files.deleteIfExists(scratchCredentialsFile); // Credentials' constructor writes it fresh
        final Credentials credentials = new Credentials(scratchCredentialsFile, host, user, password, port, database);

        // The same pool shape production uses - there, the service receives
        // SQLDatabaseProvider#getSqlExecution(); here the sample owns the pool (and closes it).
        final SQLExecution sqlExecution = new SQLExecution(DatabaseType.POSTGRES_SQL, credentials);
        final PostgresSearchIndexService index = new PostgresSearchIndexService(sqlExecution);
        try {
            // Fresh slate in case an earlier run aborted mid-way.
            index.removeFile("alice", "f-report");
            index.removeFile("alice", "f-notes");
            index.removeFile("bob", "f-bobfile");

            index.indexFile(new SearchDocument("alice", "f-report", "quarterly-report.pdf", null,
                    "revenue grew across every region this quarter"));
            index.indexFile(new SearchDocument("alice", "f-notes", "meeting-notes.txt", "folder-1",
                    "discussed the quarterly report and revenue targets"));
            index.indexFile(new SearchDocument("bob", "f-bobfile", "quarterly-summary.txt", null,
                    "bob's own quarterly file"));

            // --- prefix search + filename-over-content ranking ---
            final List<SearchResult> quarterly = index.search("alice", "quarterly", 10);
            check("both of alice's documents match 'quarterly'", quarterly.size() == 2);
            check("filename hit outranks content hit",
                    !quarterly.isEmpty() && quarterly.get(0).storedFileId().equals("f-report"));
            check("prefix matching works ('quart' finds them too)",
                    index.search("alice", "quart", 10).size() == 2);

            // --- per-account scoping ---
            check("bob's search never sees alice's documents",
                    index.search("bob", "quarterly", 10).stream().allMatch(result -> result.storedFileId().equals("f-bobfile")));

            // --- updateMetadata: filename half only ---
            index.updateMetadata("alice", "f-report", "renamed-summary.pdf", "folder-2");
            check("old filename no longer matches after rename",
                    index.search("alice", "report", 10).stream().noneMatch(result -> result.storedFileId().equals("f-report")
                            && result.fileName().equals("quarterly-report.pdf")));
            final List<SearchResult> renamed = index.search("alice", "renamed", 10);
            check("new filename matches after rename",
                    renamed.size() == 1 && renamed.get(0).folderId().equals("folder-2"));
            check("content still matches after a rename (content vector untouched)",
                    index.search("alice", "revenue", 10).stream().anyMatch(result -> result.storedFileId().equals("f-report")));

            // --- metadata-only re-index (the backfill's shape) preserves content ---
            index.indexFile(new SearchDocument("alice", "f-report", "renamed-summary.pdf", "folder-2", null));
            check("a text-less re-index preserves the previously indexed content",
                    index.search("alice", "revenue", 10).stream().anyMatch(result -> result.storedFileId().equals("f-report")));

            // --- removal + count ---
            final int beforeRemove = index.indexedDocumentCount();
            index.removeFile("alice", "f-notes");
            check("removeFile removes exactly one document", index.indexedDocumentCount() == beforeRemove - 1);
            check("a removed document stops matching",
                    index.search("alice", "meeting", 10).isEmpty());

            // --- query building edge cases ---
            check("blank query yields no tsquery", PostgresSearchIndexService.buildPrefixTsQuery("  \t ") == null);
            check("tokens are sanitized and OR-ed with prefix matching",
                    "hello:* | world:*".equals(PostgresSearchIndexService.buildPrefixTsQuery("Hello, WORLD!")));
            check("a purely-symbolic query returns no results (never throws)",
                    index.search("alice", "&&& |||", 10).isEmpty());
        } finally {
            sqlExecution.shutdown();
            Files.deleteIfExists(scratchCredentialsFile);
        }

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** Reads one environment variable, falling back to {@code fallback} when unset/blank. */
    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Prints one check's outcome and folds it into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS  " : "FAIL  ") + description);
        allPassed &= passed;
    }
}
