package de.lino.cloud.api.terminal.service;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A single service a {@link CommandService} can dispatch input to: a name, optional aliases, a
 * description, optional {@link #flags() flags} and {@link #usages() usages}, and an
 * {@link #execute(CommandArguments)} action. No typed-argument/syntax layer - a declared usage is
 * documentation ({@code help} prints it, {@link #sendUsage()} prints it back to the operator), not
 * a parser.
 */
public interface Command {

    /**
     * @return this service's primary, case-insensitively matched name
     */
    @NotNull String name();

    /**
     * @return alternative names this service is also reachable under; empty by default
     */
    @NotNull default List<String> aliases() {
        return List.of();
    }

    /**
     * @return a short, human-readable description of what this service does
     */
    @NotNull String description();

    /**
     * The flags this service understands, e.g. {@code --skip-task}. Declaring them is optional -
     * {@link CommandArguments#hasFlag(String)} answers for any flag, declared or not - but a
     * declared flag is printed by {@code help}, suggested by tab completion, and, when it is
     * {@link CommandFlag#valued() valued}, may be written space-separated ({@code --limit 25})
     * instead of only attached ({@code --limit=25}).
     *
     * @return every flag this service understands; empty by default
     */
    @NotNull default List<CommandFlag> flags() {
        return List.of();
    }

    /**
     * Every way this service can be invoked - one entry per sub-command, e.g. {@code backup now}
     * and {@code backup status}. Declaring them puts the command's whole syntax in one place:
     * {@link #sendUsage()} prints it when the operator gets the arguments wrong, and
     * {@code help <command>} prints the same lines on demand.
     *
     * @return every invocation this service understands; empty by default
     */
    @NotNull default List<CommandUsage> usages() {
        return List.of();
    }

    /**
     * Prints this service's declared {@link #usages() usages} to the terminal - what a command
     * shows when it was called with arguments it does not understand. Falls back to the command's
     * name and description when it declares no usage, so the call is always safe to make.
     */
    default void sendUsage() {

        final Terminal terminal = this.terminal();
        if (this.usages().isEmpty()) {
            terminal.displayApproved("&f%s &8- %s", this.name(), this.description());
            return;
        }

        this.usages().forEach(usage -> terminal.displayApproved("&f%-46s &8%s", usage.syntax(), usage.explanation()));
    }

    /**
     * Runs this service. Called on a virtual thread dispatched by {@link
     * CommandService#dispatchAsync(String, String[])} - never on the terminal's own reading
     * thread - so a slow implementation does not delay the next line being read.
     *
     * @param arguments the arguments following the service name, split on whitespace
     */
    void execute(@NotNull final CommandArguments arguments);

    /**
     * @return the host application's {@link Terminal}
     */
    default Terminal terminal() {
        return CloudDriver.getInstance().getTerminal();
    }

    /**
     * The whitespace-split arguments following a command's name, as passed to {@link
     * #execute(CommandArguments)}, split once at construction into <em>positional</em> arguments
     * and <em>flags</em>.
     *
     * <h2>Positionals and flags</h2>
     *
     * A token is a flag when it starts with a dash followed by a letter ({@code --skip-task},
     * {@code -c}); everything else is a positional argument, so a negative number stays an
     * argument. {@link #command(int)}, {@link #hasCommand(int, String)}, {@link #hasLength(int)},
     * {@link #length()} and {@link #isEmpty()} all address positionals only - a flag may
     * therefore be typed anywhere on the line without shifting the positions the command reads,
     * which is the whole point of having flags. {@link #args()} still returns the raw token
     * array, flags included, for the rare caller that wants the line verbatim.
     *
     * <p>A flag's value is written attached ({@code --limit=25}) or, if the command
     * {@link Command#flags() declared} the flag as {@link CommandFlag#valued() valued}, as the
     * following token ({@code --limit 25}) - which is then consumed as the value and not
     * mistaken for a positional. A flag with no value at all reads as present with an empty
     * value: {@link #hasFlag(String)} is {@code true}, {@link #flag(String)} is empty. Flags are
     * matched case-insensitively and independently of their leading dashes, and a flag repeated
     * on one line keeps its last occurrence.
     */
    final class CommandArguments {

        /** Every token following the command's name, verbatim - flags included. */
        private final String[] args;

        /** {@link #args} without its flag tokens (and without any consumed flag values). */
        private final List<String> positionals;

        /**
         * Every flag present, keyed by {@link CommandFlag#normalize(String) normalized} name and,
         * for a declared flag, by every one of its spellings, so a lookup by any alias resolves.
         * A flag without a value maps to an empty string.
         */
        private final Map<String, String> flagValues;

        /** The normalized name each present flag was declared/typed under, in input order. */
        private final Set<String> flagNames;

        /** The flags the executing command declared; empty when constructed without them. */
        private final List<CommandFlag> declaredFlags;

        /**
         * Parses {@code args} without any flag declarations - valued flags are then only
         * recognized in their attached form ({@code --limit=25}).
         *
         * @param args the tokens following the command's name
         * @throws NullPointerException if {@code args} is {@code null}
         */
        public CommandArguments(@NotNull final String[] args) {
            this(args, List.of());
        }

        /**
         * Parses {@code args} against {@code declaredFlags}, which is what {@link
         * CommandService} dispatches with (passing the executing {@link Command#flags()}).
         *
         * @param args          the tokens following the command's name
         * @param declaredFlags the flags the executing command understands
         * @throws NullPointerException if {@code args} or {@code declaredFlags} is {@code null}
         */
        public CommandArguments(@NotNull final String[] args, @NotNull final List<CommandFlag> declaredFlags) {
            this.args = Asserts.requireNonNull(args, "@CommandArguments: args must not be null").clone();
            this.declaredFlags = List.copyOf(Asserts.requireNonNull(declaredFlags, "@CommandArguments: declaredFlags must not be null"));

            this.positionals = new ArrayList<>(this.args.length);
            this.flagValues = new LinkedHashMap<>();
            this.flagNames = new LinkedHashSet<>();

            this.parse();
        }

        /**
         * Splits {@link #args} into {@link #positionals} and {@link #flagValues}, consuming the
         * token after a declared valued flag as that flag's value.
         */
        private void parse() {

            for (int index = 0; index < this.args.length; index++) {

                final String token = this.args[index];
                if (!CommandFlag.isFlagToken(token)) {
                    this.positionals.add(token);
                    continue;
                }

                final CommandFlag declared = this.findDeclared(token);
                final int equalsIndex = token.indexOf('=');

                if (equalsIndex >= 0) {
                    this.putFlag(token, declared, token.substring(equalsIndex + 1));
                    continue;
                }

                // Only a declared valued flag may swallow the next token: without the declaration
                // there is no way to tell "--limit 25" (a value) from "--verbose file.txt" (a
                // switch followed by a positional), and guessing would silently eat arguments.
                final boolean consumesNext = declared != null && declared.valued()
                        && index + 1 < this.args.length && !CommandFlag.isFlagToken(this.args[index + 1]);

                this.putFlag(token, declared, consumesNext ? this.args[++index] : "");
            }

        }

        /**
         * Stores {@code value} under every spelling {@code token} can be looked up by.
         *
         * @param token    the flag token as it was typed
         * @param declared the declaration {@code token} matched, or {@code null} if undeclared
         * @param value    the flag's value, or an empty string if it carries none
         */
        private void putFlag(final String token, final CommandFlag declared, final String value) {

            final String typedName = CommandFlag.normalize(token);
            this.flagNames.add(declared == null ? typedName : declared.normalizedName());

            this.flagValues.put(typedName, value);
            if (declared == null) return;

            this.flagValues.put(declared.normalizedName(), value);
            declared.normalizedAliases().forEach(alias -> this.flagValues.put(alias, value));
        }

        /**
         * @param token the flag token as it was typed
         * @return the declared flag {@code token} spells, or {@code null} if none was declared
         */
        @Nullable
        private CommandFlag findDeclared(final String token) {
            return this.declaredFlags.stream().filter(flag -> flag.matches(token)).findFirst().orElse(null);
        }

        /**
         * @return every token following the command's name, verbatim - flags included, nothing
         * consumed. Prefer {@link #positionals()} unless the raw line is what is actually wanted
         */
        @NotNull
        public String[] args() {
            return this.args.clone();
        }

        /**
         * @return the arguments that are not flags (and were not consumed as a flag's value), in
         * input order - what every index-based accessor on this class addresses
         */
        @NotNull
        public List<String> positionals() {
            return List.copyOf(this.positionals);
        }

        /**
         * Whether the positional argument at {@code index} equals {@code command},
         * case-insensitively.
         *
         * @param index the positional position to check
         * @param command the value to compare against, case-insensitively
         * @return {@code true} if {@code index} is in bounds and matches {@code command}
         */
        public boolean hasCommand(final int index, @NotNull String command) {
            return !this.outOfBounds(index) && this.positionals.get(index).equalsIgnoreCase(command);
        }

        /**
         * Whether {@code index} is a valid, positive positional position.
         *
         * @param index the positional position to check
         * @return {@code true} if {@code index} is greater than zero and within bounds
         */
        public boolean hasLength(final int index) {
            return index > 0 && index < this.positionals.size();
        }

        /**
         * Returns the positional argument at {@code index}.
         *
         * @param index the positional position to read
         * @return the positional argument at {@code index}
         * @throws IllegalArgumentException if {@code index} is out of bounds
         */
        public String command(final int index) {
            if (this.outOfBounds(index)) throw new IllegalArgumentException("@CommandArgs.command: index out of bounds");
            return this.positionals.get(index);
        }

        /**
         * Joins every positional argument from {@code start} onwards with single spaces - a
         * search query typed at a terminal is not one token, and the flags mixed into it are not
         * part of it either.
         *
         * @param start the first positional position to include
         * @return the joined arguments, or an empty string if {@code start} is out of bounds
         */
        @NotNull
        public String join(final int start) {
            if (this.outOfBounds(start)) return "";
            return String.join(" ", this.positionals.subList(start, this.positionals.size()));
        }

        /**
         * @return the number of positional arguments
         */
        public int length() {
            return this.positionals.size();
        }

        /**
         * @return {@code true} if there is no positional argument (flags alone do not count)
         */
        public boolean isEmpty() {
            return this.positionals.isEmpty();
        }

        /**
         * Whether {@code flag} was typed anywhere on the line, ignoring case and leading dashes -
         * {@code hasFlag("--skip-task")}, {@code hasFlag("skip-task")} and, if the command
         * declared it under an alias, that alias all answer the same.
         *
         * @param flag the flag to look for, e.g. {@code "--skip-task"}
         * @return {@code true} if the flag is present, with or without a value
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public boolean hasFlag(@NotNull final String flag) {
            Asserts.requireNonNull(flag, "@CommandArgs.hasFlag: flag must not be null");
            return this.flagValues.containsKey(CommandFlag.normalize(flag));
        }

        /**
         * Whether the declared {@code flag} was typed anywhere on the line, under its name or any
         * of its aliases.
         *
         * @param flag the declared flag to look for
         * @return {@code true} if the flag is present, with or without a value
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public boolean hasFlag(@NotNull final CommandFlag flag) {
            Asserts.requireNonNull(flag, "@CommandArgs.hasFlag: flag must not be null");
            return this.hasFlag(flag.normalizedName());
        }

        /**
         * Whether any of {@code flags} was typed anywhere on the line.
         *
         * @param flags the flags to look for
         * @return {@code true} if at least one is present
         * @throws NullPointerException if {@code flags} is {@code null}
         */
        public boolean hasAnyFlag(@NotNull final String... flags) {
            Asserts.requireNonNull(flags, "@CommandArgs.hasAnyFlag: flags must not be null");

            for (final String flag : flags) if (this.hasFlag(flag)) return true;
            return false;
        }

        /**
         * The value of {@code flag}, e.g. {@code 25} for {@code --limit=25}.
         *
         * @param flag the flag to read
         * @return its value, or {@link Optional#empty()} if the flag is absent or carries no value
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        @NotNull
        public Optional<String> flag(@NotNull final String flag) {
            Asserts.requireNonNull(flag, "@CommandArgs.flag: flag must not be null");

            final String value = this.flagValues.get(CommandFlag.normalize(flag));
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
        }

        /**
         * {@link #flag(String)}, falling back to {@code fallback}.
         *
         * @param flag     the flag to read
         * @param fallback the value to return if the flag is absent or carries no value
         * @return the flag's value, or {@code fallback}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public String flag(@NotNull final String flag, final String fallback) {
            return this.flag(flag).orElse(fallback);
        }

        /**
         * The value of {@code flag} as an {@code int}.
         *
         * @param flag     the flag to read
         * @param fallback the value to return if the flag is absent, carries no value, or is not
         *                 a valid {@code int}
         * @return the parsed value, or {@code fallback}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public int flagAsInt(@NotNull final String flag, final int fallback) {
            try {
                return this.flag(flag).map(Integer::parseInt).orElse(fallback);
            } catch (final NumberFormatException exception) {
                return fallback;
            }
        }

        /**
         * The value of {@code flag} as a {@code long}.
         *
         * @param flag     the flag to read
         * @param fallback the value to return if the flag is absent, carries no value, or is not
         *                 a valid {@code long}
         * @return the parsed value, or {@code fallback}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public long flagAsLong(@NotNull final String flag, final long fallback) {
            try {
                return this.flag(flag).map(Long::parseLong).orElse(fallback);
            } catch (final NumberFormatException exception) {
                return fallback;
            }
        }

        /**
         * The value of {@code flag} as a {@code double}.
         *
         * @param flag     the flag to read
         * @param fallback the value to return if the flag is absent, carries no value, or is not
         *                 a valid {@code double}
         * @return the parsed value, or {@code fallback}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public double flagAsDouble(@NotNull final String flag, final double fallback) {
            try {
                return this.flag(flag).map(Double::parseDouble).orElse(fallback);
            } catch (final NumberFormatException exception) {
                return fallback;
            }
        }

        /**
         * The value of {@code flag} as a {@code boolean}. A flag typed without a value counts as
         * {@code true}, so {@code --force} and {@code --force=true} mean the same thing; only
         * {@code true}/{@code yes}/{@code y}/{@code 1} (case-insensitively) read as {@code true}.
         *
         * @param flag     the flag to read
         * @param fallback the value to return if the flag is absent
         * @return the parsed value, or {@code fallback}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public boolean flagAsBoolean(@NotNull final String flag, final boolean fallback) {
            if (!this.hasFlag(flag)) return fallback;

            final Optional<String> value = this.flag(flag);
            if (value.isEmpty()) return true;

            final String trimmed = value.get().trim();
            return trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("yes")
                    || trimmed.equalsIgnoreCase("y") || trimmed.equals("1");
        }

        /**
         * @return the normalized name of every flag present on the line, in input order
         */
        @NotNull
        public Set<String> flagNames() {
            return Set.copyOf(this.flagNames);
        }

        /**
         * Every flag that was typed but that the executing command never {@link Command#flags()
         * declared} - what a command prints back when an operator mistypes {@code --skiptask},
         * rather than silently doing the opposite of what was asked. Always empty when the
         * command declares no flags at all, since nothing can be judged unknown then.
         *
         * @return the normalized names of the undeclared flags present, in input order
         */
        @NotNull
        public List<String> unknownFlags() {
            if (this.declaredFlags.isEmpty()) return List.of();
            return this.flagNames.stream()
                    .filter(name -> this.declaredFlags.stream().noneMatch(flag -> flag.matches(name)))
                    .toList();
        }

        /**
         * @return the flags the executing command declared, as passed to this instance
         */
        @NotNull
        public List<CommandFlag> declaredFlags() {
            return this.declaredFlags;
        }

        /**
         * @param index the positional position to check
         * @return {@code true} if {@code index} is negative or not less than {@link #length()}
         */
        private boolean outOfBounds(final int index) {
            return index < 0 || index >= this.positionals.size();
        }

        /**
         * @return this instance's positionals and flags, for logging and debugging
         */
        @Override
        public String toString() {
            return "CommandArguments{positionals=" + this.positionals + ", flags=" + this.flagNames + "}";
        }

    }

}
