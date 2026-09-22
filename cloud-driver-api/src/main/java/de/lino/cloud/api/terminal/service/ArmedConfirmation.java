package de.lino.cloud.api.terminal.service;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The arm-then-confirm guard a {@link Command} puts in front of an invocation that destroys data
 * or grants privilege: the first invocation prints what would happen and arms, and only a second
 * invocation - carrying the explicit word {@code confirm}, naming the same target, inside the
 * window - performs it.
 *
 * <p><b>Why a second command rather than a prompt.</b> The terminal has exactly one reader thread,
 * and while a command runs that thread is already inside {@code jline}'s {@code readLine} waiting
 * for the next line. A command that tried to read an answer of its own would be a second reader on
 * the same terminal, fighting the first for every keystroke - so a confirmation here is a second
 * command, never a second read. It is also why the confirming word is a positional token rather
 * than a flag: a flag may be typed anywhere on the line, which would make a single line both arm
 * and fire, recallable from history as one self-contained destructive command.
 *
 * <p><b>One instance per command.</b> Arming is keyed on the exact command line, and each command
 * holds its own instance, so arming one command never disarms another and arming against one
 * target can never confirm an action against a different one. Anything typed in between leaves an
 * armed action armed but unfired; it is not cancellable, it simply expires.
 */
public final class ArmedConfirmation {

    /** How long an armed invocation stays confirmable. */
    private final Duration window;

    /** The exact command line currently armed, or {@code null} while nothing is. */
    private final AtomicReference<String> armed = new AtomicReference<>();

    /** When the armed invocation stops being confirmable, in epoch millis, or {@code null}. */
    private final AtomicReference<Long> armedUntil = new AtomicReference<>();

    /**
     * Creates a guard with its own arm slot.
     *
     * @param window how long an armed invocation stays confirmable
     * @throws NullPointerException if {@code window} is {@code null}
     */
    public ArmedConfirmation(@NotNull final Duration window) {
        this.window = Asserts.requireNonNull(window, "@ArmedConfirmation: window must not be null");
    }

    /**
     * Either arms {@code commandLine} and prints what it would do, or - on an invocation carrying
     * {@code confirm} at {@code confirmIndex}, for this same command line, inside the window -
     * reports that the caller may proceed.
     *
     * <p>A confirming invocation that finds nothing armed, something else armed, or an expired
     * window clears the arm slot and refuses: a {@code confirm} recalled from history minutes
     * later can never act, and can never arm either.
     *
     * @param terminal where the warning and the refusal are printed
     * @param arguments the invocation, checked for the confirming token
     * @param confirmIndex the positional index the confirming token would occupy
     * @param commandLine the exact form to re-run to confirm, and the key arming is held under
     * @param whatItDoes a plain description of what confirming would do, shown while arming -
     *     including whether it can be undone, which differs between the commands that use this
     * @return {@code true} if the caller should proceed, {@code false} if it must return
     * @throws NullPointerException if any argument is {@code null}
     */
    public boolean armOrConfirm(@NotNull final Terminal terminal,
                                @NotNull final Command.CommandArguments arguments,
                                final int confirmIndex,
                                @NotNull final String commandLine,
                                @NotNull final String whatItDoes) {

        Asserts.requireNonNull(terminal, "@ArmedConfirmation.armOrConfirm: terminal must not be null");
        Asserts.requireNonNull(arguments, "@ArmedConfirmation.armOrConfirm: arguments must not be null");
        Asserts.requireNonNull(commandLine, "@ArmedConfirmation.armOrConfirm: commandLine must not be null");
        Asserts.requireNonNull(whatItDoes, "@ArmedConfirmation.armOrConfirm: whatItDoes must not be null");

        final boolean confirming = arguments.hasCommand(confirmIndex, "confirm");
        final Long deadline = this.armedUntil.get();

        if (confirming && commandLine.equals(this.armed.get()) && deadline != null && deadline > System.currentTimeMillis()) {
            this.armed.set(null);
            this.armedUntil.set(null);
            return true;
        }

        if (confirming) {
            terminal.displayApproved("&cNothing armed for that, or the confirmation window has passed - run it again without 'confirm' first.");
            this.armed.set(null);
            this.armedUntil.set(null);
            return false;
        }

        this.armed.set(commandLine);
        this.armedUntil.set(System.currentTimeMillis() + this.window.toMillis());
        terminal.displayApproved("&c&lThis %s", whatItDoes);
        terminal.displayApproved("&7To confirm, run &c%s confirm &7within &b%s seconds&7.", commandLine, this.window.toSeconds());
        return false;

    }

}
