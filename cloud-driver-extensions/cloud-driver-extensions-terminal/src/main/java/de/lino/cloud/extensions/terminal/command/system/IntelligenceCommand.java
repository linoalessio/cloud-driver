package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.intelligence.DuplicateFileGroup;
import de.lino.cloud.api.intelligence.IntelligenceDocument;
import de.lino.cloud.api.intelligence.IntelligenceService;
import de.lino.cloud.api.intelligence.SemanticSearchResult;
import de.lino.cloud.api.intelligence.TagSuggestion;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Operator access to semantic search: health, on-demand backfill, and the query/duplicate/tag
 * surfaces, from the terminal.
 *
 * <h2>Backfill - the gap this primarily exists to close</h2>
 *
 * Indexing is driven entirely from {@code CloudUserService}'s own mutation methods, so a file is
 * embedded when it is uploaded, replaced or restored - and <b>never at any other time</b>. A
 * deployment that enables semantic search after files already exist therefore starts with those
 * files permanently invisible to it: nothing in the normal running of the system ever revisits
 * them. That is not a corner case, it is what every existing deployment experiences on the day
 * this feature is switched on.
 *
 * <p>{@code intelligence backfill} is the answer, and it is deliberately a command rather than a
 * startup hook: a full backfill re-reads and re-embeds an account's entire corpus, which is
 * exactly the kind of unbounded work this codebase has repeatedly been burned by running
 * automatically (see the {@code OutOfMemoryError} incidents around full-table scans). An operator
 * chooses when to pay it.
 *
 * <p>By default it backfills <b>names only</b>, which is cheap - it reads already-cached metadata
 * off {@code StoredFileOwnership} rows and never touches file content. {@code --content} opts into
 * the expensive form, fetching and decrypting each file so its text is embedded too; that is the
 * one that finds "Rechnung Autowerkstatt" inside {@code scan_0042.pdf}, and the one that must not
 * be run casually on a large account.
 */
public class IntelligenceCommand implements Command {

    /** Files fetched per progress line during a {@code --content} backfill. */
    private static final int PROGRESS_INTERVAL = 100;

    /** @return {@code "intelligence"} */
    @Override
    public @NotNull String name() {
        return "intelligence";
    }

    /** @return {@code "ai"}, {@code "semantic"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("ai", "semantic");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Semantic search: status, backfill the vector index, query, find duplicates, suggest tags";
    }

    /**
     * Dispatches to one of {@code status}/{@code backfill}/{@code search}/{@code duplicates}/
     * {@code tags}, printing usage if the sub-command is missing or unrecognized.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();

        if (intelligenceService == null) {
            terminal.displayApproved("&cSemantic search is not running &7on this deployment (cloud-driver-intelligence extension not loaded).");
            return;
        }

        if (arguments.isEmpty() || arguments.hasCommand(0, "status")) {
            this.status(terminal, intelligenceService);
            return;
        }

        if (arguments.hasCommand(0, "backfill")) {
            this.backfill(terminal, intelligenceService, arguments);
            return;
        }

        if (arguments.hasCommand(0, "search") && arguments.hasLength(2)) {
            this.search(terminal, arguments);
            return;
        }

        if (arguments.hasCommand(0, "duplicates") && arguments.hasLength(1)) {
            this.duplicates(terminal, arguments);
            return;
        }

        if (arguments.hasCommand(0, "tags") && arguments.hasLength(1)) {
            this.tags(terminal, arguments);
            return;
        }

        this.sendHelp(terminal);

    }

    /** Prints reachability plus what an unreachable service actually costs. */
    private void status(final Terminal terminal, final IntelligenceService intelligenceService) {
        final boolean healthy = intelligenceService.isServiceHealthy();
        terminal.emptyLine();
        terminal.displayApproved("Semantic search: %s", healthy ? "&areachable" : "&cUNREACHABLE");
        if (!healthy) {
            terminal.displayApproved("&7The service is either down or up-but-unable-to-embed. Uploads stay &cun-indexed&7 either way.");
        }
        terminal.emptyLine();
    }

