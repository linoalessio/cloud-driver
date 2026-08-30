package de.lino.cloud.api.terminal;

import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandService;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * {@code jline} {@link Completer} suggesting registered service names and aliases while the
 * first word of the input line is being typed. No argument-position completion.
 */
public final class TabCompleter implements Completer {

    /** The registry {@link #complete} suggests registered names/aliases from. */
    private final CommandService commandService;

    /**
     * @param commandService the registry {@link #complete} suggests service names from
     * @throws NullPointerException if {@code commandService} is {@code null}
     */
    public TabCompleter(@NotNull final CommandService commandService) {
        this.commandService = Asserts.requireNonNull(commandService, "@TabCompleter: commandService must not be null");
    }

    /**
     * Adds a candidate for every registered service name and alias, if the first word of the
     * line is still being completed.
     *
     * @param reader     the line reader requesting completion
     * @param line       the parsed input line
     * @param candidates the list to add suggestions to
     */
    @Override
    public void complete(final LineReader reader, final ParsedLine line, final List<Candidate> candidates) {
        if (line.wordIndex() != 0) return;

        for (final Command command : this.commandService.snapshot()) {
            candidates.add(new Candidate(command.name()));
            command.aliases().forEach(alias -> candidates.add(new Candidate(alias)));
        }
    }

}
