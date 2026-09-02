package de.lino.cloud.platform.desktop.model

import de.lino.cloud.platform.desktop.client.CloudDriverClient

/**
 * Aggregate counts for [DashboardScreen] - everything the signed-in account owns, across every
 * folder. [trashBytes] is the total size of files currently sitting in the trash (a subset of
 * [totalBytes] - a trashed file still occupies real storage until it's purged, see
 * `CloudUserService`'s own "Recycle bin / soft delete" Javadoc server-side), summed separately
 * from the live-tree walk below since trashed files aren't part of it. [sharedFileCount] (added
 * 2026-09-02) is the number of files *directly shared with* the signed-in account (item 9) - not
 * a subset of [fileCount]/[totalBytes], which only ever count files this account owns.
 */
data class AccountStats(
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val trashBytes: Long,
    val sharedFileCount: Int,
)

/**
 * Walks the caller's entire folder tree from the root, summing file counts/folder counts/byte
 * sizes as it goes. There is no server-side "give me totals" endpoint - `GET /files`/`GET
 * /folders` are both scoped to one folder at a time (see CLAUDE.md's "Folder organization"
 * section) - so this is a client-side recursive walk.
 *
 * **Deliberately sequential, not concurrent (fixed a real bug, 2026-09-01).** This used to recurse
 * via `mapConcurrently` (one call per folder level, each with its own fresh, uncoordinated
 * semaphore) - the identical shape that made `AppViewModel.deleteEntries` throw `"too many
 * concurrent streams"` on a large-enough folder tree (see that function's own Javadoc for the full
 * mechanism): the real number of simultaneously in-flight HTTP requests multiplied with the tree's
 * depth/breadth instead of ever being capped, risking the same error here too against a wide/deep
 * enough account. Since this only ever issues read-only listing calls (no file content, no
 * upload/download), a plain sequential walk removes the risk entirely at a cost this call site can
 * afford - unlike `AppViewModel`'s file-duplicating/-deleting batches, there is no expensive
 * per-item network transfer here to parallelize; `listFiles`/`listFolders` are cheap metadata-only
 * calls. A single-threaded walk also means no concurrent branches can race the running totals, so
 * the `Mutex`-guarded accumulator this function used to need is gone too - plain closed-over `var`s
 * are enough.
 */
suspend fun CloudDriverClient.computeAccountStats(): AccountStats {
    var fileCount = 0
    var folderCount = 0
    var totalBytes = 0L

    suspend fun walk(folderId: String?) {
        val files = this.listFiles(folderId)
        val folders = this.listFolders(folderId)

        fileCount += files.size
        folderCount += folders.size
        totalBytes += files.sumOf { it.sizeBytes() }

        folders.forEach { folder -> walk(folder.folderId()) }
    }

    walk(null)
    val trashBytes = this.listDeletedFiles().sumOf { it.file().sizeBytes() }
    val sharedFileCount = this.listSharedWithMe().size
    return AccountStats(fileCount, folderCount, totalBytes, trashBytes, sharedFileCount)
}
