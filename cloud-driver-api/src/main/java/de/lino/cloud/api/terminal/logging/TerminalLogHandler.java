package de.lino.cloud.api.terminal.logging;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Routes {@link LogRecord}s through a {@link Terminal} via {@link
 * Terminal#displayApproved(String)}, so log output never corrupts what the user is typing.
 * Falls back to plain {@code System.out} once the terminal is no longer {@link
 * Terminal#isActive() active}. Install via {@link Terminal#attachLogging(java.util.logging.Logger)}.
 */
public final class TerminalLogHandler extends Handler {

    private final Terminal terminal;

    /**
     * @param terminal the terminal every published record is routed through
     * @throws NullPointerException if {@code terminal} is {@code null}
     */
    public TerminalLogHandler(@NotNull final Terminal terminal) {
        this.terminal = Asserts.requireNonNull(terminal, "@TerminalLogHandler: terminal must not be null");
        setFormatter(new TerminalLogFormatter());
    }

    /**
     * Formats and prints {@code record} through the terminal, or {@code System.out} once the
     * terminal is no longer active.
     *
     * @param record the record to publish
     */
    @Override
    public void publish(final LogRecord record) {
        if (!isLoggable(record)) return;

        final String formatted = getFormatter().format(record);

        if (this.terminal.isActive()) {
            this.terminal.displayApproved(formatted);
        } else {
            System.out.print(formatted);
        }
    }

    /** No-op - every {@link #publish(LogRecord)} call writes through immediately. */
    @Override
    public void flush() {
    }

    /** No-op - owns no resources; {@link Terminal#shutdown()} closes the underlying terminal. */
    @Override
    public void close() {
    }

}
