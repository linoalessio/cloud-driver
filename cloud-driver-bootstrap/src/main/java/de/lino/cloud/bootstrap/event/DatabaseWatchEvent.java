package de.lino.cloud.bootstrap.event;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

public class DatabaseWatchEvent extends Event {

    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        Asserts.assertNotNull(CloudAPI.getInstance());

        final String id = properties.getString("id");
        if (id.isBlank()) return;

        final StoredFile uploadedFile = CloudAPI.getInstance().getFileFactory().findById(id).orElseThrow();

    }

}
