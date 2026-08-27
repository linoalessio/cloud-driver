package de.lino.cloud.extensions.watcher;

import de.lino.cloud.api.event.database.DatabaseWatchEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.notification.DatabaseNotification;
import de.lino.database.database.sql.postgresql.PostgresDatabaseNotification;

import java.util.logging.Level;

/**
 * Watches {@link StoredFile}'s table for writes via Postgres {@code LISTEN}/{@code NOTIFY} and
 * routes each notification through {@link DatabaseWatchEvent}. Declares a dependency on {@code
 * "cloud-driver-bootstrap"} in its {@code extension.json}.
 */
@SuppressWarnings("unchecked")
public class CloudWatcherExtension extends Extension {

    /** The active Postgres change-notification listener; {@code null} until {@link #onLoading()} runs. */
    private DatabaseNotification notification;

    /**
     * Resolves {@link Credentials} from {@code postgres-database.json} and constructs this
     * instance's {@link PostgresDatabaseNotification} on channel {@code "cloud_driver_watcher"}.
     *
     * @throws java.util.NoSuchElementException if {@code postgres-database.json} is missing or malformed
     */
    @Override
    public void onLoading() {

        final Credentials credentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).orElseThrow();
        this.notification = new PostgresDatabaseNotification(credentials, "cloud_driver_watcher");

    }

    /**
     * Installs the change-notification trigger on {@link StoredFile}'s table and starts
     * listening, dispatching each notification as a {@link DatabaseWatchEvent}. A {@code
     * RuntimeException} from the callback is caught and logged rather than propagated, since it
     * would otherwise permanently kill the underlying listener thread.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {

        this.notification.watch(StoredFile.class);
        this.notification.start(payload -> {
            try {
                this.cloudDriver().getEventFactory().dispatch(DatabaseWatchEvent.class, payload);
            } catch (final RuntimeException notificationHandlingFailed) {
                this.cloudDriver().getLogger().log(Level.WARNING, "Failed to handle a database change notification: " + payload, notificationHandlingFailed);
            }
        });

    }

    /** Shuts the listener down, if it was ever started. */
    @Override
    public void onEnding() {
        if (this.notification == null) return;
        this.notification.shutdown();
    }

    /**
     * Shuts the listener down, if it was ever started, and logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(RuntimeException reason) {

        if (this.notification != null) this.notification.shutdown();
        this.cloudDriver().getLogger().severe("An error occurred while trying to start the cloud watcher extension.");
        this.cloudDriver().getLogger().log(Level.SEVERE, reason.getMessage(), reason);

    }

}