    /**
     * Backfills the vector index for one account or every account.
     *
     * <p>Runs on the calling command thread (a virtual thread dispatched by {@code
     * CommandService#dispatchAsync}), so it never blocks the terminal's reading loop and an
     * operator can keep working while it runs. Progress is printed as it goes rather than only at
     * the end - a content backfill over a large account is genuinely slow, and a command that
     * prints nothing for minutes is indistinguishable from one that has hung.
     */
    private void backfill(final Terminal terminal, final IntelligenceService intelligenceService,
                          final CommandArguments arguments) {

        final boolean includeContent = this.hasFlag(arguments, "--content");
        final String target = arguments.hasLength(1) && !arguments.command(1).startsWith("--")
                ? arguments.command(1) : "all";

        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cCloudUserService is not published &7- the REST extension has not started yet.");
            return;
        }

        final String scopedAuthUserId;
        if ("all".equalsIgnoreCase(target)) {
            scopedAuthUserId = null;
        } else {
            final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(target);
            if (cloudUser.isEmpty()) {
                terminal.displayApproved("Cloud user '&b%s&7' does not exist", target);
                return;
            }
            scopedAuthUserId = cloudUser.get().getAuthUserId();
        }

        terminal.displayApproved("Starting backfill (&b%s&7, %s)...",
                scopedAuthUserId == null ? "every account" : target,
                includeContent ? "&econtent + names &7- this is the slow one" : "&anames only");

        final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
        final FileFactory fileFactory = CloudDriver.getInstance().getFactoryContainer().getFileFactory();

        final List<StoredFileOwnership> rows;
        try {
            rows = dataFactory.getEntitiesAsync(StoredFileOwnership.class).join();
        } catch (final RuntimeException failed) {
            terminal.displayApproved("&cBackfill aborted&7: could not list ownership rows (%s)", failed.getMessage());
            return;
        }

        int indexed = 0;
        int skipped = 0;
        for (final StoredFileOwnership row : rows) {
            // A trashed file must not become searchable again by being backfilled - the same rule
            // every live listing already applies.
            if (row.isDeleted() || !row.hasMetadata()) {
                skipped++;
                continue;
            }
            if (scopedAuthUserId != null && !scopedAuthUserId.equals(row.getAuthUserId())) continue;

            byte[] content = null;
            if (includeContent) {
                content = this.resolveContentQuietly(fileFactory, row.getStoredFileId());
            }

            intelligenceService.indexAsync(new IntelligenceDocument(
                    row.getAuthUserId(), row.getStoredFileId(), row.getFileName(), row.getContentType(), content));
            indexed++;

            if (indexed % PROGRESS_INTERVAL == 0) {
                terminal.displayApproved("  &8... &7queued &b%s &7file(s)", indexed);
            }
        }

