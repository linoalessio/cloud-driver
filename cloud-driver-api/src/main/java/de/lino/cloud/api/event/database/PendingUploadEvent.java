package de.lino.cloud.api.event.database;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.file.StoredFile;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.Optional;

/**
 * Fires once a previously offline-deferred {@link StoredFile} upload from a {@code
 * PendingUploadScheduler} succeeds. {@code properties} carries only the uploaded file's id, so this
 * class re-fetches the actual entity to confirm and log it.
 */
public class PendingUploadEvent extends Event {

    /**
     * Re-fetches the {@link StoredFile} named by {@code properties}' {@code "fileId"} field,
     * reloading {@link StoredFile}'s section first so the just-flushed row becomes visible. A blank
     * id is a no-op; a miss after reloading is logged and ignored rather than thrown.
     *
     * @param properties the payload, carrying the uploaded file's {@code "fileId"}
     */
    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("fileId");
        if (id.isBlank()) return;

        this.cloudDriver().getFactoryContainer().getDataFactory().reload(StoredFile.class);
        final Optional<StoredFile> pendingFile = this.cloudDriver().getFactoryContainer().getFileFactory().findById(id);

        if (pendingFile.isEmpty()) {
            this.cloudDriver().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));
            return;
        }

        this.cloudDriver().getLogger().info(String.format("Pending file uploaded: '%s'", pendingFile.get()));

    }

}
