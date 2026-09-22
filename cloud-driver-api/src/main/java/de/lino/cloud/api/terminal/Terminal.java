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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
     * Lines {@link #displayPaged(String, List)} has queued but not printed yet, oldest first.
     * Guarded by itself - a command thread fills it while the reading thread drains it.
     */
    private final Deque<String> pendingOutput = new ArrayDeque<>();

    /** What {@link #pendingOutput} is the remainder of, e.g. {@code "help --description"}. */
    private volatile String pendingLabel = "";

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

    /**
     * Prints {@code lines} one screen at a time instead of flushing all of them past the top of
     * the window, keeping the rest for {@link #displayNextPage()}.
     *
     * <h2>Why paging and not scrolling</h2>
     *
     * The operator terminal runs inside {@code jline}, which owns the screen while it is reading
     * a line, and typically inside a detached {@code screen} session with little or no scrollback
     * - so output longer than the window is not scrolled back to, it is simply gone. Anything
     * that can print more lines than fit (a full command catalog, an audit trail, a bucket
     * reconciliation) therefore has to stop by itself and wait to be asked for more.
     *
     * <p>The continuation is a normal command ({@code more}), not a keypress: while a command is
     * running, the reading thread is already blocked inside {@code jline}'s {@code readLine},
     * and a second reader on the same terminal would fight it for every keystroke.
     *
     * <p>Queuing is per terminal, so a second paged command replaces whatever the first one had
     * left over - {@code label} is what the footer names, so it is always clear which command's
     * remainder is about to be printed.
     *
     * @param label the command this output belongs to, e.g. {@code "help --description"}
     * @param lines the lines to print, each using {@code &x} legacy ansi codes
     * @throws NullPointerException if {@code label} or {@code lines} is {@code null}
     */
    public void displayPaged(@NotNull final String label, @NotNull final List<String> lines) {
        Asserts.requireNonNull(label, "@Terminal.displayPaged: label must not be null");
        Asserts.requireNonNull(lines, "@Terminal.displayPaged: lines must not be null");

        synchronized (this.pendingOutput) {
            this.pendingOutput.clear();
            this.pendingOutput.addAll(lines);
            this.pendingLabel = label;
        }

        this.displayNextPage();
    }

    /**
     * Prints the next screenful of whatever {@link #displayPaged(String, List)} queued, followed
     * by a footer naming how much is still waiting. Prints a short note instead if nothing is
     * pending.
     */
    public void displayNextPage() {

        final List<String> page;
        final int remaining;

        synchronized (this.pendingOutput) {

            if (this.pendingOutput.isEmpty()) {
                this.displayApproved("&8Nothing more to show.");
                return;
            }

            page = this.take(this.pageSize());
            remaining = this.pendingOutput.size();
        }

        page.forEach(this::displayApproved);
        if (remaining == 0) return;

        this.displayApproved("&8-- &b%s &8more line(s) of '&7%s&8' - type &7more&8 for the next page, &7more all&8 for the rest --",
                remaining, this.pendingLabel);
    }

    /** Prints everything {@link #displayPaged(String, List)} still holds, in one go. */
    public void displayAllPending() {

        final List<String> rest;
        synchronized (this.pendingOutput) {

            if (this.pendingOutput.isEmpty()) {
                this.displayApproved("&8Nothing more to show.");
                return;
            }

            rest = this.take(this.pendingOutput.size());
        }

        rest.forEach(this::displayApproved);
    }

    /**
     * @return {@code true} if {@link #displayPaged(String, List)} left lines unprinted
     */
    public boolean hasPendingOutput() {
        synchronized (this.pendingOutput) {
            return !this.pendingOutput.isEmpty();
        }
    }

    /**
     * @return how many lines are still waiting to be printed
     */
    public int pendingLines() {
        synchronized (this.pendingOutput) {
            return this.pendingOutput.size();
        }
    }

    /**
     * @return what the pending lines are the remainder of, or an empty string if nothing is
     * pending
     */
    @NotNull
    public String pendingLabel() {
        return this.hasPendingOutput() ? this.pendingLabel : "";
    }

    /** Drops whatever {@link #displayPaged(String, List)} still holds, unprinted. */
    public void clearPendingOutput() {
        synchronized (this.pendingOutput) {
            this.pendingOutput.clear();
            this.pendingLabel = "";
        }
    }

    /**
     * Removes and returns the first {@code count} pending lines. Callers hold the
     * {@link #pendingOutput} monitor.
     *
     * @param count how many lines to take
     * @return the taken lines, in order
     */
    private List<String> take(final int count) {

        final List<String> taken = new ArrayList<>(count);
        for (int index = 0; index < count && !this.pendingOutput.isEmpty(); index++) {
            taken.add(this.pendingOutput.pollFirst());
        }

        return taken;
    }

    /**
     * @return how many lines fit on one page - the window height less the prompt and the footer,
     * and never less than five, so a tiny or unreported window still makes progress
     */
    private int pageSize() {
        final int height = this.terminal.getHeight();
        return Math.max(5, height - 3);
    }

    /** Prints a single blank line above the current input line. No-op once {@link #isActive()} is {@code false}. */
    public void emptyLine() {
        if (!this.active.get()) return;
        this.lineReader.printAbove(" ");
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
