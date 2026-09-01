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
     * id is a no-op; a miss after reloading is logged and ignored rather than thrown. Dispatched
     * from {@code PendingUploadScheduler#retryUpload} inside a {@code thenRun} stage whose whole
     * chain ends in {@code .exceptionally(stillFailing -> null)}, so any exception this method
     * itself throws - including one sneaky-thrown per the {@code @throws} list below - is silently
     * swallowed there rather than logged.
     *
     * @param properties the payload, carrying the uploaded file's {@code "fileId"}
     * @throws de.lino.cloud.api.security.database.DatabaseClientException if the file exists but its record is corrupted - sneaky-thrown by {@code @SneakyThrows}, not declared on this method's signature
     * @throws de.lino.cloud.api.security.keys.KeyWrapException if the file's data-encryption key cannot be unwrapped by the KMS/HSM - sneaky-thrown
     * @throws de.lino.cloud.api.security.crypto.AuthenticationFailedException if the retrieved payload fails authentication - sneaky-thrown
     * @throws de.lino.cloud.api.file.exception.FileIntegrityException if the decrypted content does not match its recorded checksum - sneaky-thrown
     */
    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("fileId");
        if (id.isBlank()) return;

        this.cloudDriver().getFactoryContainer().getDataFactory().reload(StoredFile.class);
        final Optional<StoredFile> pendingFile = this.cloudDriver().getFactoryContainer().getFileFactory().findById(id);

        if (pendingFile.isPresent()) return;

        this.cloudDriver().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));

    }

}
