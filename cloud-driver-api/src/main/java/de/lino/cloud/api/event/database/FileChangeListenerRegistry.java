package de.lino.cloud.api.event.database;

import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

/**
 * The fan-out registry {@link DatabaseWatchEvent#handle} dispatches every {@link StoredFile}
 * table change notification through, reached via {@code
 * de.lino.cloud.api.factory.container.IFactoryContainer#getFileChangeListenerRegistry()} - see
 * {@link FileChangeListener}'s own Javadoc for why this exists instead of every addon calling
 * {@code EventFactory#registerEvent} directly.
 *
 * <p>Always present (never {@code null}) once {@code CloudDriver} itself is set up - unlike
 * {@code IServiceContainer}'s facets, this one is constructed unconditionally alongside every
 * other {@code IFactoryContainer} facet, regardless of whether {@code cloud-driver-watcher} (the
 * extension that actually drives {@link DatabaseWatchEvent}) is running on this deployment. A
 * listener can register at any time; if nothing ever calls {@link #notifyChange}, it simply
 * never fires - the same graceful-degradation contract every optional extension in this
 * codebase already has to honor.
 */
public interface FileChangeListenerRegistry {

    /**
     * Registers {@code listener} to be notified of every future {@link StoredFile} table change.
     * Registering the same instance twice results in it being notified twice per change -
     * callers are responsible for registering exactly once (e.g. from {@code onLoading()},
     * unregistering from {@code onEnding()}/{@code onException}).
     *
     * @param listener the callback to register
     */
    void register(@NotNull FileChangeListener listener);

    /**
     * Unregisters {@code listener}, if currently registered - a no-op otherwise (idempotent,
     * matching {@code ExtensionFactory#stop}'s own "safe to call more than once" convention).
     *
     * @param listener the callback to unregister
     */
    void unregister(@NotNull FileChangeListener listener);

    /**
     * Notifies every currently registered listener, in registration order, that {@code
     * storedFileId} changed via {@code operation}. Never throws - a listener's own exception is
     * caught and logged so it can never prevent a sibling listener from being notified, or
     * propagate into the Postgres {@code LISTEN}/{@code NOTIFY} listener thread {@link
     * DatabaseWatchEvent#handle} ultimately calls this from.
     *
     * @param storedFileId the changed {@link StoredFile}'s id
     * @param operation the raw Postgres trigger operation, {@code "INSERT"} or {@code "UPDATE"}
     */
    void notifyChange(@NotNull String storedFileId, @NotNull String operation);

}
