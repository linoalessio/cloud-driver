package de.lino.cloud.api.file;

import org.jetbrains.annotations.NotNull;

/**
 * One entry in {@code ICloudUserService#listDeletedFolders}'s response - a {@link Folder} paired
 * with the epoch-millis instant it becomes eligible for permanent removal, the same "when will
 * this actually be gone" pairing {@link TrashedFileSummary} adds for files, and for the same
 * reason - see that record's own Javadoc for how {@code purgeAtEpochMillis} is computed and why
 * it reflects the configured retention window rather than a purge guarantee.
 *
 * @param folder the trashed folder itself
 * @param purgeAtEpochMillis when this folder becomes eligible for permanent removal, per the
 *                           configured retention window
 */
public record TrashedFolderSummary(@NotNull Folder folder, long purgeAtEpochMillis) {
}
