package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandFlag;
import de.lino.cloud.api.terminal.service.CommandService;
import de.lino.cloud.api.terminal.service.CommandUsage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lists every registered {@link Command}. The catalog has grown past the point where a
 * description per line is readable, so the default listing is the identifying metadata only -
 * name, aliases, flags - and the prose is one flag or one lookup away:
 *
 * <ul>
 *     <li>{@code help} - every command's name, aliases, and declared flags</li>
 *     <li>{@code help --description} - the same listing with each description</li>
 *     <li>{@code help <command>} - everything known about one command</li>
 * </ul>
 */
public class HelpCommand implements Command {

    /** Opts the descriptions back into the listing. */
    private static final CommandFlag DESCRIPTION = CommandFlag
            .of("--description", "Print each command's description too").withAliases("--descriptions", "-d");

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

    /** @return {@code --description} */
    @Override
    public @NotNull List<CommandFlag> flags() {
        return List.of(DESCRIPTION);
    }

    /** @return how this command itself is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("help", "List every command: name, aliases, flags"),
                CommandUsage.of("help --description", "The same list, with what each command does"),
                CommandUsage.of("help <command>", "Everything known about one command")
        );
    }

    /**
     * Prints the full catalog, or - if a command name or alias was given - everything known
     * about that one command.
     *
     * @param arguments an optional command name to detail, plus {@code --description}
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final CommandService commandService = terminal.getCommandService();

        if (!arguments.isEmpty()) {
            this.detail(terminal, commandService, arguments.command(0));
            return;
        }

        final boolean withDescriptions = arguments.hasFlag(DESCRIPTION);

        final List<String> lines = new ArrayList<>();
        lines.add(String.format("Registered commands (&b%s&7): ", commandService.snapshot().size()));
        commandService.snapshot().forEach(command -> lines.add(this.line(command, withDescriptions)));

        if (!withDescriptions) {
            lines.add("&8Use &7help --description&8 for what each one does, or &7help <command>&8 for one");
            lines.add("&8command in full - its syntax, sub-commands and flags.");
        }

        terminal.emptyLine();
        terminal.displayPaged(withDescriptions ? "help --description" : "help", lines);

    }

    /**
     * Builds one catalog line: name, aliases, and flag syntax, plus the description when it was
     * asked for.
     *
     * @param command         the command to render
     * @param withDescription whether to append {@code command}'s description
     * @return the rendered line, using {@code &x} legacy ansi codes
     */
    private String line(final Command command, final boolean withDescription) {

        final String aliases = command.aliases().isEmpty() ? "" : " &7(" + String.join(", ", command.aliases()) + ")";
        final String flags = command.flags().isEmpty() ? ""
                : " &8" + String.join(" ", command.flags().stream().map(CommandFlag::usage).toList());

        return "- &b" + command.name() + aliases + flags
                + (withDescription ? " &7| &7" + command.description() : "");
    }

    /**
     * Prints everything known about the command registered under {@code name}: its name, every
     * alias, its description, and every flag it declares with that flag's own description.
     *
     * @param terminal       the terminal to print through
     * @param commandService the registry to resolve {@code name} against
     * @param name           the command name or alias to detail
     */
    private void detail(final Terminal terminal, final CommandService commandService, final String name) {

        final Optional<Command> resolved = commandService.findByName(name);
        if (resolved.isEmpty()) {
            terminal.displayApproved("Unknown command '&b%s&7'. Use &fhelp&7 for the full list.", name);
            return;
        }

        final Command command = resolved.get();

        terminal.emptyLine();
        terminal.displayApproved("Command:     &b%s", command.name());
        terminal.displayApproved("Aliases:     &b%s", command.aliases().isEmpty() ? "-" : String.join(", ", command.aliases()));
        terminal.displayApproved("Description: &7%s", command.description());

        if (!command.usages().isEmpty()) {
            terminal.displayApproved("Usage:");
            command.usages().forEach(usage ->
                    terminal.displayApproved("  &f%-46s &8%s", usage.syntax(), usage.explanation()));
        }

        if (command.flags().isEmpty()) {
            terminal.displayApproved("Flags:       &8none");
            terminal.emptyLine();
            return;
        }

        terminal.displayApproved("Flags:");
        command.flags().forEach(flag -> {
            final String aliases = flag.aliases().isEmpty() ? "" : " &8(" + String.join(", ", flag.aliases()) + ")";
            terminal.displayApproved("  &f%-22s &7%s%s", flag.usage(), flag.description(), aliases);
        });
        terminal.emptyLine();
    }

}
