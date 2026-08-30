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
     * uncaught exception here would kill the underlying notification listener thread for good. Note
     * that a real failure from {@code findById} itself (as opposed to a plain miss) is not guarded
     * the same way - see the {@code @throws} list below.
     *
     * @param properties the notification payload ({@code "table"}/{@code "operation"}/{@code "id"})
     * @throws de.lino.cloud.api.security.database.DatabaseClientException if the file exists but its record is corrupted - sneaky-thrown by {@code @SneakyThrows}, not declared on this method's signature
     * @throws de.lino.cloud.api.security.keys.KeyWrapException if the file's data-encryption key cannot be unwrapped by the KMS/HSM - sneaky-thrown
     * @throws de.lino.cloud.api.security.crypto.AuthenticationFailedException if the retrieved payload fails authentication - sneaky-thrown
     * @throws de.lino.cloud.api.file.exception.FileIntegrityException if the decrypted content does not match its recorded checksum - sneaky-thrown
     */
    @SneakyThrows
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("id");
        if (id.isBlank()) return;

        this.cloudDriver().getFactoryContainer().getDataFactory().reload(StoredFile.class);
        final Optional<StoredFile> uploadedFile = this.cloudDriver().getFactoryContainer().getFileFactory().findById(id);

        if (uploadedFile.isEmpty()) {
            this.cloudDriver().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));
            return;
        }

        this.cloudDriver().getLogger().info(String.format("Received new file: '%s'", uploadedFile.get()));

    }

}
