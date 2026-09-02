package de.lino.cloud.api.file;

import org.jetbrains.annotations.NotNull;

/**
 * One entry in {@code ICloudUserService#listDeletedFiles}'s response - a {@link StoredFileSummary}
 * paired with the epoch-millis instant it becomes eligible for permanent removal, so the trash UI
 * can show a caller when a file will actually be gone for good, not just that it's currently
 * trashed. Added 2026-09-02.
 *
 * <p>{@code purgeAtEpochMillis} is computed as {@code StoredFileOwnership#getDeletedAtEpochMillis()
 * + the configured trash retention window} (mirroring {@code TrashPurgeScheduler}'s own retention
 * resolution - see {@code CloudUserService#resolveTrashRetentionDays}'s Javadoc for why that logic
 * is necessarily duplicated rather than shared, given this codebase's module dependency direction).
 * This reflects the <em>configured</em> retention window, not a guarantee that a purge will
 * actually happen at that instant - {@code TrashPurgeScheduler} is deliberately never started
 * automatically (see its own Javadoc), so on a deployment that never wires it in, a file past this
 * timestamp simply stays in the trash indefinitely rather than actually being purged.
 *
 * @param file the trashed file's descriptive fields (no content)
 * @param purgeAtEpochMillis when this file becomes eligible for permanent removal, per the
 *                           configured retention window
 */
public record TrashedFileSummary(@NotNull StoredFileSummary file, long purgeAtEpochMillis) {
}
