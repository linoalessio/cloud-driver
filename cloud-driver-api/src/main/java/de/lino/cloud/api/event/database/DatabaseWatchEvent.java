package de.lino.cloud.api.event.database;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.file.StoredFile;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.Optional;

/**
 * Fires once per Postgres change notification a {@code DatabaseNotification} listener delivers
 * (currently only {@code cloud-driver-extensions-watcher}'s {@code CloudWatcherExtension}, which
 * only watches {@link StoredFile}'s table). {@code properties} is the small JSON payload the
 * watched table's trigger sent via {@code pg_notify} - {@code {"table", "operation", "id"}},
 * never the row's own still-encrypted data - so this class re-fetches the actual entity through
 * {@code FileFactory} rather than the notification carrying it directly.
 */
public class DatabaseWatchEvent extends Event {

    /**
     * Re-fetches the {@link StoredFile} named by {@code properties}' {@code "id"} field - a
     * no-op if that field is blank, which should not normally happen for a well-formed
     * notification payload. Fetching (rather than trusting the notification's own payload)
     * matters here because the trigger deliberately never includes the row's own data.
     *
     * <p><b>Reloads {@link StoredFile}'s section before looking the id up</b> - {@code
     * DataFactory#reload(Class)} - because a notification only ever means "some process just
     * wrote a row to this table", and that process is very often <em>not</em> this one (a
     * notification fires for every writer, in-process or not - see {@code
     * PostgresDatabaseNotification}). The underlying {@code database-driver-plugin} section
     * implementations mirror every entry in process-local memory once loaded and only keep that
     * mirror in sync with writes made through that very same instance - a plain {@link
     * de.lino.cloud.api.factory.FileFactory#findById} never falls back to the database on a
     * cache miss the way it looks like it should, since the miss happens one layer lower, inside
     * the section's own read, which has no such fallback. Without the reload here, a file
     * uploaded by one process (e.g. a laptop talking to a shared Postgres instance) would never
     * become visible to another already-running process (e.g. the deployed server) watching the
     * same table - not eventually, not ever, until that process restarts.
     *
     * <p>A miss (the id still doesn't resolve to a {@link StoredFile} even after reloading, e.g.
     * a row already deleted again by the time this notification was processed) is logged and
     * otherwise ignored rather than thrown - {@code CloudWatcherExtension} routes every
     * notification through this method from directly inside {@code
     * PostgresDatabaseNotification#listen}'s loop, which has no {@code try/catch} of its own
     * around the callback: a {@code RuntimeException} escaping this method kills that loop's
     * dedicated listener thread for good (no reconnect logic there - see that class's Javadoc),
     * silently ending Postgres change notifications for the rest of the process's life.
     *
     * @param properties the notification payload ({@code "table"}/{@code "operation"}/{@code "id"})
     */
    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("id");
        if (id.isBlank()) return;

        this.cloudAPI().getDataFactory().reload(StoredFile.class);
        final Optional<StoredFile> uploadedFile = this.cloudAPI().getFileFactory().findById(id);

        if (uploadedFile.isEmpty()) {
            CloudAPI.getInstance().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));
            return;
        }

        CloudAPI.getInstance().getLogger().info(String.format("Received new file: '%s'", uploadedFile.get()));

    }

}
