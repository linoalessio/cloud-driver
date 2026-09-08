package de.lino.cloud.api.event.database;

import de.lino.cloud.api.file.StoredFile;

/**
 * A callback notified whenever {@link DatabaseWatchEvent} observes a write to {@link
 * StoredFile}'s table - register one via {@link FileChangeListenerRegistry#register}.
 *
 * <p><b>Why this exists, architecturally:</b> {@code EventFactory} constructs exactly one
 * handler instance per registered {@code Event} subclass and throws {@code
 * IllegalStateException} if a second caller tries to register the same class - so multiple
 * independent extensions (thumbnails, search indexing, sync, push notifications, ...) cannot
 * each call {@code registerEvent(DatabaseWatchEvent.class)} themselves. This interface, and
 * {@link FileChangeListenerRegistry}, are the fan-out point: {@code DatabaseWatchEvent} (the
 * one registered {@code Event} handler) notifies every registered {@code FileChangeListener} in
 * turn, so any number of addons can independently observe the same underlying notification
 * stream without touching {@code EventFactory}'s own one-handler-per-class contract at all - this
 * was chosen over changing {@code EventFactory} itself.
 *
 * <p>{@code storedFileId} is the changed {@link StoredFile}'s id; {@code operation} is the raw
 * Postgres trigger operation - {@code "INSERT"} for a brand-new file, {@code "UPDATE"} for
 * anything that changes an existing row (a rename, a soft delete/restore, a move to S3, ...).
 * There is currently no {@code "DELETE"} notification at all - the underlying trigger only fires
 * {@code AFTER INSERT OR UPDATE} (see {@code PostgresDatabaseNotification}'s own Javadoc) - so a
 * listener that needs to react to a file's content being permanently removed (e.g. to drop a
 * derived index entry) must hook the relevant hard-delete call site directly instead of relying
 * on this notification stream.
 *
 * <p>Implementations must never let an exception escape {@link #onFileChanged}: {@link
 * FileChangeListenerRegistry#notifyChange} catches and logs one anyway (so a broken listener can
 * never affect a sibling listener or the underlying Postgres {@code LISTEN}/{@code NOTIFY}
 * listener thread this is ultimately invoked from), but a well-behaved implementation should
 * still dispatch any real work onto its own executor rather than blocking this callback, the
 * same "don't do slow work on the notification thread" discipline {@code
 * cloud-driver-extensions-thumbnails}'s {@code CloudThumbnailsExtension} follows.
 */
@FunctionalInterface
public interface FileChangeListener {

    /**
     * Called once per {@code INSERT}/{@code UPDATE} notification for {@link StoredFile}'s table.
     *
     * @param storedFileId the changed {@link StoredFile}'s id
     * @param operation the raw Postgres trigger operation, {@code "INSERT"} or {@code "UPDATE"}
     */
    void onFileChanged(String storedFileId, String operation);

}
