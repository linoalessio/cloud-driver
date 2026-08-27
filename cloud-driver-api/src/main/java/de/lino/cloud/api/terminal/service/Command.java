package de.lino.cloud.api.terminal.service;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A single service a {@link CommandService} can dispatch input to: a name, optional aliases, a
 * description, and an {@link #execute(String[])} action. No typed-argument/syntax layer.
 */
public interface Command {

    /**
     * @return this service's primary, case-insensitively matched name
     */
    @NotNull String name();

    /**
     * @return alternative names this service is also reachable under; empty by default
     */
    @NotNull default List<String> aliases() {
        return List.of();
    }

    /**
     * @return a short, human-readable description of what this service does
     */
    @NotNull String description();

    /**
     * Runs this service. Called on a virtual thread dispatched by {@link
     * CommandService#dispatchAsync(String, String[])} - never on the terminal's own reading
     * thread - so a slow implementation does not delay the next line being read.
     *
     * @param arguments the arguments following the service name, split on whitespace
     */
    void execute(@NotNull final CommandArguments arguments);

    /**
     * @return the host application's {@link Terminal}
     */
    default Terminal terminal() {
        return CloudDriver.getInstance().getTerminal();
    }

    public record CommandArguments(@NotNull String[] args) {

        public boolean hasCommand(final int index, @NotNull String command) {
            return !this.outOfBounds(index) && this.args[index].equalsIgnoreCase(command);
        }

        public boolean hasLength(final int index) {
            return index > 0 && index < args.length;
        }

        public String command(final int index) {
            if (this.outOfBounds(index)) throw new IllegalArgumentException("@CommandArgs.command: index out of bounds");
            return this.args[index];
        }

        public int length() {
            return this.args.length;
        }

        private boolean outOfBounds(final int index) {
            return index < 0 || index >= args.length;
        }

    }

}
