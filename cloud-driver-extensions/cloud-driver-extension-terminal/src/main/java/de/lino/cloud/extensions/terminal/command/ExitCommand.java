package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.terminal.command.Command;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ExitCommand implements Command {

    @Override
    public @NotNull String name() {
        return "exit";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("quit", "q", "leave");
    }

    @Override
    public @NotNull String description() {
        return "Shutdown the entire cloud system";
    }

    @Override
    public void execute(@NotNull String[] args) {

        final CloudAPI cloudAPI = CloudAPI.getInstance();

        this.terminal().displayApproved("Shutting down the cloud system...");
        cloudAPI.shutdown();

    }

}
