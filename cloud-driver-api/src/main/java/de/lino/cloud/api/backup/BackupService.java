package de.lino.cloud.api.backup;

import de.lino.cloud.api.factory.service.IServiceContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * On-demand access to this deployment's database backup job, reached via {@link
 * IServiceContainer#getBackupService()} - {@code null} until {@code
 * cloud-driver-extensions-backup}'s {@code CloudBackupExtension} has published one, the same "may
 * not exist yet" contract every other {@link IServiceContainer} facet carries.
 *
 * <p>Backups were previously reachable only as a periodic tick with no operator surface at all:
 * no way to take one on demand before a risky operation (a {@code hardReset}, an S3 migration, a
 * schema-affecting deploy), and no way to see whether the scheduled one had actually been running
 * or silently failing every night. Both are exactly the moments a backup matters most.
 */
public interface BackupService {

    /**
     * The outcome of the most recent backup attempt, scheduled or on-demand.
     *
     * @param startedAtEpochMillis when that attempt began, or {@code null} if none has run yet in
     * this process
     * @param finishedAtEpochMillis when it finished, or {@code null} if it is still running (or
     * none has run yet)
     * @param succeeded whether it completed without error - meaningless while {@code
     * finishedAtEpochMillis} is {@code null}
     * @param detail a short human-readable summary (output path, row counts, or the failure), or
     * {@code null} if none has run yet
     * @param running whether a backup is in flight right now
     */
    record BackupStatus(@Nullable Long startedAtEpochMillis, @Nullable Long finishedAtEpochMillis,
                        boolean succeeded, @Nullable String detail, boolean running) {
    }

    /**
     * Runs a backup immediately, on this deployment's own backup worker rather than the calling
     * thread, and returns without waiting for it.
     *
     * <p>Must never throw and must never start a second concurrent run - a backup already in
     * flight (scheduled or on-demand) simply causes this call to be ignored, since two
     * simultaneous full-database reads are exactly the memory pressure this codebase has already
     * been bitten by.
     *
     * @return {@code true} if a run was actually started, {@code false} if one was already in
     * flight
     */
    boolean runNow();

    /**
     * @return the most recent attempt's outcome - see {@link BackupStatus}
     */
    @NotNull
    BackupStatus status();

}
