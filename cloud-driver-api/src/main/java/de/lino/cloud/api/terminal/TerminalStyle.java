package de.lino.cloud.api.terminal;

/**
 * ANSI text attributes {@link StyledText} renders with, independent of
 * {@link TerminalColor} - a {@link StyledText} can combine a color with any
 * number of these. Each constant only carries its ready-to-print ANSI SGR
 * escape sequence as data, the same reasoning {@link TerminalColor} follows.
 */
public enum TerminalStyle {

    BOLD("\u001B[1m"),
    DIM("\u001B[2m"),
    ITALIC("\u001B[3m"),
    UNDERLINE("\u001B[4m"),
    BLINK("\u001B[5m"),
    REVERSE("\u001B[7m"),
    STRIKETHROUGH("\u001B[9m");

    private final String ansiCode;

    TerminalStyle(final String ansiCode) {
        this.ansiCode = ansiCode;
    }

    /**
     * @return this style's ready-to-print ANSI SGR escape sequence (e.g. {@code "\u001B[1m"} for {@link #BOLD})
     */
    public String ansiCode() {
        return this.ansiCode;
    }

}
