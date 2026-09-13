package de.lino.cloud.api.terminal.service;

import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One flag a {@link Command} declares it understands, e.g. {@code --skip-task} or
 * {@code --limit=25} - the declarative half of the flag layer, {@link Command.CommandArguments}
 * being the query half.
 *
 * <h2>Why a declaration and not just string matching</h2>
 *
 * A command can always ask {@link Command.CommandArguments#hasFlag(String)} for any flag without
 * declaring it. Declaring it through {@link Command#flags()} buys three things a bare string
 * comparison cannot: {@code help} can print it, tab completion can suggest it, and - the one that
 * actually changes parsing - a {@link #valued() valued} flag may then be written space-separated
 * ({@code --limit 25}), because the parser knows that the following token belongs to the flag
 * rather than being a positional argument. Undeclared flags only ever support the attached form
 * ({@code --limit=25}); there is no way to tell {@code --limit 25} from a flag followed by a
 * positional without knowing the flag takes a value.
 *
 * <p>A flag's spelling is matched case-insensitively and independently of its leading dashes:
 * {@code --skip-task}, {@code -skip-task} and {@code skip-task} all refer to the same flag, so a
 * caller never has to remember how many dashes the declaration used.
 *
 * @param name        this flag's primary spelling, with or without leading dashes
 * @param aliases     alternative spellings this flag is also recognized under
 * @param description a short, human-readable description, shown by {@code help} and completion
 * @param valued      whether this flag carries a value ({@code --limit=25} / {@code --limit 25})
 *                    rather than being a bare on/off switch
 */
public record CommandFlag(@NotNull String name, @NotNull List<String> aliases,
                          @NotNull String description, boolean valued) {

    /**
     * Validates and defensively copies this flag's components.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if {@code name} carries no characters besides dashes
     */
    public CommandFlag {
        Asserts.requireNonNull(name, "@CommandFlag: name must not be null");
        Asserts.requireNonNull(aliases, "@CommandFlag: aliases must not be null");
        Asserts.requireNonNull(description, "@CommandFlag: description must not be null");

        if (normalize(name).isEmpty()) {
            throw new IllegalArgumentException("@CommandFlag: name must contain more than leading dashes");
        }

        aliases = List.copyOf(aliases);
    }

    /**
     * A bare on/off switch, e.g. {@code --skip-task}.
     *
     * @param name        the flag's primary spelling, with or without leading dashes
     * @param description a short, human-readable description
     * @return the declared flag
     * @throws NullPointerException if {@code name} or {@code description} is {@code null}
     */
    @NotNull
    public static CommandFlag of(@NotNull final String name, @NotNull final String description) {
        return new CommandFlag(name, List.of(), description, false);
    }

    /**
     * A flag carrying a value, e.g. {@code --limit=25} or {@code --limit 25}.
     *
     * @param name        the flag's primary spelling, with or without leading dashes
     * @param description a short, human-readable description
     * @return the declared flag
     * @throws NullPointerException if {@code name} or {@code description} is {@code null}
     */
    @NotNull
    public static CommandFlag valued(@NotNull final String name, @NotNull final String description) {
        return new CommandFlag(name, List.of(), description, true);
    }

    /**
     * Returns a copy of this flag that is additionally recognized under {@code aliases}.
     *
     * @param aliases alternative spellings, with or without leading dashes
     * @return a copy carrying {@code aliases}
     * @throws NullPointerException if {@code aliases} is {@code null}
     */
    @NotNull
    public CommandFlag withAliases(@NotNull final String... aliases) {
        Asserts.requireNonNull(aliases, "@CommandFlag.withAliases: aliases must not be null");
        return new CommandFlag(this.name, Arrays.asList(aliases), this.description, this.valued);
    }

    /**
     * @return this flag's primary spelling, dashes stripped and lowercased - the key it is looked
     * up under
     */
    @NotNull
    public String normalizedName() {
        return normalize(this.name);
    }

    /**
     * @return every alias, dashes stripped and lowercased
     */
    @NotNull
    public List<String> normalizedAliases() {
        return this.aliases.stream().map(CommandFlag::normalize).toList();
    }

    /**
     * Whether {@code token} spells this flag's name or one of its aliases, ignoring case and
     * leading dashes.
     *
     * @param token the token to test, e.g. {@code "--skip-task"}
     * @return {@code true} if {@code token} refers to this flag
     * @throws NullPointerException if {@code token} is {@code null}
     */
    public boolean matches(@NotNull final String token) {
        Asserts.requireNonNull(token, "@CommandFlag.matches: token must not be null");

        final String normalized = normalize(token);
        return this.normalizedName().equals(normalized) || this.normalizedAliases().contains(normalized);
    }

    /**
     * @return how this flag is typed on the input line, e.g. {@code "--skip-task"} - the declared
     * spelling if it already carries dashes, otherwise {@code --} for multi-character names and
     * {@code -} for single-character ones
     */
    @NotNull
    public String token() {
        if (this.name.startsWith("-")) return this.name;
        return (this.name.length() == 1 ? "-" : "--") + this.name;
    }

    /**
     * @return this flag's syntax as {@code help} prints it, e.g. {@code "--skip-task"} or
     * {@code "--limit=<value>"}
     */
    @NotNull
    public String usage() {
        return this.valued ? this.token() + "=<value>" : this.token();
    }

    /**
     * Whether {@code token} is shaped like a flag rather than a positional argument: it starts
     * with a dash and the first character after the dashes is a letter.
     *
     * <p>The letter requirement is what keeps a negative number ({@code -1}, {@code -0.5}) a
     * positional argument instead of turning it into a flag nobody declared.
     *
     * @param token the token to classify
     * @return {@code true} if {@code token} is a flag token
     * @throws NullPointerException if {@code token} is {@code null}
     */
    public static boolean isFlagToken(@NotNull final String token) {
        Asserts.requireNonNull(token, "@CommandFlag.isFlagToken: token must not be null");
        if (!token.startsWith("-")) return false;

        final String stripped = stripDashes(token);
        return !stripped.isEmpty() && Character.isLetter(stripped.charAt(0));
    }

    /**
     * Reduces {@code token} to the key a flag is stored and looked up under: leading dashes
     * removed, lowercased, and everything from a {@code =} onwards dropped.
     *
     * @param token the token to normalize, e.g. {@code "--Limit=25"}
     * @return the normalized key, e.g. {@code "limit"}
     * @throws NullPointerException if {@code token} is {@code null}
     */
    @NotNull
    public static String normalize(@NotNull final String token) {
        Asserts.requireNonNull(token, "@CommandFlag.normalize: token must not be null");

        final String stripped = stripDashes(token);
        final int equalsIndex = stripped.indexOf('=');
        final String withoutValue = equalsIndex < 0 ? stripped : stripped.substring(0, equalsIndex);

        return withoutValue.toLowerCase(Locale.ROOT);
    }

    /**
     * @param token the token to strip
     * @return {@code token} without its leading dashes
     */
    private static String stripDashes(final String token) {
        int index = 0;
        while (index < token.length() && token.charAt(index) == '-') index++;
        return token.substring(index);
    }

}
