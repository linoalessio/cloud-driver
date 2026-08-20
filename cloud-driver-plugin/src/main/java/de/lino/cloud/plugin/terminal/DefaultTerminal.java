package de.lino.cloud.plugin.terminal;

import de.lino.cloud.api.terminal.Terminal;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link Terminal} implementation reading blocking, line-buffered input from
 * {@link System#in} and writing ANSI-styled output to {@link System#out} -
 * the only genuinely concrete, sink-specific piece of the terminal facet;
 * every command's dispatch logic lives in {@link Terminal} itself. The same
 * "interface(s)/abstract primitives in {@code cloud-driver-api}, one
 * concrete implementation in {@code cloud-driver-plugin}" shape {@code
 * InternetConnectivityChecker}/{@code FileKeyEncryptionService} use.
 */
public final class DefaultTerminal extends Terminal {

    private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final PrintStream out = System.out;

    public DefaultTerminal() {
        super();
    }

    /**
     * Blocks on {@link BufferedReader#readLine()}. A closed/exhausted {@link
     * System#in} (including after {@link #stop()} closes it) surfaces here as
     * {@code null} - {@link IOException} on an already-closed stream is
     * treated the same way, since both mean "no more input is coming".
     */
    @Override
    protected String readLine() {
        try {
            return this.reader.readLine();
        } catch (final IOException closed) {
            return null;
        }
    }

    @Override
    protected void write(@NotNull final String raw) {
        this.out.print(raw);
        this.out.flush();
    }

    /**
     * Emits the ANSI "clear screen, move cursor to top-left" sequence -
     * a no-op on a sink that does not interpret ANSI escape codes.
     */
    @Override
    protected void clearScreen() {
        this.out.print("[H[2J");
        this.out.flush();
    }

    /**
     * {@link Terminal#stop()} alone only flips a flag {@link Terminal#start()}
     * checks between prompts - it cannot unblock a {@link #readLine()} call
     * already parked on {@link System#in}. Closing the reader here does: the
     * blocked {@link BufferedReader#readLine()} then throws {@link
     * IOException}, which {@link #readLine()} above treats as end-of-input,
     * so {@link Terminal#start()}'s loop exits on its very next check rather
     * than waiting for a line of input that may never come (e.g. during an
     * unattended shutdown with nobody at the console).
     */
    @Override
    public void stop() {
        super.stop();
        try {
            this.reader.close();
        } catch (final IOException ignored) {
            // Already closed, or nothing left to flush - either way, readLine() above
            // will observe end-of-input on its next call.
        }
    }

}
