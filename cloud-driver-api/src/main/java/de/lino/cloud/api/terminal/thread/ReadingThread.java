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

                this.commandService.dispatchAsync(commandName, args).thenAccept(found -> {
                    if (!found) this.terminal.displayApproved("Unknown service provided. Use 'help' for more information.");
                });

            } catch (final UserInterruptException exception) {
                // Ctrl+C - end the loop; the embedding application decides what happens next
                // (e.g. calling Terminal#shutdown()), this thread only stops reading.
                break;
            } catch (final EndOfFileException exception) {
                // Ctrl+D / stdin closed - nothing further to read.
                break;
            } catch (final Throwable throwable) {
                CloudDriver.getInstance().getLogger().log(Level.SEVERE, "@ReadingThread.run: input handling failed", throwable);
            }

        }

    }

}
