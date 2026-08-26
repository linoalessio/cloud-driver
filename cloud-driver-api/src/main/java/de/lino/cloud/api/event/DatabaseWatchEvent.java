package de.lino.cloud.api.event;

import de.lino.cloud.api.file.StoredFile;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

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
     * @param properties the notification payload ({@code "table"}/{@code "operation"}/{@code "id"})
     */
    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("id");
        if (id.isBlank()) return;

        final StoredFile uploadedFile = this.cloudAPI().getFileFactory().findById(id).orElseThrow();

    }

}
