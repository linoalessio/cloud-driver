package de.lino.cloud.api.event.database;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Fires once a previously offline-deferred {@link StoredFile} upload from a {@code
 * PendingUploadScheduler} succeeds. {@code properties} carries only the uploaded file's id, so this
 * class re-reads the row to confirm and log it.
 */
public class PendingUploadEvent extends Event {

    /**
     * Confirms that the {@link StoredFile} named by {@code properties}' {@code "fileId"} field
     * really landed.
     *
     * <p>Metadata only, and reload-free first: this reads exactly one thing - whether the row is
     * there - and the process that just flushed it is this one, so its own view is already
     * current. Only a miss is worth a reload, which is what a row another process wrote would
     * need. A blank id is a no-op; a miss after reloading is logged and ignored.
     *
     * <p>Handles its own failures rather than letting any escape. This runs inside a {@code
     * CompletableFuture} stage whose chain ends in {@code .exceptionally(stillFailing -> null)},
     * so anything thrown here would be swallowed with no log at all.
     *
     * @param properties the payload, carrying the uploaded file's {@code "fileId"}
     */
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("fileId");
        if (id.isBlank()) return;

        final DataFactory dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();

        final Optional<StoredFile> pendingFile;
        try {
            if (dataFactory.findById(id, StoredFile.class).isPresent()) return;
            dataFactory.reload(StoredFile.class);
            pendingFile = dataFactory.findById(id, StoredFile.class);
        } catch (final Throwable lookupFailed) {
            this.cloudDriver().getLogger().log(Level.WARNING,
                    String.format("Could not confirm whether the flushed file id '%s' was persisted", id), lookupFailed);
            return;
        }

        if (pendingFile.isPresent()) return;

        this.cloudDriver().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));

    }

}
