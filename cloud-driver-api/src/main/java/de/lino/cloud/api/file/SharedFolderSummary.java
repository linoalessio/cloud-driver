package de.lino.cloud.api.file;

import org.jetbrains.annotations.NotNull;

/**
 * One entry in {@code ICloudUserService#listSharedFoldersWithMe}'s response - a {@link Folder}
 * paired with the email address of the account that shared it, the same "who shared this with
 * me" pairing {@link SharedFileSummary} adds for files, and for the same reason (added
 * 2026-09-02: the plain {@link Folder} this method returned before carried no owner information
 * a grantee could display).
 *
 * @param folder the shared folder itself
 * @param ownerEmail the sharing account's email address
 */
public record SharedFolderSummary(@NotNull Folder folder, @NotNull String ownerEmail) {
}
