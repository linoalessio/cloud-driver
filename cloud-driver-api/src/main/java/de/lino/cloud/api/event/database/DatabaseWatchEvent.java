package de.lino.cloud.api.event.database;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.push.LiveUpdatePublisher;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

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
     * {@link StoredFile}'s section only if a first, no-reload lookup misses. A blank id is a no-op;
     * a miss that persists after reloading is logged and ignored rather than thrown, since an
     * uncaught exception here would kill the underlying notification listener thread for good. Note
     * that a real failure from {@code findById} itself (as opposed to a plain miss) is not guarded
     * the same way - see the {@code @throws} list below.
     *
     * <p><b>Fixed a real OOM incident (2026-09-02):</b> this used to call {@code reload(StoredFile
     * .class)} unconditionally, on every single notification, before ever attempting {@code
     * findById} - {@code reload} re-reads {@code StoredFile}'s <i>entire</i> table (every row's
     * encrypted content included) into this process's local section mirror, so on a table that has
     * grown to hold real file content, that's a full-table, full-blob load into heap, every time.
     * Extracting a ~700 MB zip archive in the desktop app uploads many files in quick succession,
     * each insert firing its own {@code NOTIFY} - and since this deployment's Postgres instance runs
     * co-located with this very process (see {@code CloudBootstrap}'s connectivity-checker
     * incident), the process that receives almost every one of these notifications is the exact
     * same process that just performed the write, whose local section mirror is already
     * up to date from that write - {@code EntityDatabaseClient#store} updates it directly, with no
     * need for a database round trip at all. Reloading anyway, for every notification in the burst,
     * repeatedly re-read the whole (by-then large) table into memory - several such reloads racing
     * against each other's not-yet-collected garbage exhausted the heap, surfaced as {@code
     * PSQLException: Ran out of memory retrieving query results} deep in the JDBC driver's own
     * result-set buffering. A reload is only ever actually needed for a row some <i>other</i>
     * process wrote (this process's own mirror would never see it otherwise) - a real but rare case
     * in this single-process deployment - so this method now tries the cheap, reload-free lookup
     * first and only pays for a reload on an actual miss, before retrying once.
     *
     * <p>Resolves metadata only, never content. This method reads exactly one thing from the
     * lookup - whether a row exists - and resolving content here meant every notification for an
     * S3-backed file pulled that whole object back out of the store, decrypted it into one array
     * and checksummed it, on the single thread that also drives live push, malware scanning and
     * thumbnail generation. For a multi-gigabyte upload that is an out-of-memory error on the one
     * thread the system can least afford to lose.
     *
     * <p>Handles its own failures rather than letting them escape: the dispatch boundary this is
     * called from cannot catch a checked exception, so one corrupted row used to kill the
     * notification thread permanently.
     *
     * @param properties the notification payload ({@code "table"}/{@code "operation"}/{@code "id"})
     */
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final String id = properties.getString("id");
        if (id.isBlank()) return;

        final DataFactory dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();

        Optional<StoredFile> uploadedFile;
        try {
            uploadedFile = dataFactory.findById(id, StoredFile.class);
            if (uploadedFile.isEmpty()) {
                dataFactory.reload(StoredFile.class);
                uploadedFile = dataFactory.findById(id, StoredFile.class);
            }
        } catch (final Throwable lookupFailed) {
            // Existence could not be established, by any failure at all. The listeners below still
            // need to run - a scan or a live push matters more than this method's warning line -
            // so carry on as though the row were present and say why it could not be confirmed.
            this.cloudDriver().getLogger().log(Level.WARNING,
                    String.format("Could not confirm whether file id '%s' exists while handling a change notification", id), lookupFailed);
            uploadedFile = Optional.empty();
            this.pushLiveUpdate(properties, id);
            this.notifyFileChangeListeners(properties, id);
            return;
        }

        this.pushLiveUpdate(properties, id);
        this.notifyFileChangeListeners(properties, id);

        if (uploadedFile.isPresent()) return;

        this.cloudDriver().getLogger().warning(String.format("Received change notification for unknown file id '%s' - ignoring", id));

    }

    /**
     * Live push via WebSocket: resolves which
     * account owns {@code id} (via {@link ICloudUserService#resolveOwnerAuthUserId}, only
     * reachable once {@code CloudRestExtension} has published one into {@code IServiceContainer} -
     * see that interface's own Javadoc) and forwards this notification's raw {@code
     * "table"}/{@code "operation"} fields to {@link LiveUpdatePublisher#publish}, if one has been
     * published there too. Runs unconditionally (regardless of whether {@code findById} above hit
     * or missed) - a client's live-refresh trigger doesn't need this event's own re-fetch to have
     * succeeded, only to know that *something* changed for its account.
     *
     * <p>Deliberately never lets a failure of any kind escape into the caller: this method runs
     * inside a Postgres {@code LISTEN}/{@code NOTIFY}-driven listener thread with no tolerance for
     * anything uncaught (see {@code CloudWatcherExtension}'s own {@code dispatch}-callback
     * try/catch for the same reasoning) - a broken push must never take down change-notification
     * handling itself, and must never skip the listener fan-out that follows it.
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

        } catch (final Throwable e) {
            this.cloudDriver().getLogger().log(Level.WARNING, "Failed to push live update for file id '" + id + "'", e);
        }
    }

    /**
     * Forwards this notification's raw {@code "operation"} field to every registered {@link
     * FileChangeListener}, via {@link FileChangeListenerRegistry#notifyChange} - see {@link
     * FileChangeListener}'s own Javadoc for why this fan-out exists at all (one {@code Event}
     * class can only ever have one {@code EventFactory}-registered handler). Runs unconditionally,
     * regardless of whether {@link #handle}'s own {@code findById}/reload above hit or missed - a
     * listener (e.g. a thumbnail generator) only needs the id and operation, it re-fetches
     * whatever content it actually needs itself.
     *
     * <p>{@link FileChangeListenerRegistry#notifyChange} itself never throws (it catches and
     * logs each listener's own failure individually, of any kind) - this method's own try/catch is
     * defense-in-depth on top of that, matching {@link #pushLiveUpdate}'s own reasoning: this
     * runs inside a Postgres {@code LISTEN}/{@code NOTIFY}-driven listener thread with zero
     * tolerance for anything uncaught.
     *
     * @param properties this event's own notification payload
     * @param id the changed {@link StoredFile}'s id, already extracted by the caller
     */
    private void notifyFileChangeListeners(@NonNull final JsonDocument properties, @NonNull final String id) {
        try {
            this.cloudDriver().getFactoryContainer().getFileChangeListenerRegistry()
                    .notifyChange(id, properties.getString("operation"));
        } catch (final Throwable e) {
            this.cloudDriver().getLogger().log(Level.WARNING, "Failed to notify file change listeners for file id '" + id + "'", e);
        }
    }

}
