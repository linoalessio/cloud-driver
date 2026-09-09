package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.backup.BackupService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Takes a database backup on demand, and reports whether the scheduled one is actually working.
 *
 * <h2>Why both halves matter</h2>
 *
 * The backup scheduler previously exposed only {@code start}/{@code stop}/{@code shutdown}, which
 * left two obvious things impossible. There was no way to take a backup <em>before</em> a risky
 * operation - a {@code hardReset}, an S3 migration, a schema-affecting deploy - which are exactly
 * the moments one is worth having. And there was no way to tell a scheduler that had been running
 * nightly from one that had been failing nightly, since a failed cycle logs and moves on.
 *
 * <p>A backup you have never confirmed is a backup you do not have.
 */
public class BackupCommand implements Command {

    /** Timestamp format for the status lines - local time, since an operator reads these live. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** @return {@code "backup"} */
    @Override
    public @NotNull String name() {
        return "backup";
    }

    /** @return {@code "db"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("db");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Run a database backup now, or report the outcome of the most recent one";
    }

    /**
     * Dispatches to {@code status} (the default) or {@code now}.
     *
     * @param arguments the sub-command
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final BackupService backupService = CloudDriver.getInstance().getServiceContainer().getBackupService();

        if (backupService == null) {
            terminal.displayApproved("&cThe backup extension is not running &7on this deployment - nothing is being backed up.");
            return;
        }

        if (arguments.hasCommand(0, "now") || arguments.hasCommand(0, "run")) {
            if (backupService.runNow()) {
                terminal.displayApproved("Backup started. Run &fbackup status &7to see the outcome - it is not instant.");
            } else {
                // Refused rather than queued, deliberately: two concurrent full-database reads are
                // exactly the memory pressure this deployment has already been bitten by.
                terminal.displayApproved("&eA backup is already running &7- not starting a second one.");
            }
            return;
        }

        if (arguments.isEmpty() || arguments.hasCommand(0, "status")) {
            this.status(terminal, backupService);
            return;
        }

        terminal.displayApproved("&fbackup status");
        terminal.displayApproved("&fbackup now");
    }

    /** Prints the most recent cycle's outcome. */
    private void status(final Terminal terminal, final BackupService backupService) {
        final BackupService.BackupStatus status = backupService.status();

        terminal.emptyLine();
        if (status.running()) {
            terminal.displayApproved("A backup is &erunning right now&7 (started %s).", this.format(status.startedAtEpochMillis()));
        }

        if (status.startedAtEpochMillis() == null) {
            // Worth stating rather than printing a blank: on a freshly restarted process this is
            // the expected state, and it must not be mistaken for a failure.
            terminal.displayApproved("&7No backup has run &8in this process yet&7 - the schedule triggers one on start.");
            terminal.emptyLine();
            return;
        }

        terminal.displayApproved("Last run started:  &b%s", this.format(status.startedAtEpochMillis()));
        terminal.displayApproved("Last run finished: &b%s", this.format(status.finishedAtEpochMillis()));
        if (!status.running()) {
            terminal.displayApproved("Outcome:           %s", status.succeeded() ? "&asucceeded" : "&cFAILED");
        }
        if (status.detail() != null) {
            terminal.displayApproved("Detail:            &7%s", status.detail());
        }
        terminal.emptyLine();
    }

    /** An epoch-millisecond value as local time, or {@code -} for {@code null}. */
    private String format(final Long epochMillis) {
        return epochMillis == null ? "-" : TIMESTAMP.format(Instant.ofEpochMilli(epochMillis));
    }

}
