package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.scan.ContentScanService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Content-scan visibility and on-demand rescans.
 *
 * <h2>The problem this addresses</h2>
 *
 * Scanning fails <b>open</b>: a file whose scan cannot be performed is marked {@code CLEAN} and
 * never looked at again. That is the right trade-off for availability - an unreachable scanner
 * must not block uploads - but it has a consequence nothing else surfaces: files uploaded during
 * a scanner outage are permanently recorded as clean without ever having been scanned, and no
 * code path in this system will ever revisit them.
 *
 * <p>This deployment has already had such a window. {@code clamd} was installed and running but
 * had no TCP listener at all, so every scan attempt was refused, retried, and failed open, with
 * the only evidence in console scrollback. Every file uploaded in that period is still marked
 * clean today. {@code scan rescan all} is what fixes that after the fact.
 */
public class ScanCommand implements Command {

    /** @return {@code "scan"} */
    @Override
    public @NotNull String name() {
        return "scan";
    }

    /** @return {@code "contentScan"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("contentScan");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Content scanning: engine reachability, flagged/pending files, and on-demand rescans";
    }

    /**
     * Dispatches to {@code status} (the default), {@code list}, or {@code rescan}.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final ContentScanService contentScanService = CloudDriver.getInstance().getServiceContainer().getContentScanService();

        if (contentScanService == null) {
            terminal.displayApproved("&cContent scanning is not running &7on this deployment.");
            terminal.displayApproved("&7Every upload is being accepted &cunscanned&7.");
            return;
        }

        if (arguments.hasCommand(0, "list")) {
            this.list(terminal, arguments.hasLength(1) ? arguments.command(1) : null);
            return;
        }

        if (arguments.hasCommand(0, "rescan") && arguments.hasLength(1)) {
            this.rescan(terminal, contentScanService, arguments.command(1));
            return;
        }

        if (arguments.isEmpty() || arguments.hasCommand(0, "status")) {
            this.status(terminal, contentScanService);
            return;
        }

        terminal.displayApproved("&fscan status");
        terminal.displayApproved("&fscan list [flagged|pending]");
        terminal.displayApproved("&fscan rescan <fileId|all>");
    }

    /** Reachability plus a count of every non-clean file. */
    private void status(final Terminal terminal, final ContentScanService contentScanService) {
        final boolean reachable = contentScanService.isScannerReachable();

        terminal.emptyLine();
        terminal.displayApproved("Scan engine: %s", reachable ? "&areachable" : "&cUNREACHABLE");
        if (!reachable) {
            terminal.displayApproved("&c! &7Uploads are being marked &fCLEAN &7without being scanned.");
            terminal.displayApproved("&7Once the engine is back, run &fscan rescan all&7 - nothing revisits those files on its own.");
        }

        final List<StoredFileOwnership> rows = this.rowsQuietly();
        long flagged = 0;
        long pending = 0;
        for (final StoredFileOwnership row : rows) {
            final String status = row.resolvedScanStatus();
            if ("FLAGGED".equalsIgnoreCase(status)) flagged++;
            else if ("PENDING".equalsIgnoreCase(status)) pending++;
        }
        terminal.displayApproved("Flagged files: %s%s", flagged > 0 ? "&c" : "&a", flagged);
        terminal.displayApproved("Pending files: %s%s", pending > 0 ? "&e" : "&a", pending);
        terminal.emptyLine();
    }

    /** Lists files in a non-clean scan state. */
    private void list(final Terminal terminal, final String filter) {
        final String wanted = filter == null ? null : filter.toUpperCase();
        final List<StoredFileOwnership> rows = this.rowsQuietly();

        terminal.emptyLine();
        terminal.displayApproved("Files with a non-clean scan status%s:", wanted == null ? "" : " (" + wanted + ")");
        int shown = 0;
        for (final StoredFileOwnership row : rows) {
            final String status = row.resolvedScanStatus();
            if (status == null || "CLEAN".equalsIgnoreCase(status)) continue;
            if (wanted != null && !wanted.equalsIgnoreCase(status)) continue;
            terminal.displayApproved("&8- %s%-8s &7%s &8(%s)",
                    "FLAGGED".equalsIgnoreCase(status) ? "&c" : "&e", status,
                    row.getFileName(), row.getStoredFileId());
            shown++;
        }
        if (shown == 0) terminal.displayApproved("&8  (none)");
        terminal.emptyLine();
    }

    /**
     * Re-triggers scanning for one file, or for every file.
     *
     * <p>{@code all} deliberately re-scans <em>everything</em>, including files already recorded
     * as clean. That is the point: a file marked clean during a scanner outage is
     * indistinguishable from one that was genuinely scanned, because failing open records no
     * evidence of itself. Only rescanning all of them can restore the guarantee.
     */
    private void rescan(final Terminal terminal, final ContentScanService contentScanService, final String target) {

        if (!"all".equalsIgnoreCase(target)) {
            contentScanService.scanAsync(target);
            terminal.displayApproved("Queued a rescan of &b%s&7.", target);
            return;
        }

        if (!contentScanService.isScannerReachable()) {
            // Queuing thousands of scans against a dead engine would just fail every one of them
            // open again - producing exactly the false "all clean" state this is meant to repair.
            terminal.displayApproved("&cThe scan engine is unreachable &7- refusing to rescan.");
            terminal.displayApproved("&7Every queued scan would fail open and re-mark these files &fCLEAN&7.");
            return;
        }

        final List<StoredFileOwnership> rows = this.rowsQuietly();
        int queued = 0;
        for (final StoredFileOwnership row : rows) {
            if (row.isDeleted()) continue;
            contentScanService.scanAsync(row.getStoredFileId());
            queued++;
        }
        terminal.displayApproved("Queued &b%s &7rescan(s). Scanning is asynchronous and bounded by the scan worker.", queued);
    }

    /** Every ownership row, or an empty list if they cannot be read - never throws out of a command. */
    private List<StoredFileOwnership> rowsQuietly() {
        try {
            final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
            return dataFactory.getEntitiesAsync(StoredFileOwnership.class).join();
        } catch (final RuntimeException failed) {
            return List.of();
        }
    }

}
