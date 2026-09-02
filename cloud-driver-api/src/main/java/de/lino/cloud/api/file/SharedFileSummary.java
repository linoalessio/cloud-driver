package de.lino.cloud.api.file;

import org.jetbrains.annotations.NotNull;

/**
 * One entry in {@code ICloudUserService#listSharedWithMe}'s response - a {@link
 * StoredFileSummary} paired with the email address of the account that shared it, so a grantee
 * can see <em>who</em> shared a file with them, not just that it was shared. Added 2026-09-02:
 * the plain {@link StoredFileSummary} this method returned before carried no owner information
 * at all, so a "Shared with me" listing had no way to display who a file came from.
 *
 * @param file the shared file's descriptive fields (no content - same reasoning {@link
 *             StoredFileSummary} itself documents)
 * @param ownerEmail the sharing account's email address
 */
public record SharedFileSummary(@NotNull StoredFileSummary file, @NotNull String ownerEmail) {
}
