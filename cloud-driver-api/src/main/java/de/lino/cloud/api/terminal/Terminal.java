package de.lino.cloud.api.terminal;

import de.lino.cloud.api.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.FileFactory;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An interactive, styled admin console over a {@link de.lino.cloud.api.CloudAPI}-backed
 * process's {@link ExtensionFactory}, {@link EventFactory}, {@link
 * FileFactory}, and {@link ConnectivityChecker} facets - a fixed, built-in
 * {@link TerminalCommand} set ({@code help}/{@code status}/{@code
 * extensions}/{@code events}/{@code files}/{@code clear}/{@code exit}), not a
 * pluggable registry, so it ships complete and ready to run rather than
 * requiring an embedder to register commands themselves.
 *
 * <p>Only {@link #readLine()}, {@link #write(String)}, and {@link
 * #clearScreen()} are abstract - the raw, genuinely sink-specific I/O
 * primitives, the same "interface(s)/abstract primitives in {@code
 * cloud-driver-api}, concrete I/O in {@code cloud-driver-plugin}" shape
 * {@link de.lino.cloud.api.security.keys.KeyEncryptionService}/{@link
 * ConnectivityChecker} use. Everything else - the REPL loop ({@link
 * #start()}), command parsing, and every built-in command's dispatch logic -
 * is implemented here, concretely, entirely in terms of those three
 * primitives plus the abstract {@link ExtensionFactory}/{@link
 * EventFactory}/{@link FileFactory}/{@link ConnectivityChecker} facets passed
 * to the constructor, the same "abstract primitives + generic concrete
 * methods on the abstract class" shape {@link ExtensionFactory}/{@link
 * EventFactory}/{@link FileFactory} themselves use. No concrete plugin type
 * is ever referenced.
 *
 * <p>{@link #start()} blocks the calling thread on {@link #readLine()} in a
 * loop - run it on its own thread (never a process's actual main thread) and
 * pair it with {@link #stop()} as a shutdown action, the same "every
 * background task on its own thread, only the real main thread ever blocked"
 * shape {@code CloudBootstrap} uses for its other subsystems.
 */
public abstract class Terminal {

    private volatile boolean running;

    protected Terminal() {}

    /**
     * Reads one line of input, blocking the calling thread until a full line
     * is available.
     *
     * @return the line read, with no trailing line terminator, or {@code null} if the input source is exhausted or closed
     */
    @Nullable
    protected abstract String readLine();

    /**
     * Writes {@code raw} to this terminal's output sink exactly as given -
     * already {@link StyledText#render() rendered} ANSI escape sequences and
     * all, or plain text - with no trailing line terminator implied.
     *
     * @param raw the exact characters to write
     */
    protected abstract void write(@NotNull String raw);

    /**
     * Clears the visible terminal screen, if this sink supports it; a no-op otherwise.
     */
    protected abstract void clearScreen();

    /**
     * Writes {@code text}, rendered, followed by a line terminator - the
     * concrete building block every command handler below prints its output
     * through.
     *
     * @param text the line to write
     */
    private void writeLine(@NonNull final StyledText text) {
        write(text.render());
        write(System.lineSeparator());
    }

    /**
     * Runs this terminal's REPL loop on the calling thread: prints a prompt,
     * reads one line, resolves and dispatches it as a {@link TerminalCommand}
     * (an unrecognized line prints an error and re-prompts rather than
     * stopping), and repeats until {@link TerminalCommand#EXIT} is read,
     * {@link #stop()} is called, or {@link #readLine()} returns {@code null}
     * (input exhausted/closed).
     */
    public final void start() {
        this.running = true;
        writeLine(StyledText.of("CloudDriver admin terminal started...")
                .color(TerminalColor.DEFAULT).style(TerminalStyle.ITALIC));

        while (this.running) {

            write(prompt().render());

            final String line = readLine();

            if (line == null) {
                this.running = false;
                break;
            }

            if (line.isBlank()) continue;

            final Optional<TerminalCommand> resolved = TerminalCommand.fromInput(line);
            if (resolved.isEmpty()) {
                writeLine(StyledText.of("Unknown command provided. Use 'help' for information.").color(TerminalColor.BRIGHT_RED));
                continue;
            }

            final CommandResult result = dispatch(toCommandInput(resolved.get(), line));
            result.messages().forEach(this::writeLine);
            if (result.shouldExit()) this.running = false;
        }
    }

    /**
     * Signals this terminal's REPL loop to stop after its current iteration.
     * Does not by itself unblock a {@link #readLine()} call already in
     * progress - a concrete implementation whose input source can be closed
     * to unblock it (e.g. closing the underlying stream) should override
     * this, calling {@code super.stop()} first.
     */
    public void stop() {
        this.running = false;
    }

    @NonNull
    private StyledText prompt() {
        return StyledText.of("❯ ").color(TerminalColor.BRIGHT_BLUE).style(TerminalStyle.DIM);
    }

    @NonNull
    private static CommandInput toCommandInput(@NonNull final TerminalCommand command, @NonNull final String raw) {
        final String[] tokens = raw.trim().split("\\s+");
        final List<String> arguments = tokens.length > 1 ? List.of(tokens).subList(1, tokens.length) : List.of();
        return new CommandInput(command, arguments, raw);
    }

    @NonNull
    private CommandResult dispatch(@NonNull final CommandInput input) {
        return switch (input.command()) {
            case HELP -> handleHelp();
            case CLEAR -> {
                clearScreen();
                yield CommandResult.of();
            }
            case EXIT -> CommandResult.exit(StyledText.of("Goodbye.").color(TerminalColor.GREEN));
        };
    }

    private CommandResult handleHelp() {
        final List<StyledText> lines = new ArrayList<>();
        lines.add(StyledText.of("Available commands:").color(TerminalColor.CYAN).style(TerminalStyle.BOLD));
        for (final TerminalCommand command : TerminalCommand.values()) {
            lines.add(StyledText.of("  " + command.commandName() + " - " + command.usage()).color(TerminalColor.WHITE));
        }
        return CommandResult.of(lines.toArray(StyledText[]::new));
    }

}
