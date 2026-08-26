package de.lino.cloud.extensions.watcher;

import de.lino.cloud.api.event.DatabaseWatchEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.notification.DatabaseNotification;
import de.lino.database.database.sql.postgresql.PostgresDatabaseNotification;

/**
 * Watches {@link StoredFile}'s table for writes via Postgres {@code LISTEN}/{@code NOTIFY}
 * and routes each notification through {@link DatabaseWatchEvent} - the extension form of what
 * used to be {@code CloudBootstrap}'s own {@code startDatabaseChangeNotifier} method. Declares a
 * dependency on {@code "cloud-driver-bootstrap"} in its {@code extension.json} (see
 * {@link Extension} for how dependency ordering works), so {@link
 * de.lino.cloud.api.factory.ExtensionFactory#start} always starts the host bootstrap's own
 * placeholder extension first.
 *
 * <p>Resolves its own {@link Credentials} from {@code postgres-database.json} independently of
 * whatever already loaded them elsewhere in the host process - an {@link Extension} is only ever
 * constructed via a no-arg constructor, so it has no way to receive an already-loaded {@code
 * Credentials} instance from its host.
 */
@SuppressWarnings("unchecked")
public class CloudWatcherExtension extends Extension {

    private DatabaseNotification notification;

    /**
     * Resolves {@link Credentials} from {@code postgres-database.json} and constructs this
     * instance's {@link PostgresDatabaseNotification} on channel {@code "cloud_driver_watcher"}.
     * Does not yet install any trigger or open the {@code LISTEN} connection - see
     * {@link #onRunning(String[])}.
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
     * listening. {@code watch(StoredFile.class)} is called unguarded (no try/catch) because
     * {@link PostgresDatabaseNotification#watch} swallows and logs its own {@code SQLException}
     * internally rather than throwing - safe to call even before the table exists, though it
     * only takes effect once the table is actually there (guaranteed in the {@code
     * cloud-driver-bootstrap} flow by {@code CloudBootstrap#loadSecurityRequirements} running,
     * synchronously, before any extension is started). Each notification is routed through
     * {@link #cloudAPI()}'s {@code EventFactory} as a {@link DatabaseWatchEvent}.
     *
     * @param args the arguments passed from the command line, unused by this extension
     */
    @Override
    public void onRunning(String[] args) {

        this.notification.watch(StoredFile.class);
        this.notification.start(payload -> this.cloudAPI().getEventFactory().callEvent(DatabaseWatchEvent.class, payload));

    }

    /**
     * Shuts the listener down, if it was ever started. Null-guarded because {@link #onLoading()}
     * can itself throw before {@link #notification} is assigned - {@link
     * de.lino.cloud.api.factory.ExtensionFactory#stop} calls this method unconditionally,
     * regardless of whether loading ever succeeded.
     */
    @Override
    public void onEnding() {
        if (this.notification != null) this.notification.shutdown();
    }

    /**
     * Shuts the listener down the same way {@link #onEnding()} does, for the same null-guarded
     * reason - a failure in {@link #onLoading()} routes here with {@link #notification} still
     * {@code null}.
     *
     * @param reason the exception that occurred; not otherwise acted on, since {@code
     *               PostgresDatabaseNotification} has no reconnect logic to trigger on failure
     */
    @Override
    public void onException(RuntimeException reason) {
        if (this.notification != null) this.notification.shutdown();
    }

}
