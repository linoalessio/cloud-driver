package de.lino.cloud.extensions.terminal;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.terminal.service.CommandService;
import de.lino.cloud.extensions.terminal.command.*;

import java.util.logging.Level;

/**
 * Registers the built-in terminal {@link de.lino.cloud.api.terminal.service.Command}s
 * ({@code exit}, {@code help}, {@code clear}, {@code extensions}, {@code about},
 * {@code screen-leave}, {@code dispatch}) on the host {@link de.lino.cloud.api.CloudDriver}'s
 * {@link CommandService}.
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
                , new ExtensionCommand(), new AboutCommand(), new LeaveCommand()
                , new DispatchCommand(), new CloudUserCommand()
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
