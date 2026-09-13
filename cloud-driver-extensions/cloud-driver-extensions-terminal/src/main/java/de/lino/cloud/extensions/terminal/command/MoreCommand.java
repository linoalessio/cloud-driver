package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Prints the next page of whatever a paged command
 * ({@link Terminal#displayPaged(String, java.util.List)}) left waiting - the continuation half of
 * the terminal's pager.
 *
 * <p>It is a command rather than a keypress on purpose: while a command runs, the terminal's
 * reading thread is already blocked inside {@code jline} waiting for the next line, and a second
 * reader on the same terminal would compete with it for every keystroke. Going back through the
 * normal input loop costs the operator four characters and keeps input single-threaded.
 */
public class MoreCommand implements Command {

    /** @return {@code "more"} */
    @Override
    public @NotNull String name() {
        return "more";
    }

    /** @return {@code "m"}, {@code "next"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("m", "next");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Print the next page of a command whose output did not fit on one screen";
    }

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("more", "Print the next page of the last paged command"),
                CommandUsage.of("more all", "Print everything that is still waiting, in one go"),
                CommandUsage.of("more drop", "Throw the waiting output away")
        );
    }

    /**
     * Prints the next page, everything that is left, or nothing at all.
     *
     * @param arguments {@code all} or {@code drop}; the next page when empty
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (arguments.hasCommand(0, "all")) {
            terminal.displayAllPending();
            return;
        }

        if (arguments.hasCommand(0, "drop")) {
            final int dropped = terminal.pendingLines();
            terminal.clearPendingOutput();
            terminal.displayApproved("&8Dropped &b%s &8waiting line(s).", dropped);
            return;
        }

        terminal.displayNextPage();
    }

}
