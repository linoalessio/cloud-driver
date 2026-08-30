package de.lino.cloud.api.terminal;

import de.lino.cloud.api.terminal.ansi.AnsiColors;
import de.lino.cloud.api.terminal.service.CommandService;
import de.lino.cloud.api.terminal.logging.TerminalLogHandler;
import de.lino.cloud.api.terminal.prompt.DefaultPromptProvider;
import de.lino.cloud.api.terminal.prompt.PromptProvider;
import de.lino.cloud.api.terminal.thread.ReadingThread;
import de.lino.cloud.api.utility.Asserts;
import lombok.NonNull;
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
 * Wraps a {@code jline} terminal, providing output display, prompt management, and service
 * reading - the terminal engine itself, not any concrete service. Requires a real terminal
 * ({@code .dumb(false)}); construction fails in environments with no real pty (e.g. an IDE's
 * console). Every displayed string accepts {@code &x} legacy ansi codes, translated via
 * {@link AnsiColors#translate}.
 */
public final class Terminal {

    /** The underlying {@code jline} terminal this class wraps. */
    private final org.jline.terminal.Terminal terminal;

    /** The {@code jline} line reader backing every prompt/input operation. */
    private final LineReaderImpl lineReader;

    /** The registry {@link Command}s are registered on and dispatched through. */
    private final CommandService commandService = new CommandService();

    /** The background input loop; not started automatically, see {@link #start()}. */
    private final ReadingThread readingThread;

    /** Builds the prompt this terminal starts with and can be {@link #resetPrompt() reset} to. */
    private final PromptProvider promptProvider;

    /** {@code true} from construction until {@link #shutdown()}. */
    private final AtomicBoolean active = new AtomicBoolean(true);

    /** The currently displayed prompt, already ANSI-translated. */
    private volatile String prompt;

    /**
     * Constructs a terminal using {@link DefaultPromptProvider}.
     *
     * @throws UncheckedIOException  if the underlying {@code jline} terminal fails to open
     * @throws IllegalStateException if no working terminal provider is available
     */
    public Terminal() {
        this(new DefaultPromptProvider());
    }

    /**
     * Constructs a terminal with an explicit prompt provider.
     *
     * @param promptProvider builds the prompt this terminal starts with and can be reset to
     * @throws NullPointerException  if {@code promptProvider} is {@code null}
     * @throws UncheckedIOException  if the underlying {@code jline} terminal fails to open
     * @throws IllegalStateException if no working terminal provider is available
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
     * @return the reading thread; not started automatically, see {@link ReadingThread#start()}
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

    /** Clears the entire terminal screen. No-op once {@link #isActive()} is {@code false}. */
    public void clearScreen() {
        if (!this.active.get()) return;

        this.terminal.puts(InfoCmp.Capability.clear_screen);
        this.terminal.flush();
    }

    /**
     * Prints {@code message} above the current prompt line, then redraws it. Prefer
     * {@link #displayApproved(String)} once {@link #readingThread()} is running.
     *
     * <p>Falls back to a plain {@code System.out.println} (no prompt, since the prompt is a
     * terminal-UI artifact with no meaning once the terminal is gone) once {@link #isActive()}
     * is {@code false}, instead of throwing - the same fallback {@link TerminalLogHandler}
     * already uses for log records, hoisted here so every direct caller (not just logging)
     * is safe to call after {@link #shutdown()}, e.g. from an {@code Extension#onEnding()}/
     * {@code #onException(RuntimeException)} that runs a second time after the terminal this
     * process owns has already been closed.
     *
     * @param message the message to display, using {@code &x} legacy ansi codes
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public void display(@NotNull final String message) {
        Asserts.requireNonNull(message, "@Terminal.display: message must not be null");
        final String translated = AnsiColors.translate(String.format("&7%s", message));

        if (!this.active.get()) {
            System.out.println(translated);
            return;
        }

        this.terminal.puts(InfoCmp.Capability.carriage_return);
        this.terminal.writer().println(this.prompt + translated);
        this.terminal.flush();
        update();
    }

    /**
     * {@link #display(String)}, formatting {@code message} with {@code args} via {@link
     * String#format(String, Object...)} first.
     *
     * @param message the message format string, using {@code &x} legacy ansi codes
     * @param args the arguments to substitute into {@code message}
     * @throws NullPointerException if {@code message} or {@code args} is {@code null}
     */
    public void display(@NonNull final String message, @NonNull final Object... args) {
        this.display(String.format(message, args));
    }

    /**
     * Prints {@code message} above the current input line without disturbing what the user is
     * typing. Safe to call while {@link #readingThread()} is blocked reading a line.
     *
     * <p>Same post-{@link #shutdown()} fallback as {@link #display(String)} - see its Javadoc.
     *
     * @param message the message to display, using {@code &x} legacy ansi codes
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public void displayApproved(@NotNull final String message) {
        Asserts.requireNonNull(message, "@Terminal.displayApproved: message must not be null");
        final String translated = AnsiColors.translate(String.format("&7%s", message));

        if (!this.active.get()) {
            System.out.println(translated);
            return;
        }

        this.lineReader.printAbove(this.prompt + translated);
        update();
    }

    /**
     * {@link #displayApproved(String)}, formatting {@code format} with {@code args} via {@link
     * String#format(String, Object...)} first.
     *
     * @param format the message format string, using {@code &x} legacy ansi codes
     * @param args the arguments to substitute into {@code format}
     * @throws NullPointerException if {@code format} or {@code args} is {@code null}
     */
    public void displayApproved(@NonNull final String format, @NonNull final Object... args) {
        this.displayApproved(String.format(format, args));
    }

    /** Prints a single blank line above the current input line. No-op once {@link #isActive()} is {@code false}. */
    public void emptyLine() {
        if (!this.active.get()) return;
        this.lineReader.printAbove(" ");
    }

    /**
     * Prompts with a yes/no {@code message} and blocks until answered. Only {@code y}/{@code
     * yes} (case-insensitive) counts as confirmation; anything else, including blank input, is
     * a rejection.
     *
     * <p>Returns {@code false} immediately, without attempting to read, once {@link
     * #isActive()} is {@code false} - there is no input to read once the underlying terminal
     * has been closed.
     *
     * @param message the confirmation question to display, e.g. {@code "&eProceed? (y/n)"}
     * @return {@code true} if confirmed, {@code false} otherwise (including if the terminal is no longer active)
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public boolean confirm(@NotNull final String message) {
        Asserts.requireNonNull(message, "@Terminal.confirm: message must not be null");
        if (!this.active.get()) return false;

        final String answer = this.lineReader.readLine(AnsiColors.translate(message + " "));
        final String trimmed = answer.trim();
        return trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes");
    }

    /** Redraws the prompt if the reader is currently active. Called after every display. */
    void update() {
        if (!this.lineReader.isReading()) return;
        this.lineReader.callWidget(LineReader.REDRAW_LINE);
        this.lineReader.callWidget(LineReader.REDISPLAY);
    }

    /**
     * Updates the prompt to {@code prompt} (supports {@code &x} ansi codes) and redraws the
     * terminal. {@link #prompt()} still reflects the new value once {@link #isActive()} is
     * {@code false}, but the jline redraw itself is skipped (nothing to redraw).
     *
     * @param prompt the new prompt, using {@code &x} legacy ansi codes
     * @throws NullPointerException if {@code prompt} is {@code null}
     */
    public void updatePrompt(@NotNull final String prompt) {
        Asserts.requireNonNull(prompt, "@Terminal.updatePrompt: prompt must not be null");

        this.prompt = AnsiColors.translate(prompt);
        if (!this.active.get()) return;

        this.lineReader.setPrompt(this.prompt);
        update();
    }

    /** Resets the prompt to this terminal's {@link PromptProvider}. */
    public void resetPrompt() {
        updatePrompt(this.promptProvider.prompt());
    }

    /**
     * Attaches a {@link TerminalLogHandler} to {@code logger} as its sole handler, removing any
     * already installed and disabling parent delegation, so log records route through this
     * terminal instead of a plain console handler.
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

    /** Starts {@link #readingThread()}, beginning the interactive reading loop. */
    public void start() {
        this.readingThread.start();
    }

    /**
     * Closes the underlying {@code jline} terminal and interrupts {@link #readingThread()}.
     * Idempotent; a failure closing the terminal is logged and otherwise ignored.
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
