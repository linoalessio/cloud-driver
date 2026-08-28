package de.lino.cloud.extensions;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.auth.Credentials;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Wires a {@link DatabaseBackupScheduler} up as an {@link Extension}, so the periodic,
 * keyset-paginated Postgres backup job (see {@link DatabaseBackupScheduler}'s own class
 * Javadoc for the full rationale) starts and stops the same way any other extension does.
 * Declares a dependency on {@code "cloud-driver-bootstrap"} in its {@code extension.json}.
 */
public class CloudBackupExtension extends Extension {

    /** The scheduler this extension owns; {@code null} until {@link #onLoading()} runs. */
    private DatabaseBackupScheduler backupScheduler;

    /**
     * Resolves {@link Credentials} from {@code postgres-database.json} and constructs this
     * extension's {@link DatabaseBackupScheduler}, targeting a {@code backup} subdirectory of
     * {@link Constraints#CONFIGURATION_PATH}.
     *
     * @throws IllegalStateException if {@code postgres-database.json} is missing or malformed
     */
    @Override
    public void onLoading() {

        final Optional<Credentials> credentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json"));

        if (credentials.isEmpty())
            throw new IllegalStateException("@CloudBackupExtension.onLoading: No database file could be found");

        this.backupScheduler = new DatabaseBackupScheduler(
                credentials.orElseThrow()
                , Constraints.CONFIGURATION_PATH.resolve("backup")
        );

    }

    /**
     * Starts the backup scheduler on its default interval.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {

        this.backupScheduler.start();

    }

    /** No-op; the scheduler is left running until process shutdown. */
    @Override
    public void onEnding() {

    }

    /**
     * Logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(RuntimeException reason) {

        this.cloudDriver().getLogger().severe("An error occurred while trying to start the cloud backup extension.");
        this.cloudDriver().getLogger().log(Level.SEVERE, reason.getMessage(), reason);

    }

}
