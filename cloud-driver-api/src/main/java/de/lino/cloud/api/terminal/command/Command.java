package de.lino.cloud.api.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A single command a {@link CommandService} can dispatch input to: a name, optional aliases, a
 * description, and an {@link #execute(String[])} action. No typed-argument/syntax layer.
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

    /**
     * @return the host application's {@link Terminal}
     */
    default Terminal terminal() {
        return CloudDriver.getInstance().getTerminal();
    }

}
