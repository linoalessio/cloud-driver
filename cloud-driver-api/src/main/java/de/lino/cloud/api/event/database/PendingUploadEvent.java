package de.lino.cloud.api.event.database;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.file.StoredFile;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.Optional;

public class PendingUploadEvent extends Event {

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
