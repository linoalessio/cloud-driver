package de.lino.cloud.api.file.exception;

import de.lino.cloud.api.file.StoredFileSummary;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown by {@code CloudUserService#replaceFileContent} (the optimistic-concurrency overload -
 * section 10, Sync, {@code architecture/MICRO.md}) when the caller's {@code
 * expectedUpdatedAtEpochMillis} precondition no longer matches the file's current {@code
 * updatedAtEpochMilli} - someone else's write reached the server first. The canonical file is left
 * completely untouched by this call (last write, in the sense of "whoever legitimately committed
 * last", keeps its place under the original name); the caller's own {@code newContent} is instead
 * persisted as a brand-new file (a "conflicted copy", the same Drive/Dropbox-style UX the handoff
 * doc names explicitly) in the same folder, never silently discarded. {@link
 * #conflictedCopy()} carries that new file's summary so the caller can surface/navigate to it
 * directly, rather than just being told a conflict happened with nothing to show for it.
 */
public final class SyncConflictException extends RuntimeException {

    private final StoredFileSummary conflictedCopy;

    public SyncConflictException(@NotNull final StoredFileSummary conflictedCopy) {
        super("Content changed since it was last read - your edit was saved as a conflicted copy: " + conflictedCopy.fileName());
        this.conflictedCopy = conflictedCopy;
    }

    /** @return the newly-created "conflicted copy" file's summary - the caller's own {@code newContent}, preserved rather than discarded */
    @NotNull
    public StoredFileSummary conflictedCopy() {
        return this.conflictedCopy;
    }

}
