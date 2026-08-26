package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.command.Command;
import de.lino.cloud.api.terminal.command.CommandService;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class HelpCommand implements Command {

    @Override
    public @NotNull String name() {
        return "help";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("?", "h");
    }

    @Override
    public @NotNull String description() {
        return "Display all available commands";
    }

    @Override
    public void execute(@NotNull String[] args) {

        final Terminal terminal = this.terminal();
        final CommandService commandService = terminal.getCommandService();

        terminal.emptyLine();
        terminal.displayApproved(String.format("Registered commands (&b%s&7): ", commandService.registeredCommands().size()));
        commandService.registeredCommands().forEach(command -> {
            terminal.displayApproved(String.format("- &b%s &7(%s) | &7%s", command.name(), String.join(", ", command.aliases()), command.description()));
        });
        terminal.emptyLine();

    }

}
