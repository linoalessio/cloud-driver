package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.command.Command;
import de.lino.cloud.api.terminal.command.CommandService;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Lists every registered {@link Command}, its aliases, and its description. */
public class HelpCommand implements Command {

    /** @return {@code "help"} */
    @Override
    public @NotNull String name() {
        return "help";
    }

    /** @return {@code "?"}, {@code "h"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("?", "h");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Display all available commands";
    }

    /**
     * Prints every registered command's name, aliases, and description.
     *
     * @param args unused
     */
    @Override
    public void execute(@NotNull String[] args) {

        final Terminal terminal = this.terminal();
        final CommandService commandService = terminal.getCommandService();

        terminal.emptyLine();
        terminal.displayApproved(String.format("Registered commands (&b%s&7): ", commandService.snapshot().size()));
        commandService.snapshot().forEach(command -> {
            terminal.displayApproved(String.format("- &b%s &7(%s) | &7%s", command.name(), String.join(", ", command.aliases()), command.description()));
        });
        terminal.emptyLine();

    }

}
