package de.lino.cloud.api.terminal;

import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandFlag;

import java.util.List;

/**
 * Standalone, runnable worked example (same convention as the other samples) exercising the
 * terminal's flag layer: how {@link Command.CommandArguments} splits a typed line into
 * positionals and flags, what a {@link CommandFlag#valued() valued} flag does to the tokens
 * around it, and which spellings resolve to the same flag. Needs no database, no network, and no
 * terminal - it only parses argument arrays, printing pass/fail per check and exiting non-zero on
 * any failure.
 */
public final class CommandFlagSample {

    /** Folded over every {@link #check(String, boolean)}; decides this sample's exit code. */
    private static boolean allPassed = true;

    /** The flags the imaginary command under test declares. */
    private static final List<CommandFlag> DECLARED = List.of(
            CommandFlag.of("--skip-task", "Skip the follow-up task").withAliases("-s"),
            CommandFlag.valued("--limit", "How many rows to print")
    );

    /** Not instantiable; this sample is a {@code main} method. */
    private CommandFlagSample() {}

    /**
     * Runs every check.
     *
     * @param args unused
     */
    public static void main(final String[] args) {

        // --- a switch is found anywhere on the line and never shifts a positional ---
        final Command.CommandArguments skipped = parse("info", "jane@example.com", "--skip-task");
        check("a trailing flag leaves the positionals alone",
                skipped.length() == 2 && skipped.hasCommand(0, "info") && "jane@example.com".equals(skipped.command(1)));
        check("a switch typed after the positionals is found", skipped.hasFlag("--skip-task"));
        check("a flag that was not typed is absent", !skipped.hasFlag("--limit"));

        final Command.CommandArguments leading = parse("--skip-task", "info", "jane@example.com");
        check("a leading flag leaves the positionals alone",
                leading.length() == 2 && leading.hasCommand(0, "info") && "jane@example.com".equals(leading.command(1)));

        // --- spelling: dashes and case do not matter, and an alias resolves to its declaration ---
        check("a flag is matched without dashes", skipped.hasFlag("skip-task"));
        check("a flag is matched case-insensitively", parse("--SKIP-Task").hasFlag("--skip-task"));
        check("an alias resolves to the declared name", parse("-s").hasFlag("--skip-task"));
        check("the declared flag itself can be queried", parse("-s").hasFlag(DECLARED.get(0)));

        // --- values, attached and space-separated ---
        check("an attached value is read", parse("--limit=25").flagAsInt("--limit", 10) == 25);
        final Command.CommandArguments spaced = parse("list", "--limit", "25");
        check("a declared valued flag consumes the following token",
                spaced.flagAsInt("--limit", 10) == 25 && spaced.length() == 1 && spaced.hasCommand(0, "list"));
        check("a missing value falls back", parse("list").flagAsInt("--limit", 10) == 10);
        check("an unparsable value falls back", parse("--limit=many").flagAsInt("--limit", 10) == 10);
        check("a switch with no value reads as true", parse("--skip-task").flagAsBoolean("--skip-task", false));
        check("an explicitly false value reads as false", !parse("--skip-task=no").flagAsBoolean("--skip-task", true));
        check("a valued flag at the end of the line is present without a value",
                parse("--limit").hasFlag("--limit") && parse("--limit").flag("--limit").isEmpty());

        // --- an undeclared flag stays queryable, but only in its attached form ---
        final Command.CommandArguments undeclared = parse("--dry-run", "report.pdf");
        check("an undeclared switch is still found", undeclared.hasFlag("--dry-run"));
        check("an undeclared flag never swallows the next token",
                undeclared.length() == 1 && "report.pdf".equals(undeclared.command(0)));
        check("an undeclared flag is reported as unknown",
                undeclared.unknownFlags().equals(List.of("dry-run")));
        check("a declared flag is not reported as unknown", parse("-s").unknownFlags().isEmpty());

        // --- shapes that are arguments, not flags ---
        final Command.CommandArguments negative = parse("limit", "-1");
        check("a negative number stays a positional argument",
                negative.length() == 2 && "-1".equals(negative.command(1)) && negative.flagNames().isEmpty());

        // --- the raw line is still available, and joining skips the flags ---
        final Command.CommandArguments query = parse("search", "jane@example.com", "--skip-task", "invoice", "from", "march");
        check("join() concatenates positionals only", "invoice from march".equals(query.join(2)));
        check("args() still returns the line verbatim", query.args().length == 6);
        check("join() past the end is empty", query.join(99).isEmpty());

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** Parses {@code tokens} as if the command under test had been typed with them. */
    private static Command.CommandArguments parse(final String... tokens) {
        return new Command.CommandArguments(tokens, DECLARED);
    }

    /** Prints one check's outcome and folds it into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS  " : "FAIL  ") + description);
        allPassed &= passed;
    }

}
