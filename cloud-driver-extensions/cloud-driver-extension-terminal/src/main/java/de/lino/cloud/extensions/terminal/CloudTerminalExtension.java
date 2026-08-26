package de.lino.cloud.extensions.terminal;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.terminal.command.CommandService;
import de.lino.cloud.extensions.terminal.command.*;

import java.util.logging.Level;

public class CloudTerminalExtension extends Extension {

    private final CommandService commandService = this.cloudAPI().getTerminal().getCommandService();

    @Override
    public void onLoading() {

    }

    @Override
    public void onRunning(String[] args) {

        this.commandService.register(new ExitCommand());
        this.commandService.register(new HelpCommand());
        this.commandService.register(new ClearCommand());
        this.commandService.register(new ExtensionCommand());
        this.commandService.register(new AboutCommand());

    }

    @Override
    public void onEnding() {
    }

    @Override
    public void onException(RuntimeException reason) {

        this.cloudAPI().getLogger().severe("An error occurred while trying to start the cloud terminal extension.");
        this.cloudAPI().getLogger().log(Level.SEVERE, reason.getMessage(), reason);

    }

}
