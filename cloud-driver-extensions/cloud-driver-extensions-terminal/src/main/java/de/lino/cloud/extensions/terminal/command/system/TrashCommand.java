package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.TrashedFileSummary;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.UnitParser;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Deployment-wide visibility into the recycle bin.
 *
 * <h2>Why an operator needs this</h2>
 *
 * Trashed files still occupy real storage and still count against an account's quota - they are
 * only released once the retention window elapses and the purge scheduler removes them. So "the
 * disk is full" and "this account cannot upload" both have a trash-shaped explanation that
 * nothing currently surfaces: the purge scheduler ticks once a day and reports nothing, and
 * per-account trash is only visible by logging in as that account.
 *
 * <p>Deliberately read-only. Emptying a specific account's trash already exists as a user-facing
 * action, and a deployment-wide "purge everything now" would bypass every retention window at
 * once - a strictly worse version of a scheduler that is already doing the job correctly.
 */
public class TrashCommand implements Command {

    /** Date format for the per-item listing. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** @return {@code "trash"} */
    @Override
    public @NotNull String name() {
        return "trash";
    }

    /** @return {@code "recycleBin"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("recycleBin");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Show how much storage is held by trashed files, deployment-wide or per account";
    }

    /**
     * Dispatches to {@code status} (the default) or {@code list <email>}.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (arguments.hasCommand(0, "list") && arguments.hasLength(1)) {
            this.list(terminal, arguments.command(1));
            return;
        }

        if (arguments.isEmpty() || arguments.hasCommand(0, "status")) {
            this.status(terminal);
            return;
        }

        terminal.displayApproved("&ftrash status");
        terminal.displayApproved("&ftrash list <email>");
    }

    /** Sums trashed bytes across every account, and names the worst offender. */
    private void status(final Terminal terminal) {
        final List<StoredFileOwnership> rows;
        try {
            final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
            rows = dataFactory.getEntitiesAsync(StoredFileOwnership.class).join();
        } catch (final RuntimeException failed) {
            terminal.displayApproved("&cCould not read ownership rows&7: %s", failed.getMessage());
            return;
        }

        long trashedFiles = 0;
        long trashedBytes = 0;
        long oldestDeletedAt = Long.MAX_VALUE;
        for (final StoredFileOwnership row : rows) {
            if (!row.isDeleted()) continue;
            trashedFiles++;
            if (row.hasMetadata()) trashedBytes += row.getSizeBytes();
            final Long deletedAt = row.getDeletedAtEpochMillis();
            if (deletedAt != null && deletedAt < oldestDeletedAt) oldestDeletedAt = deletedAt;
        }

        terminal.emptyLine();
        terminal.displayApproved("&8--- &fTrash &7(deployment-wide) &8---");
        terminal.displayApproved("&8- &7Trashed files:   &b%s", trashedFiles);
        terminal.displayApproved("&8- &7Storage held:    &b%s", UnitParser.parseByteUnit(trashedBytes));
        if (oldestDeletedAt != Long.MAX_VALUE) {
            terminal.displayApproved("&8- &7Oldest trashed:  &b%s", TIMESTAMP.format(Instant.ofEpochMilli(oldestDeletedAt)));
        }
        if (trashedFiles > 0) {
            terminal.displayApproved("&7This storage stays occupied until the retention window elapses and the purge scheduler runs.");
        }
        terminal.emptyLine();
    }

    /** Lists one account's trashed files with the date each becomes eligible for purging. */
    private void list(final Terminal terminal, final String email) {
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cCloudUserService is not published&7.");
            return;
        }
        final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(email);
        if (cloudUser.isEmpty()) {
            terminal.displayApproved("Cloud user '&b%s&7' does not exist", email);
            return;
        }

        final List<TrashedFileSummary> trashed = cloudUserService.listDeletedFiles(cloudUser.get().getAuthUserId());
        terminal.emptyLine();
        terminal.displayApproved("Trashed files for &b%s&7: &b%s", email, trashed.size());
        trashed.forEach(item -> terminal.displayApproved("&8- &f%-40s &7%s &8| purge on %s",
                item.file().fileName(),
                UnitParser.parseByteUnit(item.file().sizeBytes()),
                TIMESTAMP.format(Instant.ofEpochMilli(item.purgeAtEpochMillis()))));
        if (trashed.isEmpty()) terminal.displayApproved("&8  (empty)");
        terminal.emptyLine();
    }

}
