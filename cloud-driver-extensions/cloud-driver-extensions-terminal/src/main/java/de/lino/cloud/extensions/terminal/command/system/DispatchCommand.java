package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.audit.AuditAction;
import de.lino.cloud.api.audit.AuditEvent;
import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs an arbitrary system-level command as the server process, printing its combined standard
 * output and standard error and its exit code.
 *
 * <p><b>The child's standard input is closed</b> before it is read from, so a command that prompts
 * sees end-of-file and fails fast instead of waiting forever on input no one can type - the
 * console's own reader owns the keyboard, and a second reader on it would fight for every
 * keystroke. A child that needs input is given it explicitly, e.g. {@code dispatch sh -c "echo y |
 * some-command"}.
 *
 * <p><b>This command's sub-commands are recognised only as the first token</b>, because everything
 * else on the line belongs to the child and is passed through untouched - flags included, exactly
 * as they were typed.
 *
 * <p><b>Nothing is timed out and nothing is confirmed.</b> A legitimate dispatch can run for many
 * minutes ({@code pg_dump}, {@code apt-get -y upgrade}), and killing it on a timer or demanding a
 * second typed line mid-incident is the harm this command must not do. Closing the child's input
 * removes the hang, {@code dispatch status} shows what is still running, and {@code dispatch
 * cancel} stops it.
 *
 * <p>Every dispatched line is recorded in the audit trail and in the process log before the child
 * starts, so a command that never returns still leaves a trace.
 */
public class DispatchCommand implements Command {

    /** How many output lines a buffered run keeps for paging; the rest are counted and dropped. */
    private static final int CAPTURED_LINE_LIMIT = 500;

    /** How many output lines a streamed run prints before it falls silent and only counts. */
    private static final int STREAMED_LINE_LIMIT = 200;

    /** How long a cancelled child is given to stop on its own before it is killed. */
    private static final int TERMINATION_GRACE_SECONDS = 5;

    /** Supplies the id each dispatched child is listed and cancelled under. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    /**
     * Every child currently running, keyed by its id. Nothing is serialised, so several children
     * may run at once and one long command never blocks the next. {@code static}, so the registry
     * is shared across every instance of this command (there is normally only one).
     */
    private static final Map<Integer, Dispatched> RUNNING = new ConcurrentHashMap<>();

    /** @return {@code "dispatch"} */
    @Override
    public @NotNull String name() {
        return "dispatch";
    }

    /** @return {@code "exec"}, {@code "d"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("exec", "d");
    }

    /** @return this service's description */
    @Override
    public @NotNull String description() {
        return "Run a system-level command as the server process - output paged, input closed, listable and cancellable";
    }

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("dispatch <service> [args...]", "Run a system command; its output is paged when it exits"),
                CommandUsage.of("dispatch stream <service> [args...]", "Run it printing output as it arrives, for a command worth watching"),
                CommandUsage.of("dispatch status", "What is still running, since when, and how much output it produced"),
                CommandUsage.of("dispatch cancel <id>|all", "Stop a running command (terminate, then kill after the grace period)")
        );
    }

    /**
     * Dispatches to {@code status}, {@code cancel}, a streamed run or a buffered run, deciding on
     * the first raw token alone.
     *
     * <p>The child is handed {@link CommandArguments#args()} verbatim rather than the positional
     * list: the operator's line <em>is</em> the child's line, and reading it positionally would
     * strip its flags out of the middle of it and run something that was never typed. This command
     * declares no flags of its own for the same reason.
     *
     * @param arguments the line following {@code dispatch}; a no-op with a usage message if empty
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final String[] raw = arguments.args();
        final String head = raw.length == 0 ? "" : raw[0];

        if (raw.length == 0) {
            this.sendUsage();
            return;
        }

        if (head.equalsIgnoreCase("status")) {
            this.printStatus();
            return;
        }

        if (head.equalsIgnoreCase("cancel")) {
            this.cancel(arguments);
            return;
        }

        final boolean streamed = head.equalsIgnoreCase("stream");
        final String[] childArgv = streamed ? Arrays.copyOfRange(raw, 1, raw.length) : raw;

        if (childArgv.length == 0) {
            this.sendUsage();
            return;
        }

        this.run(childArgv, streamed);

    }

    /**
     * Starts {@code childArgv}, drains its combined output to the end, and prints its exit code.
     *
     * <p>The output stream is read to end-of-stream even once the display cap is reached: a reader
     * that stops reading fills the operating system's pipe buffer and deadlocks the child.
     *
     * @param childArgv the executable and its arguments, exactly as the operator typed them
     * @param streamed whether output is printed as it arrives rather than paged once the child exits
     */
    private void run(@NonNull final String[] childArgv, final boolean streamed) {

        final Terminal terminal = this.terminal();
        final String commandLine = String.join(" ", childArgv);

        this.record(commandLine, childArgv[0]);

        final int id = NEXT_ID.getAndIncrement();
        Dispatched dispatched = null;

        try {

            final Process process = new ProcessBuilder(childArgv)
                    .redirectErrorStream(true)
                    .start();

            // An open, never-written standard-input pipe is what makes a child that reads input
            // hang forever and pin this thread. Closing it puts the child at end-of-file on its
            // very first read. Redirect.INHERIT is never an option here: the child would then take
            // keystrokes away from the console's own reader.
            process.getOutputStream().close();

            dispatched = new Dispatched(id, commandLine, System.currentTimeMillis(), process);
            RUNNING.put(id, dispatched);

            final List<String> captured = new ArrayList<>();

            try (final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    this.consume(dispatched, captured, line, streamed);
                }
            }

            final int exitCode = process.waitFor();
            this.report(dispatched, captured, streamed);

            terminal.displayApproved(exitCode == 0
                    ? "[&b" + id + "&7] Process exited with code &a&l" + exitCode
                    : "[&b" + id + "&7] Process exited with code &c&l" + exitCode);

        } catch (final IOException e) {
            terminal.displayApproved("&7Failed to dispatch service: &c" + e.getMessage());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (dispatched != null) dispatched.process.destroy();
            terminal.displayApproved("&cInterrupted while waiting for the service to finish.");
        } finally {
            RUNNING.remove(id);
        }

    }

    /**
     * Accounts for one output line - printing it, buffering it, or only counting it once the
     * relevant cap has been reached.
     *
     * @param dispatched the child the line came from
     * @param captured where a buffered run collects its lines
     * @param line the raw output line
     * @param streamed whether this run prints as it goes
     */
    private void consume(@NonNull final Dispatched dispatched, @NonNull final List<String> captured,
                          @NonNull final String line, final boolean streamed) {

        final int limit = streamed ? STREAMED_LINE_LIMIT : CAPTURED_LINE_LIMIT;

        if (dispatched.shownLines.get() >= limit) {
            if (streamed && dispatched.droppedLines.getAndIncrement() == 0) {
                this.terminal().displayApproved("&8-- further output suppressed; &7dispatch cancel "
                        + dispatched.id + "&8 stops it --");
            } else if (!streamed) {
                dispatched.droppedLines.incrementAndGet();
            }
            return;
        }

        dispatched.shownLines.incrementAndGet();
        if (streamed) {
            this.terminal().displayApproved(String.format("&f&l%s", line));
            return;
        }
        captured.add(String.format("&f&l%s", line));

    }

    /**
     * Prints what a finished child produced: the paged buffer for a buffered run, or the count of
     * whatever a streamed run stopped printing.
     *
     * @param dispatched the finished child
     * @param captured the lines a buffered run collected
     * @param streamed whether this run printed as it went
     */
    private void report(@NonNull final Dispatched dispatched, @NonNull final List<String> captured, final boolean streamed) {

        final int dropped = dispatched.droppedLines.get();

        if (streamed) {
            if (dropped > 0) {
                this.terminal().displayApproved("&8-- &7%s &8further line(s) were not printed --", dropped);
            }
            return;
        }

        final List<String> lines = new ArrayList<>(captured);
        if (dropped > 0) lines.add("&8-- " + dropped + " further line(s) were not captured --");
        this.terminal().displayPaged("dispatch " + dispatched.commandLine, lines);

    }

    /** Lists every child still running, paged, or says that nothing is. */
    private void printStatus() {

        final Terminal terminal = this.terminal();

        if (RUNNING.isEmpty()) {
            terminal.displayApproved("Nothing is running.");
            return;
        }

        final long now = System.currentTimeMillis();
        final List<String> lines = RUNNING.values().stream()
                .sorted(Comparator.comparingInt((Dispatched running) -> running.id))
                .map(running -> String.format("&8- &b%s &8| &7%ss &8| &7%s line(s), %s dropped &8| &f%s",
                        running.id, (now - running.startedAtMillis) / 1000,
                        running.shownLines.get(), running.droppedLines.get(), running.commandLine))
                .toList();

        terminal.displayPaged("dispatch status", lines);

    }

    /**
     * Stops one running child by id, or every one of them with {@code all}.
     *
     * @param arguments the invocation, read positionally for the id
     */
    private void cancel(@NonNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (!arguments.hasLength(1)) {
            this.sendUsage();
            return;
        }

        final String target = arguments.command(1);

        if (target.equalsIgnoreCase("all")) {
            if (RUNNING.isEmpty()) {
                terminal.displayApproved("Nothing is running.");
                return;
            }
            List.copyOf(RUNNING.values()).forEach(this::stop);
            return;
        }

        final int id;
        try {
            id = Integer.parseInt(target);
        } catch (final NumberFormatException notAnId) {
            this.sendUsage();
            return;
        }

        final Dispatched dispatched = RUNNING.get(id);
        if (dispatched == null) {
            terminal.displayApproved("No command is running under id &b%s&7.", id);
            return;
        }

        this.stop(dispatched);

    }

    /**
     * Asks one child to stop and, if it has not after the grace period, kills it - reporting which
     * of the two ended it.
     *
     * @param dispatched the child to stop
     */
    private void stop(@NonNull final Dispatched dispatched) {

        final Terminal terminal = this.terminal();
        dispatched.process.destroy();

        boolean ended;
        try {
            ended = dispatched.process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ended = false;
        }

        if (ended) {
            terminal.displayApproved("[&b%s&7] &aTerminated&7.", dispatched.id);
        } else {
            dispatched.process.destroyForcibly();
            terminal.displayApproved("[&b%s&7] &cKilled &7after %ss - it did not stop when asked.",
                    dispatched.id, TERMINATION_GRACE_SECONDS);
        }

        CloudDriver.getInstance().getLogger().info(
                "@DispatchCommand: cancelled the system command [" + dispatched.id + "] " + dispatched.commandLine);

    }

    /**
     * Records one dispatched line, before the child starts, in both places an operator can read it
     * back: the persisted trail the {@code auditLog} command lists, and the process log the console
     * session writes to its log file.
     *
     * <p>The log line is unconditional because the audit service is an optional facet that is
     * {@code null} until the REST extension publishes one, while this command is always available.
     * There is no authenticated caller at a console, so the entry records no actor.
     *
     * @param commandLine the whole line handed to the child
     * @param executable the child's own name
     */
    private void record(@NonNull final String commandLine, @NonNull final String executable) {

        CloudDriver.getInstance().getLogger().info(
                "@DispatchCommand: running a system command from the operator console: " + commandLine);

        final AuditLogService auditLogService = CloudDriver.getInstance().getServiceContainer().getAuditLogService();
        if (auditLogService == null) return;

        auditLogService.record(new AuditEvent(null, AuditAction.SYSTEM_COMMAND_DISPATCH, executable, commandLine));

    }

    /** One child this command started and has not yet seen finish. */
    private static final class Dispatched {

        /** The id this child is listed and cancelled under. */
        private final int id;

        /** The whole line this child was started with, as the operator typed it. */
        private final String commandLine;

        /** When this child was started, in epoch millis. */
        private final long startedAtMillis;

        /** The running child itself. */
        private final Process process;

        /** How many output lines were printed or buffered. */
        private final AtomicInteger shownLines = new AtomicInteger();

        /** How many output lines were read but neither printed nor buffered. */
        private final AtomicInteger droppedLines = new AtomicInteger();

        /**
         * @param id the id this child is listed and cancelled under
         * @param commandLine the whole line this child was started with
         * @param startedAtMillis when this child was started, in epoch millis
         * @param process the running child
         */
        private Dispatched(final int id, final String commandLine, final long startedAtMillis, final Process process) {
            this.id = id;
            this.commandLine = commandLine;
            this.startedAtMillis = startedAtMillis;
            this.process = process;
        }

    }

}
