package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

public class FileUploadPendingEvent extends Event {

    @Override
    public void handle(@NonNull JsonDocument properties) {

        final StoredFile pendingFile = Asserts.assertNotNull(
                properties.get("pendingFile", StoredFile.class)
                , "@FileUploadPendingEvent.handle: Pending file must not be null"
        );
    }

}
