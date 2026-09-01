package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A destructive command that wipes every {@code Serialized} entity section the cloud driver
 * defines via {@link CloudDriver#reset()}, then shuts the whole process down via {@link
 * CloudDriver#shutdown()} - with no undo. Requires being run twice within a 5-second
 * confirmation window (the first invocation only arms the reset and prints a warning; a
 * second invocation before the window expires actually performs it) so a single accidental
 * keystroke can't destroy the entire data set.
 */
public class HardResetCommand implements Command {

    /**
     * Whether a reset has been armed by an earlier invocation of {@link #execute} and is
     * still awaiting a confirming second invocation. {@code static}, so this state is shared
     * across every instance of this command (there is normally only one, registered by
     * {@link de.lino.cloud.extensions.terminal.CloudTerminalExtension}).
     */
    private static final AtomicBoolean RESET_STARTED = new AtomicBoolean(false);

    /** How long the confirmation window stays open after a reset is first armed. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * The epoch-millisecond deadline by which a confirming second invocation must arrive, or
     * {@code null} while no reset is currently armed.
     */
    private static final AtomicReference<Long> RESET_TIMEOUT = new AtomicReference<>(null);

    /** @return {@code "hardReset"} */
    @Override
    public @NotNull String name() {
        return "hardReset";
    }

    /** @return {@code "reset"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("reset");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Clear the entire data set of the cloud driver (&c@not-recommended&7)";
    }

    /**
     * On a first invocation, arms the reset (sets a 5-second confirmation deadline) and prints
     * a warning, without touching any data. On a second invocation while a reset is already
     * armed: if the confirmation window has expired, prints a timeout message and disarms
     * (requiring the sequence to be restarted); otherwise disarms, calls {@link
     * CloudDriver#reset()} to wipe every entity section, then calls {@link
     * CloudDriver#shutdown()} to terminate the process.
     *
     * @param arguments unused
     */
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
            terminal.displayApproved("&c&lThe entire cloud data has been reset.");
            terminal.displayApproved("&c&lShutting down cloud driver...");
            CloudDriver.getInstance().shutdown();

            return;
        }

        RESET_STARTED.set(true);
        RESET_TIMEOUT.set(System.currentTimeMillis() + TIMEOUT.toMillis());
        terminal.displayApproved("To &cdelete the entire cloud data&7, re-run this command for confirmation.");
        terminal.displayApproved("You got &b5 seconds &7to &aconfirm &7before you have to re-try it.");

    }

}
