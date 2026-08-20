package de.lino.cloud.api.terminal;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed, built-in set of commands every {@link Terminal} understands - a
 * closed enum rather than a pluggable registry (unlike {@link
 * de.lino.cloud.api.factory.ExtensionFactory}/{@link
 * de.lino.cloud.api.factory.EventFactory}, which register arbitrary runtime
 * classes), since the terminal is meant to ship complete and ready to use, not
 * extended per-embedder. Each constant only carries its own name and
 * usage/description text as data - {@link Terminal} owns all dispatch logic.
 */
public enum TerminalCommand {

    HELP("help", "Lists every available command."),
    CLEAR("clear", "Clears the terminal screen."),
    EXIT("exit", "Stops the terminal.");

    private final String commandName;
    private final String usage;

    TerminalCommand(final String commandName, final String usage) {
        this.commandName = commandName;
        this.usage = usage;
    }

    /**
     * @return this command's name, as typed at the prompt (e.g. {@code "help"})
     */
    public String commandName() {
        return this.commandName;
    }

    /**
     * @return this command's one-line usage/description text, as shown by {@link #HELP}
     */
    public String usage() {
        return this.usage;
    }

    /**
     * Resolves the first whitespace-separated token of {@code raw} to a
     * {@link TerminalCommand}, matched case-insensitively against {@link
     * #commandName()}.
     *
     * @param raw the raw line of input read from the terminal's prompt
     * @return the matching command, or {@link Optional#empty()} if {@code raw} is blank or matches none
     */
    public static Optional<TerminalCommand> fromInput(final String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        final String token = raw.trim().split("\\s+", 2)[0];
        return Arrays.stream(values()).filter(command -> command.commandName.equalsIgnoreCase(token)).findFirst();
    }

}
