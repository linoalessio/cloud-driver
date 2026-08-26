package de.lino.cloud.api.terminal.command;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.terminal.Terminal;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A single command a {@link CommandService} can dispatch input to.
 *
 * <p>This is intentionally the entire contract - a name, optional aliases, a description, and
 * an {@link #execute(String[])} action. There is no typed-argument/syntax layer here (unlike,
 * say, a full argument-parsing DSL): {@code de.lino.cloud.api.terminal} only implements the
 * terminal engine itself (reading, coloring, prompt handling, dispatch, tab-completing
 * registered names), not a catalog of concrete commands or how individual commands should
 * parse their own arguments - see {@code TerminalUsageSample} for how a caller wires this up
 * without any concrete {@link Command} implementations registered at all.
 */
public interface Command {

    /**
     * @return this command's primary, case-insensitively matched name
     */
    @NotNull String name();

    /**
     * @return alternative names this command is also reachable under; empty by default
     */
    @NotNull default List<String> aliases() {
        return List.of();
    }

    /**
     * @return a short, human-readable description of what this command does
     */
    @NotNull String description();

    /**
     * Runs this command. Called on a virtual thread dispatched by {@link
     * CommandService#dispatchAsync(String, String[])} - never on the terminal's own reading
     * thread - so a slow implementation does not delay the next line being read.
     *
     * @param args the arguments following the command name, split on whitespace
     */
    void execute(@NotNull String[] args);

    default Terminal terminal() {
        return CloudAPI.getInstance().getTerminal();
    }

}
