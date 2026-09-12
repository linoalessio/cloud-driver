package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.search.SearchDocument;
import de.lino.cloud.api.search.SearchIndexService;
import de.lino.cloud.api.search.SearchResult;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Keyword-search index visibility, on-demand rebuild, and a query passthrough.
 *
 * <h2>Why an operator needs this</h2>
 *
 * The index is normally a Postgres {@code tsvector}/GIN table, so it survives restarts and is
 * shared across instances - but it falls back to a purely in-memory index whenever that table
 * cannot be reached, and an in-memory index is lost on every restart. Either way, a startup
 * backfill repopulates filenames automatically, and neither mode answers the two questions that
 * actually come up when a user reports "search is broken": <em>is the index populated at all</em>,
 * and <em>does a query work when run server-side</em>. Without those, an empty index and a broken
 * client look identical - and so do the persistent and fallback modes.
 *
 * <p>That distinction is not theoretical - a real report of search returning nothing was traced to
 * an index that had simply never been populated for pre-existing files, with the client-side
 * wiring entirely correct throughout.
 */
public class SearchIndexCommand implements Command {

    /** @return {@code "searchIndex"} */
    @Override
    public @NotNull String name() {
        return "searchIndex";
    }

    /** @return {@code "si"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("si");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Keyword search index: depth, rebuild from ownership rows, and run a query server-side";
    }

    /**
     * Dispatches to {@code status} (the default), {@code rebuild}, or {@code query}.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final SearchIndexService searchIndexService = CloudDriver.getInstance().getServiceContainer().getSearchIndexService();

        if (searchIndexService == null) {
            terminal.displayApproved("&cThe search index is not running &7on this deployment.");
            return;
        }

        if (arguments.hasCommand(0, "rebuild")) {
            this.rebuild(terminal, searchIndexService);
            return;
        }

        if (arguments.hasCommand(0, "query") && arguments.hasLength(2)) {
            this.query(terminal, searchIndexService, arguments);
            return;
        }

        if (arguments.isEmpty() || arguments.hasCommand(0, "status")) {
            this.status(terminal, searchIndexService);
            return;
        }

        terminal.displayApproved("&fsearchIndex status");
        terminal.displayApproved("&fsearchIndex rebuild");
        terminal.displayApproved("&fsearchIndex query <email> <text...>");
    }

    /** Prints how many documents the index actually holds. */
    private void status(final Terminal terminal, final SearchIndexService searchIndexService) {
        final int documents = searchIndexService.indexedDocumentCount();
        terminal.emptyLine();
        terminal.displayApproved("Indexed documents: %s", documents < 0 ? "&8unknown" : "&b" + documents);
        if (documents == 0) {
            terminal.displayApproved("&e! &7The index is empty. If this account has files, run &fsearchIndex rebuild&7.");
        }
        terminal.emptyLine();
    }

    /**
     * Rebuilds the index from every non-trashed ownership row's cached metadata.
     *
     * <p>Names and folders only, deliberately - never file content. Re-extracting text would mean
     * fetching and decrypting every file in the deployment at once, which is precisely the
     * unbounded full-corpus decrypt this codebase has produced {@code OutOfMemoryError}s with
     * before. Filename search covers the common case immediately, and a file's own text is
     * re-indexed as normal the next time it is uploaded or replaced.
     */
    private void rebuild(final Terminal terminal, final SearchIndexService searchIndexService) {
        terminal.displayApproved("Rebuilding the search index from ownership rows (names/folders only)...");

        final List<StoredFileOwnership> rows;
        try {
            final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
            rows = dataFactory.getEntitiesAsync(StoredFileOwnership.class).join();
        } catch (final RuntimeException failed) {
            terminal.displayApproved("&cRebuild aborted&7: %s", failed.getMessage());
            return;
        }

        int indexed = 0;
        for (final StoredFileOwnership row : rows) {
            if (row.isDeleted() || !row.hasMetadata()) continue;
            searchIndexService.indexFile(new SearchDocument(
                    row.getAuthUserId(), row.getStoredFileId(), row.getFileName(), row.getFolderId(), null));
            indexed++;
        }
        terminal.displayApproved("Indexed &b%s &7file(s) by name.", indexed);
    }

    /** Runs a query as a given account, so a "search is broken" report can be reproduced server-side. */
    private void query(final Terminal terminal, final SearchIndexService searchIndexService, final CommandArguments arguments) {
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cCloudUserService is not published&7.");
            return;
        }
        final String email = arguments.command(1);
        final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(email);
        if (cloudUser.isEmpty()) {
            terminal.displayApproved("Cloud user '&b%s&7' does not exist", email);
            return;
        }

        final StringBuilder query = new StringBuilder();
        for (int index = 2; index < arguments.length(); index++) {
            if (!query.isEmpty()) query.append(' ');
            query.append(arguments.command(index));
        }

        final List<SearchResult> results = searchIndexService.search(cloudUser.get().getAuthUserId(), query.toString(), 20);
        terminal.emptyLine();
        terminal.displayApproved("Results for '&b%s&7': &b%s", query, results.size());
        results.forEach(result -> terminal.displayApproved("&8- &f%s &8(%s)", result.fileName(), result.storedFileId()));
        if (results.isEmpty()) terminal.displayApproved("&8  (nothing matched)");
        terminal.emptyLine();
    }

}
