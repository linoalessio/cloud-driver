package de.lino.cloud.api.terminal;

/**
 * ANSI foreground text colors {@link StyledText} renders with. Each constant
 * only carries its ready-to-print ANSI SGR escape sequence as data - no I/O,
 * no terminal capability detection - so this is safe to live in {@code
 * cloud-driver-api} alongside every other value object here, the same way
 * {@link de.lino.cloud.api.security.hash.HashAlgorithm} carries algorithm
 * data without performing any hashing itself.
 */
public enum TerminalColor {

    DEFAULT("\u001B[39m"),
    BLACK("\u001B[30m"),
    RED("\u001B[31m"),
    GREEN("\u001B[32m"),
    YELLOW("\u001B[33m"),
    BLUE("\u001B[34m"),
    MAGENTA("\u001B[35m"),
    CYAN("\u001B[36m"),
    WHITE("\u001B[37m"),
    BRIGHT_BLACK("\u001B[90m"),
    BRIGHT_RED("\u001B[91m"),
    BRIGHT_GREEN("\u001B[92m"),
    BRIGHT_YELLOW("\u001B[93m"),
    BRIGHT_BLUE("\u001B[94m"),
    BRIGHT_MAGENTA("\u001B[95m"),
    BRIGHT_CYAN("\u001B[96m"),
    BRIGHT_WHITE("\u001B[97m");

    private final String ansiCode;

    TerminalColor(final String ansiCode) {
        this.ansiCode = ansiCode;
    }

    /**
     * @return this color's ready-to-print ANSI SGR escape sequence (e.g. {@code "\u001B[31m"} for {@link #RED})
     */
    public String ansiCode() {
        return this.ansiCode;
    }

}
