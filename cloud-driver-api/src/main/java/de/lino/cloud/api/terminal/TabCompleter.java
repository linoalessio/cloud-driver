package de.lino.cloud.api.terminal;

import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandFlag;
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
 * first word of the input line is being typed, and the matched service's declared
 * {@link Command#flags() flags} while a later word starting with a dash is. No completion for
 * positional arguments - their values are data (emails, file ids), not a fixed vocabulary.
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
     * Adds a candidate for every registered service name and alias while the first word of the
     * line is being completed, or for every flag the service on that line declares while a
     * dash-prefixed word is.
     *
     * @param reader     the line reader requesting completion
     * @param line       the parsed input line
     * @param candidates the list to add suggestions to
     */
    @Override
    public void complete(final LineReader reader, final ParsedLine line, final List<Candidate> candidates) {

        if (line.wordIndex() == 0) {
            for (final Command command : this.commandService.snapshot()) {
                candidates.add(new Candidate(command.name()));
                command.aliases().forEach(alias -> candidates.add(new Candidate(alias)));
            }
            return;
        }

        if (!line.word().startsWith("-") || line.words().isEmpty()) return;
        this.commandService.findByName(line.words().get(0))
                .ifPresent(command -> command.flags().forEach(flag -> candidates.add(this.candidate(flag))));

    }

    /**
     * Builds the completion candidate for {@code flag}. A valued flag completes to
     * {@code --limit=} with no trailing space, since the value is typed straight after the
     * {@code =}; a switch completes to {@code --skip-task} followed by a space, since nothing
     * more belongs to it.
     *
     * @param flag the declared flag to suggest
     * @return the candidate, described by the flag's own description
     */
    private Candidate candidate(final CommandFlag flag) {
        final String value = flag.valued() ? flag.token() + "=" : flag.token();
        return new Candidate(value, value, null, flag.description(), null, null, !flag.valued());
    }

}
