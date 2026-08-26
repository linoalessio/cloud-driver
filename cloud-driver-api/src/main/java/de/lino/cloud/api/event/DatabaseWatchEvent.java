package de.lino.cloud.api.event;

import de.lino.cloud.api.file.StoredFile;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

public class DatabaseWatchEvent extends Event {

    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("id");
        if (id.isBlank()) return;

        final StoredFile uploadedFile = this.cloudAPI().getFileFactory().findById(id).orElseThrow();

    }

}
