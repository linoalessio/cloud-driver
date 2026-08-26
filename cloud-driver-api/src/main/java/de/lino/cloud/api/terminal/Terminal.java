package de.lino.cloud.api.terminal;

import de.lino.cloud.api.terminal.color.AnsiColors;
import de.lino.cloud.api.terminal.command.CommandService;
import de.lino.cloud.api.terminal.logging.TerminalLogHandler;
import de.lino.cloud.api.terminal.prompt.DefaultPromptProvider;
import de.lino.cloud.api.terminal.prompt.PromptProvider;
import de.lino.cloud.api.terminal.thread.ReadingThread;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps a {@code jline} terminal and provides a high-level API for displaying output, managing
 * the input prompt, and coordinating command reading - the terminal engine itself, not any
 * concrete command. See {@code TerminalUsageSample} for how this class is meant to be used end
 * to end.
 *
 * <p>On construction, connects to the system console with UTF-8 encoding and configures a
 * {@link LineReaderImpl} with tab-completion ({@link TabCompleter}, backed by this instance's
 * own {@link CommandService}) and sensible defaults for an interactive CLI. Nothing is read
 * until {@link #readingThread()}'s returned {@link ReadingThread} is started explicitly - a
 * {@link Terminal} can display output and accept {@link #confirm(String)} answers before
 * that, e.g. while an embedding application is still starting up.
 *
 * <p><b>Requires a real terminal.</b> The underlying {@code jline} builder is constructed with
 * {@code .dumb(false)} - deliberately, matching {@code PoloCloud}'s own {@code Terminal}
 * exactly: rather than silently degrading into a line-buffered "dumb" terminal (no raw input
 * mode, no live tab-completion, no in-place prompt redraw) when no real pseudo-terminal is
 * available, construction fails loudly instead ({@link UncheckedIOException}/{@link
 * IllegalStateException} from {@code jline} itself). This means an actual terminal emulator
 * (a real shell window, or an SSH session) is required - an IDE's Run/Console tool window pipes
 * stdout/stdin rather than allocating a real pty, so constructing a {@link Terminal} there
 * fails the same way; that failure is expected, not a bug.
 *
 * <p><b>Usability.</b> {@link #display(String)}/{@link #displayApproved(String)} both print
 * without corrupting whatever the user is currently typing, {@link #confirm(String)} blocks for
 * a yes/no answer, and {@link #updatePrompt(String)} lets an embedder reflect application state
 * (e.g. a connection name) directly in the prompt.
 *
 * <p><b>ConsoleColoring.</b> Every string this class accepts (prompts, {@link #display(String)}/
 * {@link #displayApproved(String)} output) is translated through {@link AnsiColors#translate},
 * so callers write {@code &x} legacy color codes rather than raw ANSI escapes.
 */
public final class Terminal {

    private final org.jline.terminal.Terminal terminal;
    private final LineReaderImpl lineReader;
    private final CommandService commandService = new CommandService();
    private final ReadingThread readingThread;
    private final PromptProvider promptProvider;

    /**
     * {@code true} from construction until {@link #shutdown()} - checked by {@link
     * TerminalLogHandler} to decide whether it is still safe to route a log line through this
     * terminal's prompt-aware {@link #displayApproved(String)}, or whether it must fall back to
     * a plain {@code System.out} print because the terminal has already been closed.
     */
    private final AtomicBoolean active = new AtomicBoolean(true);

    /**
     * The currently displayed prompt, already translated by {@link AnsiColors#translate}.
     */
    private volatile String prompt;

    /**
     * Constructs a terminal using {@link DefaultPromptProvider}.
     *
     * @throws UncheckedIOException  if the underlying {@code jline} terminal fails to open
     * @throws IllegalStateException if {@code jline} cannot find a working terminal provider
     *                                for the current process (see this class's Javadoc on why a
     *                                real terminal is required)
     */
    public Terminal() {
        this(new DefaultPromptProvider());
    }

    /**
     * @param promptProvider builds the prompt this terminal starts with and can be reset to
     * @throws NullPointerException if {@code promptProvider} is {@code null}
     * @throws UncheckedIOException  if the underlying {@code jline} terminal fails to open
     * @throws IllegalStateException if {@code jline} cannot find a working terminal provider
     *                                for the current process (see this class's Javadoc on why a
     *                                real terminal is required)
     */
    public Terminal(@NotNull final PromptProvider promptProvider) {
        this.promptProvider = Asserts.requireNonNull(promptProvider, "@Terminal: promptProvider must not be null");

        try {

            this.terminal = TerminalBuilder.builder()
                    .system(true)
                    .encoding(StandardCharsets.UTF_8)
                    .dumb(false)
                    .build();

        } catch (final IOException exception) {
            throw new UncheckedIOException("@Terminal: failed to open the system terminal", exception);
        }

        this.lineReader = (LineReaderImpl) LineReaderBuilder.builder()
                .terminal(this.terminal)
                .completer(new TabCompleter(this.commandService))
                .option(LineReader.Option.AUTO_MENU_LIST, true)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.AUTO_PARAM_SLASH, false)
                .variable(LineReader.COMPLETION_STYLE_LIST_SELECTION, "fg:cyan")
                .variable(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "fg:default")
                .variable(LineReader.BELL_STYLE, "none")
                .build();

        this.emptyLine();
        this.updatePrompt(promptProvider.prompt());
        this.readingThread = new ReadingThread(this, this.lineReader, this.commandService);
    }

    /**
     * @return the registry commands are registered on and dispatched through
     */
    @NotNull
    public CommandService getCommandService() {
        return this.commandService;
    }

    /**
     * @return the background thread that reads and dispatches input once started - not started
     * automatically by this constructor, see {@link ReadingThread#start()}
     */
    @NotNull
    public ReadingThread readingThread() {
        return this.readingThread;
    }

    /**
     * @return {@code true} until {@link #shutdown()} has been called
     */
    public boolean isActive() {
        return this.active.get();
    }

    /**
     * @return the currently displayed prompt, already ANSI-translated
     */
    @NotNull
    public String prompt() {
        return this.prompt;
    }

    /**
     * Clears the entire terminal screen.
     */
    public void clearScreen() {
        this.terminal.puts(InfoCmp.Capability.clear_screen);
        this.terminal.flush();
    }

    /**
     * Prints {@code message} (translated via {@link AnsiColors#translate}), moving the cursor
     * to the beginning of the line first to avoid overlapping whatever prompt is currently
     * displayed, then forces a prompt redraw. Prefer {@link #displayApproved(String)} once
     * {@link #readingThread()} is running - this method exists for output printed before the
     * reading loop has started.
     *
     * @param message the message to display, using {@code &x} legacy color codes
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public void display(@NotNull final String message) {
        Asserts.requireNonNull(message, "@Terminal.display: message must not be null");

        this.terminal.puts(InfoCmp.Capability.carriage_return);
        this.terminal.writer().println(this.prompt + AnsiColors.translate(message));
        this.terminal.flush();
        update();
    }

    /**
     * Prints {@code message} (translated via {@link AnsiColors#translate}) above the current
     * input line, without disturbing whatever the user is currently typing. Safe to call while
     * {@link #readingThread()} is actively blocked reading a line - this is what {@link
     * TerminalLogHandler} routes every log line through.
     *
     * @param message the message to display, using {@code &x} legacy color codes
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public void displayApproved(@NotNull final String message) {
        Asserts.requireNonNull(message, "@Terminal.displayApproved: message must not be null");

        this.lineReader.printAbove(this.prompt + AnsiColors.translate(message));
        update();
    }

    /**
     * Prints a single blank line above the current input line.
     */
    public void emptyLine() {
        this.lineReader.printAbove(" ");
    }

    /**
     * Prompts the user with a yes/no {@code message} and blocks the calling thread until
     * answered. Only {@code y}/{@code yes} (case-insensitive) counts as confirmation - any
     * other input, including blank input, is treated as a rejection. Intended for guarding
     * destructive actions behind an explicit confirmation step.
     *
     * @param message the confirmation question to display, e.g. {@code "&eProceed? (y/n)"}
     * @return {@code true} if the user confirmed, {@code false} otherwise
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public boolean confirm(@NotNull final String message) {
        Asserts.requireNonNull(message, "@Terminal.confirm: message must not be null");

        final String answer = this.lineReader.readLine(AnsiColors.translate(message + " "));
        final String trimmed = answer.trim();
        return trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes");
    }

    /**
     * Forces the {@code jline} prompt to redraw if the reader is currently active. Called
     * automatically after every display operation - callers do not need to call this directly.
     */
    void update() {
        if (this.lineReader.isReading()) {
            this.lineReader.callWidget(LineReader.REDRAW_LINE);
            this.lineReader.callWidget(LineReader.REDISPLAY);
        }
    }

    /**
     * Updates the prompt to {@code prompt} (supports {@code &x} color codes) and redraws the
     * terminal.
     *
     * @param prompt the new prompt, using {@code &x} legacy color codes
     * @throws NullPointerException if {@code prompt} is {@code null}
     */
    public void updatePrompt(@NotNull final String prompt) {
        Asserts.requireNonNull(prompt, "@Terminal.updatePrompt: prompt must not be null");

        this.prompt = AnsiColors.translate(prompt);
        this.lineReader.setPrompt(this.prompt);
        update();
    }

    /**
     * Resets the prompt to whatever this terminal's {@link PromptProvider} currently returns.
     */
    public void resetPrompt() {
        updatePrompt(this.promptProvider.prompt());
    }

    /**
     * Attaches a {@link TerminalLogHandler} to {@code logger}, removing every handler already
     * installed on it and disabling parent handler delegation first - the same "single,
     * exclusive handler" approach {@code CloudAPI#getLogger()} takes for its own console
     * handler, applied here so log records are routed through this terminal (colored, prompt-
     * safe) instead of a second, competing plain-text handler.
     *
     * @param logger the logger to route through this terminal
     * @throws NullPointerException if {@code logger} is {@code null}
     */
    public void attachLogging(@NotNull final Logger logger) {
        Asserts.requireNonNull(logger, "@Terminal.attachLogging: logger must not be null");

        for (final var handler : logger.getHandlers()) logger.removeHandler(handler);
        logger.setUseParentHandlers(false);
        logger.addHandler(new TerminalLogHandler(this));
    }

    public void start() {
        this.readingThread.start();
    }

    /**
     * Closes the underlying {@code jline} terminal and interrupts {@link #readingThread()}.
     * Idempotent - calling this more than once has no further effect. A failure closing the
     * underlying terminal is logged and otherwise ignored - by the time {@code shutdown} is
     * called, the terminal is being torn down regardless, so there is nothing a caller could
     * usefully do with that exception.
     */
    public void shutdown() {

        if (!this.active.compareAndSet(true, false)) return;

        try {
            this.terminal.close();
        } catch (final IOException exception) {
            Logger.getLogger(Terminal.class.getName())
                    .log(Level.WARNING, "@Terminal.shutdown: failed to close the terminal cleanly", exception);
        }

        this.readingThread.interrupt();
    }

}
