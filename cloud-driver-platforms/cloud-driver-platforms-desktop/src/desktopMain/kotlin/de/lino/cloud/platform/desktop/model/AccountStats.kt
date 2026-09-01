package de.lino.cloud.platform.desktop.model

import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.desktop.utils.mapConcurrently
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Aggregate counts for [DashboardScreen] - everything the signed-in account owns, across every folder. */
data class AccountStats(
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
)

/**
 * Walks the caller's entire folder tree from the root, concurrently (via [mapConcurrently], same
 * cap as every other batch operation in this app), summing file counts/folder counts/byte sizes
 * as it goes. There is no server-side "give me totals" endpoint - `GET /files`/`GET /folders` are
 * both scoped to one folder at a time (see CLAUDE.md's "Folder organization" section) - so this
 * is the same shape as [AppViewModel.deleteFolderRecursively]/`#downloadFolderRecursively`: a
 * client-side recursive walk, each folder's own children fetched/summed concurrently with its
 * siblings.
 *
 * A plain `Int`/`Long` accumulator would race across concurrently-running branches of the walk,
 * so counts are folded through a small [Mutex]-guarded accumulator rather than closed-over `var`s.
 */
suspend fun CloudDriverClient.computeAccountStats(): AccountStats {
    val mutex = Mutex()
    var fileCount = 0
    var folderCount = 0
    var totalBytes = 0L

    suspend fun walk(folderId: String?) {
        val files = this.listFiles(folderId)
        val folders = this.listFolders(folderId)

        mutex.withLock {
            fileCount += files.size
            folderCount += folders.size
            totalBytes += files.sumOf { it.sizeBytes() }
        }

        folders.mapConcurrently { folder -> walk(folder.folderId()) }
    }

    walk(null)
    return AccountStats(fileCount, folderCount, totalBytes)
}
