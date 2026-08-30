package de.lino.cloud.api.terminal.logging;

import de.lino.cloud.api.terminal.ansi.AnsiColors;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Formats a {@link LogRecord} as a single colored line - {@code HH:mm:ss | LEVEL: message} -
 * plus, if present, the thrown exception's stack trace, all translated through {@link
 * AnsiColors#translate}. {@link TerminalLogHandler} is the only intended caller.
 */
public final class TerminalLogFormatter extends Formatter {

    /** Formats a record's timestamp as {@code HH:mm:ss}. */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * @param record the record to format
     * @return the colored, formatted line for {@code record}
     */
    @Override
    public String format(final LogRecord record) {

        final String time = LocalTime.now().withNano(0).format(TIME_FORMAT);
        final String levelColor = colorFor(record.getLevel());

        final StringBuilder message = new StringBuilder();
        message.append(AnsiColors.translate(
                "&7" + time + " &8(" + levelColor + record.getLevel().getName() + "&8): &7" + formatMessage(record) + "\n"
        ));

        final Throwable thrown = record.getThrown();
        if (thrown != null) appendThrowable(thrown, message);

        return message.toString();
    }

    /** Appends {@code throwable}'s type, message, and stack trace, recursing into its cause. */
    private void appendThrowable(final Throwable throwable, final StringBuilder message) {
        message.append(AnsiColors.translate("&c" + throwable.getClass().getName() + "&8: &7" + throwable.getMessage() + "\n"));

        for (final StackTraceElement element : throwable.getStackTrace()) {
            message.append(AnsiColors.translate(
                    "&7\tat " + element.getClassName() + "." + element.getMethodName()
                            + "(" + element.getFileName() + ":" + element.getLineNumber() + ")\n"
            ));
        }

        final Throwable cause = throwable.getCause();
        if (cause != null) {
            message.append(AnsiColors.translate("&7Caused by:\n"));
            appendThrowable(cause, message);
        }
    }

    /**
     * Picks the {@code &x} legacy ansi color code for a log level's severity.
     *
     * @param level the level to pick a color for
     * @return {@code &c} at/above {@link Level#SEVERE}, {@code &e} at/above {@link
     * Level#WARNING}, {@code &f} at/above {@link Level#INFO}, {@code &b} otherwise
     */
    private String colorFor(final Level level) {
        final int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) return "&c";
        if (value >= Level.WARNING.intValue()) return "&e";
        if (value >= Level.INFO.intValue()) return "&f";
        return "&b";
    }

}
