package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.audit.AuditAction;
import de.lino.cloud.api.audit.AuditEvent;
import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
import de.lino.cloud.api.utility.UnitParser;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reconciles the S3 bucket against the {@code StoredFile} rows that are supposed to reference it,
 * and - on explicit confirmation - deletes the objects nothing points at any more.
 *
 * <h2>Why this exists</h2>
 *
 * Nothing in this codebase has ever compared the two. That gap has already cost real money: the
 * live bucket was found holding 1.1 GB across 121,728 objects while only around 400 MB of files
 * had ever actually been uploaded through the app - abandoned presigned uploads, orphaned forever
 * because no code path revisited them. That specific cause now has a purge scheduler, but two
 * documented gaps still orphan objects ({@code FileFactory#clear()} and {@code #deleteSection()}
 * never purge), and any future one will be silent in exactly the same way.
 *
 * <h2>Safety</h2>
 *
 * {@code audit} is read-only and is the default. {@code purge} deletes, so it is armed by one
 * invocation and performed only by a second that carries the explicit word {@code confirm} - the
 * same shape {@code hardReset}, {@code cloudUser reset/delete} and {@code share revoke-link} use.
 * A bare repeat of the same line is deliberately not a confirmation: repeating a command that
 * appeared to do nothing is what an operator does at a console that is not redrawing, and this
 * operates on real user content.
 *
 * <p>A completed purge is recorded, so the console can be asked afterwards what was deleted.
 *
 * <p>An object is only ever treated as an orphan if the full row scan completed successfully. A
 * partial listing would make every unseen file's object look unreferenced, which in the purge
 * direction would be catastrophic - so a failed scan aborts rather than reporting what it managed
 * to see.
 */
public class S3Command implements Command {

    /** Keys requested per {@code ListObjectsV2} page - S3's own maximum. */
    private static final int PAGE_SIZE = 1000;

    /** How long an armed purge stays confirmable. */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofSeconds(15);

    /** The exact invocation currently armed for confirmation, or {@code null} while none is. */
    private static final AtomicReference<String> ARMED = new AtomicReference<>();

    /** When the armed purge stops being confirmable, in epoch millis, or {@code null}. */
    private static final AtomicReference<Long> ARMED_UNTIL = new AtomicReference<>();

    /** @return {@code "s3"} */
    @Override
    public @NotNull String name() {
        return "s3";
    }

    /** @return {@code "objectStorage"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("objectStorage");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Reconcile the S3 bucket against StoredFile rows, and purge orphaned objects";
    }

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("s3", "Compare the bucket against the file rows"),
                CommandUsage.of("s3 purge", "Arm deleting every object no file references (does nothing on its own)"),
                CommandUsage.of("s3 purge confirm", String.format("Confirm the armed purge, within %ss of arming it", CONFIRMATION_WINDOW.toSeconds()))
        );
    }

    /**
     * Dispatches to {@code audit} (the default) or {@code purge}.
     *
     * @param arguments the sub-command
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final ObjectStorageService objectStorageService = CloudDriver.getInstance().getFactoryContainer().getObjectStorageService();

        if (objectStorageService == null) {
            terminal.displayApproved("&cNo object storage is configured &7on this deployment - nothing to reconcile.");
            return;
        }

        if (arguments.hasCommand(0, "purge")) {
            this.purge(terminal, objectStorageService, arguments);
            return;
        }

        this.audit(terminal, objectStorageService, false);

    }

    /**
     * Compares bucket contents with referenced ids.
     *
     * @param purging whether the caller intends to delete what this finds - only then are the
     * orphan keys returned rather than merely counted
     * @return the orphaned keys when {@code purging}, otherwise an empty list
     */
    private List<String> audit(final Terminal terminal, final ObjectStorageService objectStorageService, final boolean purging) {

        terminal.displayApproved("Scanning StoredFile rows...");
        final Set<String> referenced;
        try {
            referenced = this.collectReferencedObjectKeys();
        } catch (final RuntimeException failed) {
            // Aborting rather than continuing is the whole safety property here - see the class Javadoc.
            terminal.displayApproved("&cAborted&7: could not read StoredFile rows (%s). Nothing was compared.", failed.getMessage());
            return List.of();
        }
        terminal.displayApproved("  &7referenced object keys: &b%s", referenced.size());

        terminal.displayApproved("Listing bucket objects...");
        final List<String> orphans = new ArrayList<>();
        int bucketObjects = 0;
        String continuationToken = null;
        try {
            do {
                final ObjectStorageService.ObjectListing page = objectStorageService.listObjects(continuationToken, PAGE_SIZE);
                for (final String key : page.keys()) {
                    bucketObjects++;
                    if (!referenced.contains(key)) orphans.add(key);
                }
                continuationToken = page.nextContinuationToken();
            } while (continuationToken != null);
        } catch (final ObjectStorageException failed) {
            terminal.displayApproved("&cAborted&7: bucket listing failed (%s). Nothing was compared.", failed.getMessage());
            return List.of();
        }

        terminal.emptyLine();
        terminal.displayApproved("&8--- &fS3 reconciliation &8---");
        terminal.displayApproved("&8- &7Objects in bucket:      &b%s", bucketObjects);
        terminal.displayApproved("&8- &7Referenced by a row:    &b%s", bucketObjects - orphans.size());
        terminal.displayApproved("&8- &7Orphaned (unreferenced):%s%s", orphans.isEmpty() ? " &a" : " &c", orphans.size());

        // Rows referencing an object that no longer exists is the opposite failure, and the far
        // more damaging one - it means a download is already broken - so it is worth naming even
        // though this command cannot fix it.
        final int missing = referenced.size() - (bucketObjects - orphans.size());
        if (missing > 0) {
            terminal.displayApproved("&8- &cRows with no object:    &c%s &7(these downloads are already broken)", missing);
        }

        if (!orphans.isEmpty() && !purging) {
            terminal.displayApproved("&7Run &fs3 purge &7to arm deleting the orphaned objects, then &fs3 purge confirm&7.");
        }
        terminal.emptyLine();

        return purging ? orphans : List.of();
    }

    /**
     * Arms a purge, or - on an invocation carrying {@code confirm}, inside the window, for this
     * same command line - deletes every orphaned object and records what was removed.
     *
     * @param terminal where to print
     * @param objectStorageService the bucket to reconcile against
     * @param arguments the invocation, checked for the confirming token
     */
    private void purge(final Terminal terminal, final ObjectStorageService objectStorageService, final CommandArguments arguments) {

        // The confirming invocation must say so explicitly. A bare repeat of 's3 purge' is exactly
        // what an operator types when the first line appeared to do nothing, and this deletes
        // content that is not recoverable from here.
        final boolean confirming = arguments.hasCommand(1, "confirm");
        final Long armedUntil = ARMED_UNTIL.get();

        if (confirming) {

            final boolean armed = "s3 purge".equals(ARMED.get()) && armedUntil != null && armedUntil > System.currentTimeMillis();
            ARMED.set(null);
            ARMED_UNTIL.set(null);

            if (!armed) {
                terminal.displayApproved("&cNothing armed, or the confirmation window has passed - run &fs3 purge &cwithout 'confirm' first.");
                return;
            }

            final List<String> orphans = this.audit(terminal, objectStorageService, true);
            if (orphans.isEmpty()) {
                terminal.displayApproved("&aNothing to purge&7.");
                return;
            }

            int deleted = 0;
            int failed = 0;
            for (final String key : orphans) {
                try {
                    objectStorageService.deleteObject(key);
                    deleted++;
                } catch (final ObjectStorageException e) {
                    failed++;
                }
            }

            terminal.displayApproved("Purged &b%s &7object(s)%s.", deleted, failed > 0 ? ", &c" + failed + " failed&7" : "");
            this.recordPurge(deleted, failed);
            return;
        }

        ARMED.set("s3 purge");
        ARMED_UNTIL.set(System.currentTimeMillis() + CONFIRMATION_WINDOW.toMillis());
        terminal.displayApproved("This will &cpermanently delete &7every bucket object no StoredFile row references.");
        terminal.displayApproved("It cannot be undone from here. To confirm, run &fs3 purge confirm &7within &b%s seconds&7.", CONFIRMATION_WINDOW.toSeconds());
    }

    /**
     * Records a completed purge in both places an operator can read afterwards: the persisted
     * trail the {@code auditLog} command lists, and the process log the console session writes to
     * its log file. Both, because the audit service is an optional facet that is {@code null}
     * until the REST extension publishes one, while a purge is available whenever object storage
     * is configured - so the log line is the record that is always written.
     *
     * @param deleted how many objects were removed
     * @param failed how many deletions the bucket refused
     */
    private void recordPurge(final int deleted, final int failed) {

        final String summary = deleted + " orphaned object(s) deleted, " + failed + " failed";
        CloudDriver.getInstance().getLogger().warning("s3 purge, from the operator console: " + summary);

        final AuditLogService auditLogService = CloudDriver.getInstance().getServiceContainer().getAuditLogService();
        if (auditLogService == null) return;

        // No authenticated actor exists at the console, and the purge is deployment-wide rather
        // than aimed at one account, so actor and target are both absent by design.
        auditLogService.record(new AuditEvent(null, AuditAction.OBJECT_STORAGE_PURGE, null, summary + " - operator console"));
    }

    /**
     * Every object key currently referenced by a {@code StoredFile} row.
     *
     * <p>Reads through {@code DataFactory} rather than {@code FileFactory} on purpose: the latter
     * resolves and verifies content, which here would mean fetching every file in the account from
     * S3 just to learn which keys exist - the exact unbounded-decrypt cost that has produced
     * {@code OutOfMemoryError}s in this codebase before.
     */
    private Set<String> collectReferencedObjectKeys() {
        final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
        final Set<String> referenced = new HashSet<>();
        for (final StoredFile file : dataFactory.getEntitiesAsync(StoredFile.class).join()) {
            if (file.isS3Backed() && file.objectStorageKey() != null) {
                referenced.add(file.objectStorageKey());
            }
        }
        return referenced;
    }

}
