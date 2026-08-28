package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Dispatches an arbitrary Linux service (e.g. {@code sleep}, {@code kill}) via {@link
 * ProcessBuilder}, printing its combined stdout/stderr and exit code to the terminal.
 */
public class DispatchCommand implements Command {

    /** @return {@code "dispatch"} */
    @Override
    public @NotNull String name() {
        return "dispatch";
    }

    /** @return {@code "exec"}, {@code "sudo"}, {@code "d"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("exec", "sudo", "d");
    }

    /** @return this service's description */
    @Override
    public @NotNull String description() {
        return "Dispatch a linux service through the system-terminal";
    }

    /**
     * Runs {@code arguments} as a service (its first element is the executable, the rest its
     * arguments - exactly as the reading thread already split the input line), streaming
     * its combined stdout/stderr to the terminal as it runs, then printing its exit code.
     *
     * @param arguments the service and its arguments; a no-op with a usage message if empty
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        if (arguments.length() == 0) {
            this.terminal().displayApproved("&fdispatch <service> [args...]");
            return;
        }

        try {
            final Process process = new ProcessBuilder(arguments.args())
                    .redirectErrorStream(true)
                    .start();

            try (final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    this.terminal().displayApproved(String.format("&f&l%s", line));
                }
            }

            final int exitCode = process.waitFor();
            this.terminal().displayApproved(exitCode == 0
                    ? "Process exited with code &a&l" + exitCode
                    : "Process exited with code &c&l" + exitCode);

        } catch (final IOException e) {
            this.terminal().displayApproved("&7Failed to dispatch service: &c" + e.getMessage());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.terminal().displayApproved("&cInterrupted while waiting for the service to finish.");
        }

    }

}
