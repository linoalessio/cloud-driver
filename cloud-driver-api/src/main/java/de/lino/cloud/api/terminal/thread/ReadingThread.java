package de.lino.cloud.api.terminal.thread;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.CommandService;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.util.Arrays;
import java.util.logging.Level;

/**
 * Background thread that continuously reads input from a {@link Terminal} and dispatches it
 * through a {@link CommandService} - the interactive loop at the center of the terminal engine.
 * Deliberately not a daemon thread, since it is typically what keeps the process alive. Each
 * line is split on whitespace and dispatched via {@link CommandService#dispatchAsync(String,
 * String[])}; a blank line is skipped, and {@code Ctrl+C}/{@code Ctrl+D} end the loop.
 */
public final class ReadingThread extends Thread {

    /** The owning terminal input is read from and results are displayed through. */
    private final Terminal terminal;

    /** The {@code jline} reader this thread blocks on for each line. */
    private final LineReader lineReader;

    /** The registry each read line's first token is dispatched through. */
    private final CommandService commandService;

    /**
     * Constructed exclusively by {@link Terminal}; obtain an instance via {@link
     * Terminal#readingThread()} rather than constructing one directly.
     *
     * @param terminal       the owning terminal
     * @param lineReader     the {@code jline} reader to block on
     * @param commandService the registry to dispatch input through
     * @throws NullPointerException if any parameter is {@code null}
     */
    public ReadingThread(@NotNull final Terminal terminal, @NotNull final LineReader lineReader, @NotNull final CommandService commandService) {
        super("cli-reading-thread");
        this.terminal = Asserts.requireNonNull(terminal, "@ReadingThread: terminal must not be null");
        this.lineReader = Asserts.requireNonNull(lineReader, "@ReadingThread: lineReader must not be null");
        this.commandService = Asserts.requireNonNull(commandService, "@ReadingThread: commandService must not be null");
    }

    /** Reads and dispatches lines until interrupted or {@code Ctrl+C}/{@code Ctrl+D} is seen. */
    @Override
    public void run() {

        while (!isInterrupted()) {

            try {

                if (!this.isAlive() || isInterrupted()) return;

                final String line = this.lineReader.readLine(this.terminal.prompt()).trim();
                if (line.isEmpty()) continue;

                final String[] tokens = line.split("\\s+");
                final String commandName = tokens[0];
                final String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);

                this.commandService.dispatchAsync(commandName, args)
                        .thenAccept(found -> {
                            if (!found) this.terminal.displayApproved("Unknown service provided. Use 'help' for more information.");
                        })
                        .exceptionally(dispatchFailed -> {
                            // A command that failed in a way its own handler could not catch must
                            // still say so at the prompt, rather than appearing to have done
                            // nothing at all.
                            this.terminal.displayApproved("&cThe command failed unexpectedly: " + dispatchFailed.getMessage());
                            return null;
                        });

            } catch (final UserInterruptException exception) {
                // Ctrl+C discards the half-typed line and returns to the prompt, as it does in
                // every shell. Ending the loop instead left the process running with a console
                // that still printed logs but never read another command - no prompt to notice
                // was gone, and no way back short of restarting the server. The exit command is
                // how this process is stopped.
                continue;
            } catch (final EndOfFileException exception) {
                // Ctrl+D / stdin closed - nothing further to read.
                break;
            } catch (final Throwable throwable) {
                if (!this.terminal.isActive()) break;
                // Terminal#shutdown() closing the underlying jline terminal while this thread is
                // blocked inside readLine() surfaces here as some jline-internal exception (not
                // necessarily observable via isInterrupted() - jline may consume/clear the
                // interrupt status itself while unwinding a blocked read), not as
                // UserInterruptException/EndOfFileException above. Without the isActive() check,
                // every next loop iteration would immediately call readLine() again on the
                // now-permanently-closed terminal, throw again with no blocking in between, and
                // spin - a tight, CPU-burning loop logging the same exception forever instead of
                // ending the same way Ctrl+C/Ctrl+D above do.
                CloudDriver.getInstance().getLogger().log(Level.SEVERE, "@ReadingThread.run: input handling failed", throwable);
            }

        }

    }

}
