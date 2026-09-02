package de.lino.cloud.api.event.database;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.push.LiveUpdatePublisher;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.Optional;
import java.util.logging.Level;

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

        this.pushLiveUpdate(properties, id);

        if (uploadedFile.isPresent()) return;

        this.cloudDriver().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));

    }

    /**
     * Item 10 (live push via WebSocket, see {@code architecture/SERVICES.md}): resolves which
     * account owns {@code id} (via {@link ICloudUserService#resolveOwnerAuthUserId}, only
     * reachable once {@code CloudRestExtension} has published one into {@code IServiceContainer} -
     * see that interface's own Javadoc) and forwards this notification's raw {@code
     * "table"}/{@code "operation"} fields to {@link LiveUpdatePublisher#publish}, if one has been
     * published there too. Runs unconditionally (regardless of whether {@code findById} above hit
     * or missed) - a client's live-refresh trigger doesn't need this event's own re-fetch to have
     * succeeded, only to know that *something* changed for its account.
     *
     * <p>Deliberately never lets a failure here escape into the caller: this method runs inside a
     * Postgres {@code LISTEN}/{@code NOTIFY}-driven listener thread with no tolerance for an
     * uncaught exception (see {@code CloudWatcherExtension}'s own {@code dispatch}-callback
     * try/catch for the same reasoning) - a broken push must never take down change-notification
     * handling itself.
     *
     * @param properties this event's own notification payload
     * @param id the changed {@link StoredFile}'s id, already extracted by the caller
     */
    private void pushLiveUpdate(@NonNull final JsonDocument properties, @NonNull final String id) {
        try {

            final ICloudUserService cloudUserService = this.cloudDriver().getServiceContainer().getCloudUserService();
            final LiveUpdatePublisher publisher = this.cloudDriver().getServiceContainer().getLiveUpdatePublisher();
            if (cloudUserService == null || publisher == null) return;

            cloudUserService.resolveOwnerAuthUserId(id).ifPresent(authUserId ->
                    publisher.publish(authUserId, properties.getString("table"), properties.getString("operation"), id));

        } catch (final RuntimeException e) {
            this.cloudDriver().getLogger().log(Level.WARNING, "Failed to push live update for file id '" + id + "'", e);
        }
    }

}
