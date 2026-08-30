package de.lino.cloud.api.terminal.ansi;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Legacy Minecraft-style ansi codes ({@code &0}-{@code &f}, {@code &r}) translated into ANSI
 * SGR escape sequences for use throughout {@code de.lino.cloud.api.terminal}.
 *
 * <pre>{@code
 * String colored = AnsiColors.translate("&aSuccess&8: &7everything is fine");
 * System.out.println(colored);
 * }</pre>
 */
public enum AnsiColors {

    /** Legacy code {@code &0}: standard black. */
    BLACK("&0", 0, false),
    /** Legacy code {@code &1}: standard blue. */
    DARK_BLUE("&1", 4, false),
    /** Legacy code {@code &2}: standard green. */
    DARK_GREEN("&2", 2, false),
    /** Legacy code {@code &3}: standard cyan. */
    DARK_AQUA("&3", 6, false),
    /** Legacy code {@code &4}: standard red. */
    DARK_RED("&4", 1, false),
    /** Legacy code {@code &5}: standard magenta. */
    DARK_PURPLE("&5", 5, false),
    /** Legacy code {@code &6}: standard yellow. */
    GOLD("&6", 3, false),
    /** Legacy code {@code &7}: standard white/gray. */
    GRAY("&7", 7, false),
    /** Legacy code {@code &8}: bright black. */
    DARK_GRAY("&8", 0, true),
    /** Legacy code {@code &9}: bright blue. */
    BLUE("&9", 4, true),
    /** Legacy code {@code &a}: bright green. */
    GREEN("&a", 2, true),
    /** Legacy code {@code &b}: bright cyan. */
    AQUA("&b", 6, true),
    /** Legacy code {@code &c}: bright red. */
    RED("&c", 1, true),
    /** Legacy code {@code &d}: bright magenta. */
    LIGHT_PURPLE("&d", 5, true),
    /** Legacy code {@code &e}: bright yellow. */
    YELLOW("&e", 3, true),
    /** Legacy code {@code &f}: bright white. */
    WHITE("&f", 7, true),

    /** Legacy code {@code &r}: resets every color/style back to the terminal's default. */
    RESET("&r", "[0m"),
    /** Legacy code {@code &i}: italic text style. */
    ITALIC("&i", "[3m"),
    /** Legacy code {@code &u}: underline text style. */
    UNDERLINE("&u", "[4m"),
    /** Legacy code {@code &l}: bold text style. */
    BOLD("&l", "[1m"),
    /** Legacy code {@code &k}: strikethrough text style. */
    STRIKETHROUGH("&k", "[9m"),
    ;

    /** The legacy ansi code this constant is triggered by (e.g. {@code "&a"}). */
    private final String code;

    /**
     * Precomputed ANSI SGR escape sequence - {@code "\u001b[0m"} for {@link #RESET},
     * {@code "\u001b[3Xm"}/{@code "\u001b[9Xm"} for a color depending on {@code bright},
     * or a fixed text-style sequence (e.g. {@code "\u001b[1m"}) for {@link #BOLD} and
     * the other style constants.
     */
    private final String ansi;

    /**
     * Builds a color constant, deriving its ANSI SGR sequence from a base color number and
     * whether it is the bright ({@code 9X}) or standard ({@code 3X}) variant.
     *
     * @param code   the legacy ansi code this constant is triggered by
     * @param color  the base SGR color number (0-7)
     * @param bright {@code true} for the bright ({@code 9X}) variant, {@code false} for standard ({@code 3X})
     */
    AnsiColors(final String code, final Integer color, final boolean bright) {
        this.code = code;
        this.ansi = "[" + (bright ? "9" : "3") + color + "m";
    }

    /**
     * Builds a constant from an explicit, already-complete ANSI escape sequence - used for
     * {@link #RESET} and the text-style constants, which don't fit the color/bright pattern.
     *
     * @param code the legacy ansi code this constant is triggered by
     * @param ansi the full ANSI escape sequence (e.g. {@code "[1m"})
     */
    AnsiColors(final String code, final String ansi) {
        this.code = code;
        this.ansi = ansi;
    }

    /** Regular expression matching any legacy ansi code this enum recognizes. */
    private static final Pattern COLOR_PATTERN = Pattern.compile("&[0-9a-fiklru]");

    /** Lookup from a legacy ansi code (e.g. {@code "&a"}) to its constant. */
    private static final Map<String, AnsiColors> CODE_LOOKUP = Arrays.stream(values())
            .collect(Collectors.toMap(color -> color.code, color -> color));

    /**
     * Translates every legacy ansi code in {@code message} into its ANSI escape sequence,
     * appending a final {@link #RESET} so the ansi does not bleed into later output.
     *
     * @param message the text to translate, e.g. {@code "&aOK&8: &7done"}
     * @return {@code message} with every recognized {@code &x} code replaced by its ANSI
     * escape sequence, terminated with a reset sequence
     */
    public static String translate(final String message) {
        return translate(message, true);
    }

    /**
     * Translates every legacy ansi code in {@code message} into its ANSI escape sequence.
     *
     * @param message the text to translate, e.g. {@code "&aOK&8: &7done"}
     * @param reset   whether to append a final {@link #RESET} sequence
     * @return {@code message} with every recognized {@code &x} code replaced by its ANSI
     * escape sequence
     */
    public static String translate(final String message, final boolean reset) {

        final Matcher matcher = COLOR_PATTERN.matcher(message);
        final StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            final AnsiColors color = CODE_LOOKUP.get(matcher.group());
            matcher.appendReplacement(result, color == null ? matcher.group() : color.ansi);
        }

        matcher.appendTail(result);

        if (reset) result.append(RESET.ansi);
        return result.toString();
    }

}