        terminal.displayApproved("Backfill queued: &b%s &7file(s), &8%s skipped &7(trashed or missing metadata).", indexed, skipped);
        terminal.displayApproved("&7Indexing itself is asynchronous - watch for &cSEVERE &7give-up lines if the service is down.");
    }

    /**
     * Fetches one file's decrypted content, or {@code null} if that fails for any reason.
     *
     * <p>A single unreadable file must not abort a backfill over thousands of others - it is
     * simply indexed by name, exactly as it would be on a deployment without content indexing.
     */
    private byte[] resolveContentQuietly(final FileFactory fileFactory, final String storedFileId) {
        try {
            return fileFactory.findById(storedFileId).map(StoredFile::content).orElse(null);
        } catch (final Exception unreadable) {
            return null;
        }
    }

    /** Runs a real, access-checked semantic search as a given account - the same path a client takes. */
    private void search(final Terminal terminal, final CommandArguments arguments) {
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
        final String query = this.joinFrom(arguments, 2);
        final List<SemanticSearchResult> results = cloudUserService.semanticSearch(cloudUser.get().getAuthUserId(), query, 10);

        terminal.emptyLine();
        terminal.displayApproved("Semantic results for '&b%s&7' (&b%s&7):", query, results.size());
        results.forEach(result -> terminal.displayApproved("&8- &b%.3f &7%s &8(%s)",
                result.score(), result.fileName(), result.storedFileId()));
        if (results.isEmpty()) {
            terminal.displayApproved("&8  (nothing matched - if this account has files, try &fintelligence backfill %s&8)", email);
        }
        terminal.emptyLine();
    }

    /** Lists near-duplicate groups for one account. */
    private void duplicates(final Terminal terminal, final CommandArguments arguments) {
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

        double threshold = 0.95d;
        if (arguments.hasLength(2)) {
            try {
                threshold = Double.parseDouble(arguments.command(2));
            } catch (final NumberFormatException malformed) {
                terminal.displayApproved("&cInvalid threshold &7'%s' - expected a number between 0 and 1", arguments.command(2));
                return;
            }
        }

        final List<DuplicateFileGroup> groups =
                cloudUserService.findDuplicateFiles(cloudUser.get().getAuthUserId(), threshold, 50);

        terminal.emptyLine();
        terminal.displayApproved("Near-duplicate groups for &b%s &7at threshold &b%.2f&7: &b%s", email, threshold, groups.size());
        groups.forEach(group -> {
            terminal.displayApproved("&8- &7group (&b%.3f&7):", group.similarity());
            group.files().forEach(entry -> terminal.displayApproved("    &8- &f%s &8(%s)", entry.fileName(), entry.storedFileId()));
        });
        terminal.displayApproved("&8These are suggestions, not facts - nothing is deleted by this command.");
        terminal.emptyLine();
    }

    /** Prints suggested labels for one file id, as its owner. */
    private void tags(final Terminal terminal, final CommandArguments arguments) {
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cCloudUserService is not published&7.");
            return;
        }
        final String storedFileId = arguments.command(1);
        final String ownerAuthUserId = cloudUserService.resolveOwnerAuthUserId(storedFileId).orElse(null);
        if (ownerAuthUserId == null) {
            terminal.displayApproved("No file found with id '&b%s&7'", storedFileId);
            return;
        }

        final List<TagSuggestion> suggestions = cloudUserService.suggestFileTags(ownerAuthUserId, storedFileId, 8);
        terminal.emptyLine();
        terminal.displayApproved("Suggested tags for &b%s&7:", storedFileId);
        suggestions.forEach(suggestion -> terminal.displayApproved("&8- &f%-24s &b%.3f", suggestion.tag(), suggestion.confidence()));
        if (suggestions.isEmpty()) {
            terminal.displayApproved("&8  (none - the file may never have been indexed)");
        }
        terminal.emptyLine();
    }

    /** Whether {@code flag} appears anywhere in {@code arguments}. */
    private boolean hasFlag(final CommandArguments arguments, final String flag) {
        for (int index = 0; index < arguments.length(); index++) {
            if (flag.equalsIgnoreCase(arguments.command(index))) return true;
        }
        return false;
    }

    /** Joins every argument from {@code start} onward with spaces - a query is not one token. */
    private String joinFrom(final CommandArguments arguments, final int start) {
        final StringBuilder joined = new StringBuilder();
        for (int index = start; index < arguments.length(); index++) {
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(arguments.command(index));
        }
        return joined.toString();
    }

    /** Prints this command's usage syntax. */
    private void sendHelp(final Terminal terminal) {
        terminal.displayApproved("&fintelligence status");
        terminal.displayApproved("&fintelligence backfill <all|email> [--content]");
        terminal.displayApproved("&fintelligence search <email> <query...>");
        terminal.displayApproved("&fintelligence duplicates <email> [threshold]");
        terminal.displayApproved("&fintelligence tags <fileId>");
    }

}
