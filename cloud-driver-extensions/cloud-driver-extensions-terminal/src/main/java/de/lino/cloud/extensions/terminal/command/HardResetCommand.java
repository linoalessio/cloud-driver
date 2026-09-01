package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class HardResetCommand implements Command {

    private static final AtomicBoolean RESET_STARTED = new AtomicBoolean(false);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final AtomicReference<Long> RESET_TIMEOUT = new AtomicReference<>(null);

    @Override
    public @NotNull String name() {
        return "hardReset";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("reset");
    }

    @Override
    public @NotNull String description() {
        return "Clear the entire data set of the cloud driver (&c@not-recommended&7)";
    }

    @Override
    public void execute(@NotNull CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (RESET_STARTED.get() && RESET_TIMEOUT.get() != null) {

            if (RESET_TIMEOUT.get() <= System.currentTimeMillis()) {
                terminal.displayApproved("The &ctimeout &7has been $breached&7. Re-try the reset.");
                RESET_STARTED.set(false);
                RESET_TIMEOUT.set(null);
                return;
            }

            RESET_STARTED.set(false);
            RESET_TIMEOUT.set(null);

            CloudDriver.getInstance().reset();
            terminal.displayApproved("The entire cloud data has been reset.");
            terminal.displayApproved("Shutting down cloud driver...");
            CloudDriver.getInstance().shutdown();

            return;
        }

        RESET_STARTED.set(true);
        RESET_TIMEOUT.set(System.currentTimeMillis() + TIMEOUT.toMillis());
        terminal.displayApproved("To &cdelete the entire cloud data&7, re-run this command for confirmation.");
        terminal.displayApproved("You got &b5 seconds &7to &aconfirm &7before you have to re-try it.");

    }

}
