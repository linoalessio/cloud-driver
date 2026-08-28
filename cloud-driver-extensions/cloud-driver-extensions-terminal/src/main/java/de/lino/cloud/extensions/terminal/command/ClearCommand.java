package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.utility.Constraints;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Clears the terminal screen and reprints the banner. */
public class ClearCommand implements Command {

    /** @return {@code "clear"} */
    @Override
    public @NotNull String name() {
        return "clear";
    }

    /** @return {@code "clc"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("clc");
    }

    /** @return this service's description */
    @Override
    public @NotNull String description() {
        return "Clearing the terminal window";
    }

    /**
     * Clears the screen and reprints the banner.
     *
     * @param arguments unused
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final CloudDriver cloudDriver = CloudDriver.getInstance();

        this.terminal().clearScreen();
        this.terminal().emptyLine();
        System.out.println(Constraints.CLOUD_DRIVER_BANNER);
        this.terminal().emptyLine();

    }

}
