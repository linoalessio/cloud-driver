package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandFlag;
import de.lino.cloud.api.terminal.service.CommandUsage;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clears this deployment's stored data through {@link CloudDriver#reset()} and then stops the
 * process through {@link CloudDriver#shutdown()}, with no undo.
 *
 * <p><b>What goes:</b> every entity section (accounts, files, folders, ownership rows, shares,
 * public links, sessions, API keys, the pending registration/password-reset/e-mail-change/presigned-
 * upload flows, chunk manifests and the audit log), every object-storage object a file row
 * references, and the data each published extension owns - file versions, thumbnails, webhook
 * subscriptions with their delivery history, and the keyword search index.
 *
 * <p><b>What does not:</b> the semantic vector store owned by the external {@code
 * cloud-driver-intelligence} service, Redis coordination state, the off-site database backup
 * bucket, the {@code kek} section holding key-encryption-key material, and the configuration files
 * on disk. Decommissioning a deployment means clearing those separately.
 *
 * <p><b>The sequence</b> is {@code hardReset} to arm, then {@code hardReset confirm} within the
 * confirmation window to perform it; {@code hardReset cancel} disarms, and {@code hardReset
 * --dry-run} prints the exact list of targets and changes nothing. The confirming invocation must
 * carry the explicit word, and a {@code confirm} typed while nothing is armed is refused rather
 * than treated as arming - so a line repeated because it appeared to do nothing can never be the
 * thing that destroys the data set.
 *
 * <p><b>Where the trail goes:</b> armed, confirmed and completed-or-failed lines are written to
 * the process log, which the console session appends to its log file - not to the audit trail,
 * since the audit log is itself one of the things this clears.
 */
public class HardResetCommand implements Command {

    /**
     * How long an armed wipe stays confirmable. Long enough to read the warning and check it
     * against whatever prompted it, which is safe here because confirming takes an explicit word
     * rather than a repeat of the arming line.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * The epoch-millisecond deadline a confirming invocation must arrive before, or {@code 0}
     * while nothing is armed. One value rather than a flag plus a deadline, so the two can never
     * disagree. {@code static}, so the arm state is shared across every instance of this command
     * (there is normally only one, registered by {@link
     * de.lino.cloud.extensions.terminal.CloudTerminalExtension}) and therefore across everyone
     * attached to the same console session.
     */
    private static final AtomicLong CONFIRMATION_DEADLINE = new AtomicLong(0L);

    /** What a wipe cannot reach, printed alongside the scope so the list is never read as complete. */
    private static final List<String> OUT_OF_REACH = List.of(
            "not reached: the semantic vector store owned by cloud-driver-intelligence",
            "not reached: Redis coordination state (rate-limit windows, scheduler locks)",
            "not reached: the off-site database backup bucket",
            "not reached: the 'kek' section holding key-encryption-key material",
            "not reached: the configuration files on disk");

    /** @return {@code "hardReset"} */
    @Override
    public @NotNull String name() {
        return "hardReset";
    }

    /**
     * @return no aliases
     *
     * <p>This command used to answer to {@code reset} as well - the name of the standard terminal
     * repair command an operator types when a console is garbled. Typing it twice, which is
     * exactly what one does when the first attempt appears to have done nothing, was the whole
     * confirmation sequence for wiping the database.
     */
    @Override
    public @NotNull List<String> aliases() {
        return List.of();
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Clear the entire data set of the cloud driver (&c@not-recommended&7)";
    }

    /** @return the flags this command understands */
    @Override
    public @NotNull List<CommandFlag> flags() {
        return List.of(
                CommandFlag.of("--dry-run", "List everything the wipe would clear and clear nothing")
        );
    }

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("hardReset --dry-run", "List every section, object store and index a wipe would clear - changes nothing"),
                CommandUsage.of("hardReset", "Arm the wipe: prints what would be destroyed, destroys nothing"),
                CommandUsage.of("hardReset confirm", String.format("Confirm an armed wipe, within %ss of arming it", TIMEOUT.toSeconds())),
                CommandUsage.of("hardReset cancel", "Disarm without wiping anything")
        );
    }

    /**
     * Dispatches to the dry run, a cancel, a confirmation or the arming step, in that order.
     *
     * <p>Only two paths ever clear the armed state: an explicit {@code cancel}, and a {@code
     * confirm} that either fires or finds the window already expired. A dry run, an unrecognised
     * positional and a repeated arming all leave an armed wipe exactly as it was, so an operator
     * never loses their window to a stray keystroke.
     *
     * @param arguments the invocation - read for the {@code --dry-run} flag and, positionally,
     *     for {@code confirm}/{@code cancel}
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (arguments.hasFlag("--dry-run")) {
            this.printDryRun(terminal);
            return;
        }

        if (arguments.hasCommand(0, "cancel")) {
            this.cancel(terminal);
            return;
        }

        if (arguments.hasCommand(0, "confirm")) {

            final long deadline = CONFIRMATION_DEADLINE.getAndSet(0L);

            if (deadline == 0L) {
                // Refusing rather than arming is the whole point: 'hardReset confirm' recalled from
                // history must never become the first half of its own confirmation sequence.
                terminal.displayApproved("&cNothing is armed&7. Run &fhardReset &7first.");
                return;
            }

            if (deadline <= System.currentTimeMillis()) {
                terminal.displayApproved("The confirmation window expired. Re-run &fhardReset&7.");
                CloudDriver.getInstance().getLogger().log(Level.INFO,
                        "@HardResetCommand: a hard reset was confirmed after its window had expired - nothing was cleared");
                return;
            }

            this.performReset(terminal);
            return;
        }

        if (!arguments.isEmpty()) {
            // A typo such as 'hardReset confrim' must never arm.
            this.sendUsage();
            return;
        }

        this.arm(terminal);

    }

    /**
     * Prints every target a wipe would clear right now, plus what it cannot reach, without arming
     * or disarming anything. Paged, because the list grows with the number of entity types and
     * published extensions and the console usually has no scrollback.
     *
     * @param terminal where to print
     */
    private void printDryRun(@NonNull final Terminal terminal) {

        final List<String> scope = CloudDriver.getInstance().resetScope();
        final List<String> lines = new ArrayList<>(scope);
        lines.addAll(OUT_OF_REACH);

        terminal.displayApproved("&7A wipe would clear &b%s &7target(s). Nothing has been armed.", scope.size());
        terminal.displayPaged("hard reset scope", lines);

    }

    /**
     * Disarms without clearing anything, and says whether there was anything to disarm.
     *
     * @param terminal where to print
     */
    private void cancel(@NonNull final Terminal terminal) {

        final long deadline = CONFIRMATION_DEADLINE.getAndSet(0L);

        if (deadline == 0L) {
            terminal.displayApproved("Nothing was armed.");
            return;
        }

        terminal.displayApproved("&aDisarmed&7. Nothing was cleared.");
        CloudDriver.getInstance().getLogger().log(Level.INFO,
                "@HardResetCommand: an armed hard reset was cancelled at the operator console - nothing was cleared");

    }

    /**
     * Arms a wipe and prints what it would destroy.
     *
     * <p>Deliberately not paged: the line that says how to confirm, and the line that says how to
     * stop, must both still be on screen afterwards. A console with no scrollback would otherwise
     * push them away behind a long list - which is what {@code --dry-run} is for.
     *
     * @param terminal where to print
     */
    private void arm(@NonNull final Terminal terminal) {

        final int targets = CloudDriver.getInstance().resetScope().size();

        CONFIRMATION_DEADLINE.set(System.currentTimeMillis() + TIMEOUT.toMillis());
        CloudDriver.getInstance().getLogger().log(Level.INFO, "@HardResetCommand: hard reset ARMED at the operator console - "
                + "confirm within " + TIMEOUT.toSeconds() + "s; " + targets + " target(s)");

        terminal.displayApproved("&c&lThis destroys &f&l%s &c&ltarget(s) and cannot be undone.", targets);
        terminal.displayApproved("&7Goes: accounts, files, folders, ownership, shares, public links, sessions, API keys,");
        terminal.displayApproved("&7pending flows, chunk manifests, the audit log, object-storage objects, extension data.");
        terminal.displayApproved("&7Stays: the semantic index in cloud-driver-intelligence, Redis state, the off-site backup");
        terminal.displayApproved("&7bucket, the 'kek' row, the config files on disk.");
        terminal.displayApproved("&7Run &fhardReset --dry-run &7for the exact list.");
        terminal.displayApproved("&7To go ahead: &chardReset confirm &7within &b%s seconds&7. To stop: &fhardReset cancel&7.", TIMEOUT.toSeconds());

    }

    /**
     * Performs the wipe and, when it succeeds, shuts the process down.
     *
     * <p>The armed/confirmed/finished lines go to the process log rather than the audit trail:
     * {@link CloudDriver#reset()} clears the audit-log section too, so no persisted entry could
     * survive what it records. The confirming line is written <em>before</em> anything is touched,
     * so a wipe that never returns still leaves the record that it was started.
     *
     * <p>A failure deliberately does not shut the process down - an operator can still inspect a
     * partially cleared instance - which also means that instance is still serving traffic, so
     * both the log line and the terminal say so plainly.
     *
     * @param terminal where to print
     */
    private void performReset(@NonNull final Terminal terminal) {

        final List<String> scope = CloudDriver.getInstance().resetScope();
        final Logger logger = CloudDriver.getInstance().getLogger();

        logger.log(Level.WARNING, "@HardResetCommand: hard reset CONFIRMED at the operator console - clearing "
                + scope.size() + " target(s): " + String.join(", ", scope));

        final long startedAt = System.currentTimeMillis();

        try {
            CloudDriver.getInstance().reset();
        } catch (final Throwable resetFailed) {
            final long failedAfter = System.currentTimeMillis() - startedAt;
            logger.log(Level.SEVERE, "@HardResetCommand: hard reset FAILED after " + failedAfter + "ms - the data set may be "
                    + "partially cleared; the process is left running and is still serving traffic", resetFailed);
            terminal.displayApproved("&c&lThe hard reset failed after &f&l%sms&c&l.", failedAfter);
            terminal.displayApproved("&cThe data set may be partially cleared. This process is still running and still serving traffic.");
            terminal.displayApproved("&7Inspect it, then stop it with &fexit &7once you know what state it is in.");
            return;
        }

        logger.log(Level.WARNING, "@HardResetCommand: hard reset COMPLETED in "
                + (System.currentTimeMillis() - startedAt) + "ms - shutting down");

        terminal.displayApproved("&c&lThe entire cloud data has been reset.");
        terminal.displayApproved("&c&lShutting down cloud driver...");
        CloudDriver.getInstance().shutdown();

    }

}
