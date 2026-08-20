package de.lino.cloud.api.terminal;

import de.lino.cloud.api.utility.Asserts;
import lombok.NonNull;

import java.util.List;

/**
 * The outcome of dispatching one {@link CommandInput} through {@link
 * Terminal}: the {@link StyledText} lines to print, in order, plus whether
 * the terminal's REPL loop should stop after printing them (only ever {@code
 * true} for {@link TerminalCommand#EXIT}). A pure value object, the same
 * "no I/O, just data {@link Terminal} acts on" shape {@link StyledText}
 * follows.
 */
public final class CommandResult {

    private final List<StyledText> messages;
    private final boolean shouldExit;

    private CommandResult(final List<StyledText> messages, final boolean shouldExit) {
        this.messages = messages;
        this.shouldExit = shouldExit;
    }

    /**
     * @param messages the lines to print, in order
     * @return a {@link CommandResult} that prints {@code messages} and keeps the terminal running
     * @throws NullPointerException if {@code messages} is {@code null}
     */
    @NonNull
    public static CommandResult of(@NonNull final StyledText... messages) {
        return new CommandResult(List.of(Asserts.assertNotNull(messages, "@CommandResult.of: messages cannot be null")), false);
    }

    /**
     * @param messages the lines to print, in order, before the terminal stops
     * @return a {@link CommandResult} that prints {@code messages} and signals the terminal's REPL loop to stop
     * @throws NullPointerException if {@code messages} is {@code null}
     */
    @NonNull
    public static CommandResult exit(@NonNull final StyledText... messages) {
        return new CommandResult(List.of(Asserts.assertNotNull(messages, "@CommandResult.exit: messages cannot be null")), true);
    }

    /**
     * @return the lines to print, in order
     */
    @NonNull
    public List<StyledText> messages() {
        return this.messages;
    }

    /**
     * @return {@code true} if the terminal's REPL loop should stop after printing {@link #messages()}
     */
    public boolean shouldExit() {
        return this.shouldExit;
    }

}
