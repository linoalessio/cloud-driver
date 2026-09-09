package de.lino.cloud.extensions;

import de.lino.cloud.api.backup.BackupService;
import org.jetbrains.annotations.NotNull;

/**
 * The one {@link BackupService} implementation - a thin adapter over this extension's own {@link
 * DatabaseBackupScheduler}, published into {@code IServiceContainer} by {@link
 * CloudBackupExtension} so an operator can reach the backup job without this module having to
 * expose the scheduler itself.
 *
 * <p>Deliberately an adapter rather than making {@code DatabaseBackupScheduler} implement the
 * interface directly: that class is concrete infrastructure with a much wider surface
 * ({@code start}/{@code stop}/{@code shutdown}, batch sizes, retention), none of which an operator
 * command has any business reaching. Narrowing it here is what keeps "run a backup now" from
 * becoming "and also reconfigure the schedule".
 */
final class SchedulerBackupService implements BackupService {

    private final DatabaseBackupScheduler scheduler;

    SchedulerBackupService(@NotNull final DatabaseBackupScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** {@inheritDoc} */
    @Override
    public boolean runNow() {
        return this.scheduler.runNow();
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public BackupStatus status() {
        return new BackupStatus(
                this.scheduler.lastRunStartedAtEpochMillis(),
                this.scheduler.lastRunFinishedAtEpochMillis(),
                this.scheduler.lastRunSucceeded(),
                this.scheduler.lastRunDetail(),
                this.scheduler.isRunning()
        );
    }

}
