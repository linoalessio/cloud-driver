package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Shuts down the entire {@link CloudDriver}. */
public class ExitCommand implements Command {

    /** @return {@code "exit"} */
    @Override
    public @NotNull String name() {
        return "exit";
    }

    /** @return {@code "quit"}, {@code "q"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("quit", "q");
    }

    /** @return this service's description */
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
    public void execute(@NotNull final CommandArguments arguments) {

        final CloudDriver cloudDriver = CloudDriver.getInstance();

        this.terminal().displayApproved("Shutting down the cloud system...");
        cloudDriver.shutdown();

    }

}
