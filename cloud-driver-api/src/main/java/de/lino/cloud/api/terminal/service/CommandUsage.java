package de.lino.cloud.api.terminal.service;

import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

/**
 * One way a {@link Command} can be invoked - a sub-command's syntax and a one-line explanation of
 * what it does, e.g. {@code backup now} / "Take a backup right now".
 *
 * <h2>Why this is declared rather than printed</h2>
 *
 * Every command used to print its own syntax block from a private method, reachable only by
 * calling the command with arguments it rejects. Declaring the lines through
 * {@link Command#usages()} keeps that output ({@link Command#sendUsage()} prints exactly the same
 * lines) while making it something {@code help <command>} can print on demand.
 *
 * @param syntax      how the sub-command is typed, e.g. {@code "backup now"} - written out in
 *                    full, including the command's own name, so a line can be copied as it stands
 * @param explanation a short, plain explanation of what that invocation does
 */
public record CommandUsage(@NotNull String syntax, @NotNull String explanation) {

    /**
     * Validates this usage's components.
     *
     * @throws NullPointerException     if either component is {@code null}
     * @throws IllegalArgumentException if {@code syntax} is blank
     */
    public CommandUsage {
        Asserts.requireNonNull(syntax, "@CommandUsage: syntax must not be null");
        Asserts.requireNonNull(explanation, "@CommandUsage: explanation must not be null");

        if (syntax.isBlank()) throw new IllegalArgumentException("@CommandUsage: syntax must not be blank");
    }

    /**
     * One invocation of a command.
     *
     * @param syntax      how it is typed, including the command's own name
     * @param explanation a short, plain explanation of what it does
     * @return the declared usage
     * @throws NullPointerException if {@code syntax} or {@code explanation} is {@code null}
     */
    @NotNull
    public static CommandUsage of(@NotNull final String syntax, @NotNull final String explanation) {
        return new CommandUsage(syntax, explanation);
    }

}
