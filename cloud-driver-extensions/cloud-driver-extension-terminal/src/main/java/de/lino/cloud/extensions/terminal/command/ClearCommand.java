package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.terminal.command.Command;
import de.lino.cloud.api.utility.Constraints;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ClearCommand implements Command {

    @Override
    public @NotNull String name() {
        return "clear";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("clc");
    }

    @Override
    public @NotNull String description() {
        return "Clearing the terminal window";
    }

    @Override
    public void execute(@NotNull String[] args) {

        final CloudAPI cloudAPI = CloudAPI.getInstance();

        this.terminal().clearScreen();
        this.terminal().emptyLine();
        System.out.println(Constraints.CLOUD_DRIVER_BANNER);
        this.terminal().emptyLine();

    }

}
