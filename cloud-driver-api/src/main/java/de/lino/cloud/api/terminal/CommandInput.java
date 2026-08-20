package de.lino.cloud.api.terminal;

import java.util.List;

/**
 * One parsed line of {@link Terminal} input: which {@link TerminalCommand}
 * was typed, its whitespace-separated arguments (excluding the command name
 * itself), and the original raw line - kept around so a command handler can
 * report back exactly what a user typed without re-deriving it.
 *
 * @param command the resolved command
 * @param arguments the command's arguments, in order, excluding the command name itself
 * @param raw the original, unparsed line of input
 */
public record CommandInput(TerminalCommand command, List<String> arguments, String raw) {

    /**
     * @param index the zero-based argument index
     * @return the argument at {@code index}, or {@code null} if fewer than {@code index + 1} arguments were given
     */
    public String argumentOrNull(final int index) {
        return index >= 0 && index < this.arguments.size() ? this.arguments.get(index) : null;
    }

}
