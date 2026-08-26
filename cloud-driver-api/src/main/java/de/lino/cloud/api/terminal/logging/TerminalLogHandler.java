package de.lino.cloud.api.terminal.logging;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Routes {@link LogRecord}s through a {@link Terminal}, so log output never corrupts
 * whatever the user is currently typing at the prompt.
 *
 * <p>While the terminal is still {@link Terminal#isActive() active}, every record is
 * formatted (via {@link TerminalLogFormatter}, the handler's default) and printed through
 * {@link Terminal#displayApproved(String)}. Once the terminal has been shut down, records
 * instead fall back to a plain {@code System.out} print - {@code displayApproved} requires a
 * live {@code jline} reader, which no longer exists once {@link Terminal#shutdown()} has
 * run, so shutdown-time log output (e.g. from a shutdown hook) still reaches the console
 * instead of being silently dropped or throwing.
 *
 * <p>Install via {@link Terminal#attachLogging(java.util.logging.Logger)} rather than
 * constructing and attaching this directly, unless a non-exclusive handler setup is needed.
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

    @Override
    public void flush() {
        // Nothing buffered - every publish() call writes through immediately.
    }

    @Override
    public void close() {
        // Owns no resources of its own; Terminal#shutdown() closes the underlying terminal.
    }

}
