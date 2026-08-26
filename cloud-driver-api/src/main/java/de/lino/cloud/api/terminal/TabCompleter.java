package de.lino.cloud.api.terminal;

import de.lino.cloud.api.terminal.command.Command;
import de.lino.cloud.api.terminal.command.CommandService;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * Suggests registered command names while the first word of the input line is being typed.
 *
 * <p>There is no argument-position completion here (unlike a full argument/syntax DSL) - {@code
 * de.lino.cloud.api.terminal} only implements the terminal engine itself, not how individual
 * commands describe their own arguments (see {@link Command}'s Javadoc). Once the first word is
 * complete, no further candidates are offered.
 *
 * <p>Registered as the completer in {@link Terminal} via {@code jline}'s {@code
 * LineReaderBuilder}. Reuses {@link CommandService#registeredCommands()}'s own cached snapshot
 * (see that method's Javadoc) rather than maintaining a second cache here - {@code jline} calls
 * {@link #complete} on every {@code Tab} press, so avoiding a fresh scan of the registry on
 * each one matters.
 */
public final class TabCompleter implements Completer {

    private final CommandService commandService;

    /**
     * @param commandService the registry {@link #complete} suggests command names from
     * @throws NullPointerException if {@code commandService} is {@code null}
     */
    public TabCompleter(@NotNull final CommandService commandService) {
        this.commandService = Asserts.requireNonNull(commandService, "@TabCompleter: commandService must not be null");
    }

    @Override
    public void complete(final LineReader reader, final ParsedLine line, final List<Candidate> candidates) {
        if (line.wordIndex() != 0) return;

        for (final Command command : this.commandService.registeredCommands()) {
            candidates.add(new Candidate(command.name()));
            command.aliases().forEach(alias -> candidates.add(new Candidate(alias)));
        }
    }

}
