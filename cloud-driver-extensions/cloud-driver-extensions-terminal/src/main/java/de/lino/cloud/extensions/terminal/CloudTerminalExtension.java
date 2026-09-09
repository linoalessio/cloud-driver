package de.lino.cloud.extensions.terminal;

import de.lino.cloud.api.extension.Extension;
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
 */
public class CloudTerminalExtension extends Extension {

    /** The host terminal's service registry, resolved once at construction. */
    private final CommandService commandService = this.cloudDriver().getTerminal().getCommandService();

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

        this.commandService.register(
                new ExitCommand(), new HelpCommand(), new ClearCommand()
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
        );

    }

    /** No-op. */
    @Override
    public void onEnding() {
    }

    /**
     * Logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(RuntimeException reason) {

        this.cloudDriver().getLogger().severe("An error occurred while trying to start the cloud terminal extension.");
        this.cloudDriver().getLogger().log(Level.SEVERE, reason.getMessage(), reason);

    }

}
