package de.lino.cloud.extensions.watcher;

import de.lino.cloud.api.event.DatabaseWatchEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.notification.DatabaseNotification;
import de.lino.database.database.sql.postgresql.PostgresDatabaseNotification;

@SuppressWarnings("unchecked")
public class CloudWatcherExtension extends Extension {

    private DatabaseNotification notification;

    @Override
    public void onLoading() {

        final Credentials credentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).orElseThrow();
        this.notification = new PostgresDatabaseNotification(credentials, "cloud_driver_watcher");

    }

    @Override
    public void onRunning(String[] args) {

        this.notification.watch(StoredFile.class);
        this.notification.start(payload -> this.cloudAPI().getEventFactory().callEvent(DatabaseWatchEvent.class, payload));

    }

    @Override
    public void onEnding() {
        if (this.notification != null) this.notification.shutdown();
    }

    @Override
    public void onException(RuntimeException reason) {
        if (this.notification != null) this.notification.shutdown();
    }

}
