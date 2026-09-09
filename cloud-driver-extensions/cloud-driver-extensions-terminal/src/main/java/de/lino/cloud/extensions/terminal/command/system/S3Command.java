package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.utility.UnitParser;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * {@code audit} is read-only and is the default. {@code purge} deletes, and therefore requires
 * being run twice within a short window - the same arm-then-confirm shape {@code hardReset} uses,
 * for the same reason: this operates on real user content, and getting the reconciliation wrong
 * in the deleting direction is unrecoverable.
 *
 * <p>An object is only ever treated as an orphan if the full row scan completed successfully. A
 * partial listing would make every unseen file's object look unreferenced, which in the purge
 * direction would be catastrophic - so a failed scan aborts rather than reporting what it managed
 * to see.
 */
public class S3Command implements Command {

    /** Keys requested per {@code ListObjectsV2} page - S3's own maximum. */
    private static final int PAGE_SIZE = 1000;

    /** How long a purge stays armed after being requested. */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofSeconds(10);

    /** Whether a purge is armed and awaiting confirmation. */
    private static final AtomicBoolean PURGE_ARMED = new AtomicBoolean(false);

    /** Deadline for the confirming second invocation, or {@code null} while unarmed. */
    private static final AtomicReference<Long> PURGE_DEADLINE = new AtomicReference<>(null);

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
            this.purge(terminal, objectStorageService);
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
            terminal.displayApproved("&7Run &fs3 purge &7to delete the orphaned objects (twice, to confirm).");
        }
        terminal.emptyLine();

        return purging ? orphans : List.of();
    }

    /** Arms, then on a second call within the window, deletes every orphaned object. */
    private void purge(final Terminal terminal, final ObjectStorageService objectStorageService) {

        if (PURGE_ARMED.get() && PURGE_DEADLINE.get() != null) {
            if (PURGE_DEADLINE.get() <= System.currentTimeMillis()) {
                terminal.displayApproved("The &cconfirmation window &7expired. Re-run &fs3 purge&7.");
                PURGE_ARMED.set(false);
                PURGE_DEADLINE.set(null);
                return;
            }
            PURGE_ARMED.set(false);
            PURGE_DEADLINE.set(null);

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
            return;
        }

        PURGE_ARMED.set(true);
        PURGE_DEADLINE.set(System.currentTimeMillis() + CONFIRMATION_WINDOW.toMillis());
        terminal.displayApproved("This will &cpermanently delete &7every bucket object no StoredFile row references.");
        terminal.displayApproved("Re-run &fs3 purge &7within &b%s seconds &7to confirm.", CONFIRMATION_WINDOW.toSeconds());
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
