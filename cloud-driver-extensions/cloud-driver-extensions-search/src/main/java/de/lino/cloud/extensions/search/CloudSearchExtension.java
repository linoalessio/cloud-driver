package de.lino.cloud.extensions.search;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.search.SearchDocument;
import de.lino.cloud.api.search.SearchIndexService;
import de.lino.cloud.auth.entity.StoredFileOwnership;

import java.util.logging.Level;

/**
 * Search/Indexing - publishes a {@link PostgresSearchIndexService} (persistent
 * tsvector/GIN-backed, on a Postgres deployment) or an {@link InMemorySearchIndexService}
 * (fallback) into {@code IServiceContainer#setSearchIndexService}. See {@code
 * SearchIndexService}'s own Javadoc for the full design, in particular why indexing is driven
 * synchronously from {@code de.lino.cloud.auth.CloudUserService}'s own mutation methods rather
 * than the async {@code FileChangeListener} mechanism.
 *
 * <p>Built as an in-process extension, not a genuinely standalone deployable service, for the
 * same reason {@code cloud-driver-extensions-thumbnails}/{@code -versioning} were. No background
 * thread of its own - unlike {@code
 * CloudVersioningExtension}'s purge scheduler, there is nothing here to tick beyond the one-shot
 * startup backfill below: {@link
 * InMemorySearchIndexService} is a plain in-memory structure, updated only in direct response to a
 * real file mutation (or this extension's own startup backfill).
 */
public class CloudSearchExtension extends Extension {

    /** The freshly constructed index {@link #onLoading()} publishes - kept here too so {@link #backfillIndexAsync()} doesn't have to re-resolve it off the service container. */
    private SearchIndexService searchIndexService;

    /**
     * Publishes a {@link PostgresSearchIndexService} when this deployment's registered database
     * is a Postgres {@code SQLDatabaseProvider} (the persistent, GIN-indexed, restart-surviving
     * index, sharing the very same {@code SQLExecution} connection pool
     * every entity table already runs through), falling back to a fresh {@link
     * InMemorySearchIndexService} otherwise (a JSON-file deployment, or the schema probe
     * failing) - search must keep working either way, matching the "must keep working with
     * every optional dependency off" constraint every extension here applies.
     */
    @Override
    public void onLoading() {
        this.searchIndexService = this.buildBestAvailableIndex();
        this.cloudDriver().getServiceContainer().setSearchIndexService(this.searchIndexService);
    }

    /** Builds the Postgres-backed index if possible, the in-memory one otherwise - see {@link #onLoading()}. */
    private SearchIndexService buildBestAvailableIndex() {
        try {
            for (final de.lino.database.database.DatabaseProvider provider
                    : de.lino.database.DatabaseRepository.getInstance()
                            .getDatabaseProviderPool(de.lino.database.database.DatabaseType.POSTGRES_SQL)) {
                if (provider instanceof de.lino.database.database.sql.SQLDatabaseProvider sqlProvider) {
                    return new PostgresSearchIndexService(sqlProvider.getSqlExecution());
                }
            }
            this.getLogger().info("No Postgres database registered - using the in-memory search index (lost on restart).");
        } catch (final Exception postgresUnavailable) {
            this.getLogger().log(Level.WARNING,
                    "Could not build the Postgres search index - falling back to the in-memory index (lost on restart).",
                    postgresUnavailable);
        }
        return new InMemorySearchIndexService();
    }

    /** Kicks off {@link #backfillIndexAsync()}, then prints a confirmation once {@link #onLoading()} has published the search index. */
    @Override
    public void onRunning(final String[] args) {
        this.backfillIndexAsync();
        this.cloudDriver().getTerminal().displayApproved(this.searchIndexService instanceof PostgresSearchIndexService
                ? "&3Search index &bready &7- Postgres tsvector/GIN-backed, survives restarts"
                : "&3Search index &bready &7- in-memory, indexing filenames and text-file content on upload");
    }

    /**
     * Backfills the freshly-constructed, empty {@link #searchIndexService} with every already-
     * existing, non-trashed {@link StoredFileOwnership} row's already-cached filename/folder
     * metadata - fire-and-forget, dispatched on {@code DataFactory}'s own async executor, never
     * blocks startup.
     *
     * <p><b>Without this, a brand-new index only ever contains files uploaded/renamed/moved
     * <em>after</em> this process started</b> ({@link InMemorySearchIndexService} is lost on every
     * restart, by design - see its own Javadoc). That Javadoc frames the gap as "briefly empty",
     * but on a long-lived deployment with years of already-uploaded files (or simply the first run
     * after this whole feature was deployed), the index starts out missing every pre-existing file
     * - which reads as "search is broken", not "briefly empty", exactly the report that prompted
     * this fix.
     *
     * <p>Indexes filename/folder only, deliberately <b>not</b> each file's own text content -
     * re-extracting indexable text would mean decrypting/fetching every existing file's full
     * content just to backfill, the same "decrypt the whole corpus at once" cost {@code
     * DefaultFileFactory#verifyAll}'s own {@code MAX_CONCURRENT_VERIFICATIONS} bound and this
     * codebase's earlier {@code getEntities(StoredFile.class)} {@code OutOfMemoryError} incidents
     * were fixed to avoid. Filename search alone already covers the common case immediately; a
     * file's own content is indexed as normal the next time it's uploaded/replaced.
     */
    private void backfillIndexAsync() {
        final DataFactory dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();
        dataFactory.getEntitiesAsync(StoredFileOwnership.class).thenAccept(rows -> {
            int indexed = 0;
            for (final StoredFileOwnership row : rows) {
                if (row.isDeleted() || !row.hasMetadata()) continue;
                this.searchIndexService.indexFile(new SearchDocument(
                        row.getAuthUserId(), row.getStoredFileId(), row.getFileName(), row.getFolderId(), null));
                indexed++;
            }
            this.getLogger().info("Search index backfill complete - indexed " + indexed + " existing file(s) by name.");
        }).exceptionally(ex -> {
            this.getLogger().log(Level.WARNING,
                    "Search index backfill failed - search will still work for files uploaded/renamed/moved from now on.", ex);
            return null;
        });
    }

    /** Nothing to shut down - the Postgres index runs over the database provider's own shared pool, which the provider owns. */
    @Override
    public void onEnding() {
        // No resources of this extension's own to release.
    }

    /**
     * Nothing to shut down (see {@link #onEnding()}) - only logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(final RuntimeException reason) {
        this.getLogger().log(java.util.logging.Level.SEVERE, "An error occurred while running the search extension.", reason);
    }

}
