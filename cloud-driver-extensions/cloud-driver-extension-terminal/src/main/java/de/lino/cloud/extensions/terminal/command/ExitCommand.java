package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.command.Command;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Shuts down the entire {@link CloudDriver}. */
public class ExitCommand implements Command {

    /** @return {@code "exit"} */
    @Override
    public @NotNull String name() {
        return "exit";
    }

    /** @return {@code "quit"}, {@code "q"}, {@code "leave"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("quit", "q", "leave");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Shutdown the entire cloud system";
    }

    /**
     * Shuts down the {@link CloudDriver}.
     *
     * @param args unused
     */
    @Override
    public void execute(@NotNull String[] args) {

        final CloudDriver cloudDriver = CloudDriver.getInstance();

        this.terminal().displayApproved("Shutting down the cloud system...");
        cloudDriver.shutdown();

    }

}
