package de.lino.cloud.api.terminal.color;

import de.lino.cloud.api.terminal.Terminal;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Legacy Minecraft-style color codes ({@code &0}-{@code &f}, {@code &r}) translated into ANSI
 * SGR escape sequences for use throughout {@code de.lino.cloud.api.terminal}.
 *
 * <pre>{@code
 * String colored = AnsiColors.translate("&aSuccess&8: &7everything is fine");
 * System.out.println(colored);
 * }</pre>
 */
public enum AnsiColors {

    BLACK("&0", 0, false),
    DARK_BLUE("&1", 4, false),
    DARK_GREEN("&2", 2, false),
    DARK_AQUA("&3", 6, false),
    DARK_RED("&4", 1, false),
    DARK_PURPLE("&5", 5, false),
    GOLD("&6", 3, false),
    GRAY("&7", 7, false),
    DARK_GRAY("&8", 0, true),
    BLUE("&9", 4, true),
    GREEN("&a", 2, true),
    AQUA("&b", 6, true),
    RED("&c", 1, true),
    LIGHT_PURPLE("&d", 5, true),
    YELLOW("&e", 3, true),
    WHITE("&f", 7, true),
    RESET("&r", null, false);

    /** The legacy color code this constant is triggered by (e.g. {@code "&a"}). */
    private final String code;

    /**
     * Precomputed ANSI SGR escape sequence - {@code "\u001b[0m"} for {@link #RESET},
     * otherwise {@code "\u001b[3Xm"}/{@code "\u001b[9Xm"} depending on {@code bright}.
     */
    private final String ansi;

    AnsiColors(final String code, final Integer color, final boolean bright) {
        this.code = code;
        this.ansi = color == null ? "[0m" : "[" + (bright ? "9" : "3") + color + "m";
    }

    /** Regular expression matching any legacy color code this enum recognizes. */
    private static final Pattern COLOR_PATTERN = Pattern.compile("&[0-9a-fr]");

    /** Lookup from a legacy color code (e.g. {@code "&a"}) to its constant. */
    private static final Map<String, AnsiColors> CODE_LOOKUP = Arrays.stream(values())
            .collect(Collectors.toMap(color -> color.code, color -> color));

    /**
     * Translates every legacy color code in {@code message} into its ANSI escape sequence,
     * appending a final {@link #RESET} so the color does not bleed into later output.
     *
     * @param message the text to translate, e.g. {@code "&aOK&8: &7done"}
     * @return {@code message} with every recognized {@code &x} code replaced by its ANSI
     * escape sequence, terminated with a reset sequence
     */
    public static String translate(final String message) {
        return translate(message, true);
    }

    /**
     * Translates every legacy color code in {@code message} into its ANSI escape sequence.
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
