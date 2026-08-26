package de.lino.cloud.api.event.database;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.file.StoredFile;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.Optional;

/**
 * Fires once per Postgres change notification a {@code DatabaseNotification} listener delivers
 * for {@link StoredFile}'s table. {@code properties} carries only {@code {"table", "operation",
 * "id"}} - never the row's own encrypted data - so this class re-fetches the actual entity.
 */
public class DatabaseWatchEvent extends Event {

    /**
     * Re-fetches the {@link StoredFile} named by {@code properties}' {@code "id"} field, reloading
     * {@link StoredFile}'s section first so a row written by another process becomes visible. A
     * blank id is a no-op; a miss after reloading is logged and ignored rather than thrown, since an
     * uncaught exception here would kill the underlying notification listener thread for good.
     *
     * @param properties the notification payload ({@code "table"}/{@code "operation"}/{@code "id"})
     */
    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("id");
        if (id.isBlank()) return;

        this.cloudDriver().getDataFactory().reload(StoredFile.class);
        final Optional<StoredFile> uploadedFile = this.cloudDriver().getFileFactory().findById(id);

        if (uploadedFile.isEmpty()) {
            CloudDriver.getInstance().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));
            return;
        }

        CloudDriver.getInstance().getLogger().info(String.format("Received new file: '%s'", uploadedFile.get()));

    }

}
