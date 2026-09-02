package de.lino.cloud.api.file;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The contents of a folder reachable via a share (added 2026-09-02, item 9's own documented
 * future extension - "browsing a shared folder's contents" - finally implemented): every
 * non-trashed file directly inside it (as {@link StoredFileSummary}s, no content) and every
 * non-trashed subfolder directly inside it, both still owned by the folder's actual owner (a
 * grantee never becomes an "owner" of anything by virtue of a share). Returned by {@code
 * ICloudUserService#listSharedFolderContents}, backing the desktop app's "browse into a shared
 * folder" and "download this shared folder" actions - previously a shared folder was display-only,
 * with no way to see or fetch what was actually inside it.
 *
 * @param files the non-trashed files directly inside the browsed folder
 * @param subfolders the non-trashed subfolders directly inside the browsed folder - each, in turn,
 *                    browsable the same way (a folder share covers everything nested inside it, at
 *                    any depth)
 */
public record SharedFolderContents(@NotNull List<StoredFileSummary> files, @NotNull List<Folder> subfolders) {
}
