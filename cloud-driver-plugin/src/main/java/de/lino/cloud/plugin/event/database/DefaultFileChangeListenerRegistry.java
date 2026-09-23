package de.lino.cloud.plugin.event.database;

import de.lino.cloud.api.event.database.FileChangeListener;
import de.lino.cloud.api.event.database.FileChangeListenerRegistry;
import lombok.NonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one {@link FileChangeListenerRegistry} implementation - a {@link CopyOnWriteArrayList}
 * backs the listener set, matching the "small, rarely-mutated list, read far more often than
 * written" shape this class actually has: {@link #register}/{@link #unregister} only ever run at
 * extension start/stop, while {@link #notifyChange} runs once per Postgres change notification,
 * potentially in a tight burst (a large upload batch).
 */
public final class DefaultFileChangeListenerRegistry implements FileChangeListenerRegistry {

    private static final Logger LOGGER = Logger.getLogger(DefaultFileChangeListenerRegistry.class.getName());

    private final List<FileChangeListener> listeners = new CopyOnWriteArrayList<>();

    /** {@inheritDoc} */
    @Override
    public void register(@NonNull final FileChangeListener listener) {
        this.listeners.add(listener);
    }

    /** {@inheritDoc} */
    @Override
    public void unregister(@NonNull final FileChangeListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Iterates a snapshot of {@link #listeners} (inherent to {@link CopyOnWriteArrayList}'s
     * own iterator, so a concurrent {@link #register}/{@link #unregister} during this call is
     * safe and never throws {@link java.util.ConcurrentModificationException}) and catches each
     * listener's failure of any kind individually, so one broken listener never stops a later one
     * in the list from being notified - matching {@code FileChangeListenerRegistry#notifyChange}'s
     * own unqualified "never throws" contract. An {@link Error} is logged at {@link Level#SEVERE},
     * because the process may be poisoned even though the fan-out carries on.
     */
    @Override
    public void notifyChange(@NonNull final String storedFileId, @NonNull final String operation) {
        for (final FileChangeListener listener : this.listeners) {
            try {
                listener.onFileChanged(storedFileId, operation);
            } catch (final Throwable e) {
                LOGGER.log(e instanceof Error ? Level.SEVERE : Level.WARNING,
                        "@DefaultFileChangeListenerRegistry.notifyChange: listener "
                        + listener.getClass().getName() + " failed for file '" + storedFileId + "'", e);
            }
        }
    }

}
