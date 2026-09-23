package de.lino.cloud.extensions.terminal;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandService;
import de.lino.cloud.extensions.terminal.command.*;
import de.lino.cloud.extensions.terminal.command.system.*;

import java.util.logging.Level;

/**
 * Registers every built-in terminal {@link de.lino.cloud.api.terminal.service.Command} on the host
 * {@link de.lino.cloud.api.CloudDriver}'s {@link CommandService}.
 *
 * <p>Two groups. The original set covers the terminal itself and account administration
 * ({@code exit}, {@code help}, {@code clear}, {@code extensions}, {@code statistics},
 * {@code screen-leave}, {@code dispatch}, {@code cloudUser}, {@code hardReset}, {@code admin},
 * {@code recomputeStorage}, {@code auditLog}, {@code migrateToS3}).
 *
 * <p>The second group is diagnostics and operations ({@code health}, {@code intelligence},
 * {@code mail}, {@code s3}, {@code scan}, {@code searchIndex}, {@code rateLimit}, {@code config},
 * {@code backup}, {@code trash}, {@code file}, {@code session}, {@code share}, {@code reload}).
 * These exist because this system fails <b>open</b> in many places - an unreachable scanner, a
 * mail sender that delivers nothing, an unpublished facet - and every one of those failures is
 * otherwise silent. Several were written directly in response to real incidents on this
 * deployment; each command's own Javadoc names the one it answers.
 *
 * <p>Every registration is released again on stop, so this extension can be restarted: the names
 * and aliases it claimed are freed, and a later start registers a fresh set rather than failing on
 * the first duplicate.
 */
public class CloudTerminalExtension extends Extension {

    /** The host terminal's service registry, resolved once at construction. */
    private final CommandService commandService = this.cloudDriver().getTerminal().getCommandService();

    /**
     * Every command instance this extension registered, held so {@link #onEnding()} can hand the
     * exact same instances back to {@link CommandService#unregister(Command...)}. {@code null}
     * until {@link #onRunning(String[])} has built them.
     */
    private Command[] registeredCommands;

    /** No-op. */
    @Override
    public void onLoading() {

    }

    /**
     * Registers every built-in terminal service.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {

        final Command[] commands = {
                new ExitCommand(), new HelpCommand(), new MoreCommand(), new ClearCommand()
                , new ExtensionCommand(), new StatisticsCommand(), new LeaveCommand()
                , new DispatchCommand(), new CloudUserCommand(), new HardResetCommand()
                , new AdminCommand(), new RecomputeStorageCommand(), new AuditLogCommand()
                , new MigrateToS3Command()
                // Diagnostics and operations. Every one of these reaches an optional facet through
                // IServiceContainer/IFactoryContainer and null-checks it, so registering them all
                // unconditionally is safe on a deployment running none of the extensions behind
                // them - each simply reports that the subsystem is absent, which is itself the
                // most useful thing it can say.
                , new HealthCommand(), new IntelligenceCommand(), new MailCommand()
                , new S3Command(), new ScanCommand(), new SearchIndexCommand()
                , new RateLimitCommand(), new ConfigCommand(), new BackupCommand()
                , new TrashCommand(), new FileCommand(), new SessionCommand()
                , new ShareCommand(), new ReloadCommand()
        };
        // Recorded before registration, so a partially-completed register still releases whatever
        // did get registered when onException runs.
        this.registeredCommands = commands;
        this.commandService.register(commands);

    }

    /**
     * Unregisters every command this extension registered, freeing each name and alias so a later
     * start can register a fresh set. Without this the console keeps answering commands owned by a
     * stopped extension, and the next start fails on the first duplicate name.
     */
    @Override
    public void onEnding() {
        this.releaseCommands();
    }

    /** Unregisters {@link #registeredCommands}, if any were ever registered; idempotent. */
    private void releaseCommands() {
        if (this.registeredCommands == null) return;
        this.commandService.unregister(this.registeredCommands);
        this.registeredCommands = null;
    }

    /**
     * Logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(RuntimeException reason) {

        this.releaseCommands();
        this.cloudDriver().getLogger().severe("An error occurred while trying to start the cloud terminal extension.");
        this.cloudDriver().getLogger().log(Level.SEVERE, reason.getMessage(), reason);

    }

}
