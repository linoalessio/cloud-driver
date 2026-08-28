package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandService;
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

    /** @return this service's description */
    @Override
    public @NotNull String description() {
        return "Display all available commands";
    }

    /**
     * Prints every registered service's name, aliases, and description.
     *
     * @param arguments unused
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final CommandService commandService = terminal.getCommandService();

        terminal.emptyLine();
        terminal.displayApproved("Registered commands (&b%s&7): ", commandService.snapshot().size());
        commandService.snapshot().forEach(command -> {
            terminal.displayApproved("- &b%s &7(%s) | &7%s", command.name(), String.join(", ", command.aliases()), command.description());
        });
        terminal.emptyLine();

    }

}
