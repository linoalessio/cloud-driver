package de.lino.cloud.api.terminal.thread;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.command.CommandService;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background thread that continuously reads input from a {@link Terminal} and dispatches it
 * through a {@link CommandService} - the interactive loop at the center of the terminal engine.
 *
 * <p>Deliberately <b>not</b> a daemon thread, unlike every other background thread elsewhere in
 * this codebase ({@code PendingUploadScheduler}, {@code ExtensionFactory#start}'s per-extension
 * threads, {@code PostgresDatabaseNotification}'s listener thread): those all run alongside a
 * real, non-daemon main thread that is itself the thing keeping the JVM alive. A terminal
 * usually has no such thread - its entire purpose is interacting with a human at the console -
 * so this thread being non-daemon is what keeps the process alive for as long as the terminal
 * is open, the same way {@code PoloCloud}'s own reading thread does.
 *
 * <p>Each line is split on whitespace: the first token is looked up (case-insensitively, by
 * name or alias) via {@link CommandService#dispatchAsync(String, String[])}, so a slow command
 * never delays the next line being read; the remaining tokens are passed as {@code args}. A
 * blank line is silently skipped. {@code Ctrl+C} ({@link UserInterruptException}) and
 * {@code Ctrl+D}/EOF ({@link EndOfFileException}) both end the loop; any other exception is
 * logged and the loop continues, so one bad input or a misbehaving command can never silently
 * kill the reading thread.
 */
public final class ReadingThread extends Thread {

    private final Terminal terminal;
    private final LineReader lineReader;
    private final CommandService commandService;

    /**
     * Constructed exclusively by {@link Terminal} itself, against its own {@code jline}
     * reader and {@link CommandService} - obtain an instance via {@link
     * Terminal#readingThread()} rather than constructing one directly.
     */
    public ReadingThread(@NotNull final Terminal terminal, @NotNull final LineReader lineReader, @NotNull final CommandService commandService) {
        super("cli-reading-thread");
        this.terminal = Asserts.requireNonNull(terminal, "@ReadingThread: terminal must not be null");
        this.lineReader = Asserts.requireNonNull(lineReader, "@ReadingThread: lineReader must not be null");
        this.commandService = Asserts.requireNonNull(commandService, "@ReadingThread: commandService must not be null");
    }

    @Override
    public void run() {

        while (!isInterrupted()) {

            try {

                if (!this.isAlive()) return;

                final String line = this.lineReader.readLine(this.terminal.prompt()).trim();
                if (line.isEmpty()) continue;

                final String[] tokens = line.split("\\s+");
                final String commandName = tokens[0];
                final String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);

                this.commandService.dispatchAsync(commandName, args).thenAccept(found -> {
                    if (!found) this.terminal.displayApproved("Unknown command provided. Use 'help' for more information.");
                });

            } catch (final UserInterruptException exception) {
                // Ctrl+C - end the loop; the embedding application decides what happens next
                // (e.g. calling Terminal#shutdown()), this thread only stops reading.
                break;
            } catch (final EndOfFileException exception) {
                // Ctrl+D / stdin closed - nothing further to read.
                break;
            } catch (final Throwable throwable) {
                CloudAPI.getInstance().getLogger().log(Level.SEVERE, "@ReadingThread.run: input handling failed", throwable);
            }

        }

    }

}
