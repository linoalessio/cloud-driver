package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

/**
 * Detaches the {@code screen} session this process is running inside, without killing it -
 * the same effect as pressing {@code Ctrl+A+D} on the physical terminal, triggered instead
 * from inside the CLI itself.
 */
public class LeaveCommand implements Command {

    /** @return {@code "screen-leave"} */
    @Override
    public @NotNull String name() {
        return "screen-leave";
    }

    /** @return {@code "l"}, {@code "sl"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("l", "sl");
    }

    /** @return this service's description */
    @Override
    public @NotNull String description() {
        return "Detach the current screen session without killing it";
    }

    /**
     * Shells out to {@code screen -X -S $STY detach}, targeting the current session via the
     * {@code STY} environment variable {@code screen} sets for every process running inside
     * one. No-ops with a message if {@code STY} is unset (not running inside {@code screen}).
     *
     * @param arguments unused
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final String sessionName = System.getenv("STY");
        if (sessionName == null || sessionName.isBlank()) {
            this.terminal().displayApproved("&cNot running inside a screen session (STY is not set).");
            return;
        }

        try {

            final Process process = new ProcessBuilder("screen", "-X", "-S", sessionName, "detach")
                    .redirectErrorStream(true)
                    .start();
            final int exitCode = process.waitFor();

            if (exitCode != 0)
                this.terminal().displayApproved("&cFailed to detach screen session '" + sessionName + "' (exit code " + exitCode + ").");

        } catch (final IOException e) {
            this.terminal().displayApproved("&cFailed to detach screen session: " + e.getMessage());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.terminal().displayApproved("&cInterrupted while detaching screen session.");
        }

    }

}
