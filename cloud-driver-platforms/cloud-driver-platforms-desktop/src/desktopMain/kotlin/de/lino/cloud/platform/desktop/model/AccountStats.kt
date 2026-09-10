package de.lino.cloud.platform.desktop.model

import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.rest.api.ApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregate counts for [DashboardScreen] - everything the signed-in account owns, across every
 * folder. [trashBytes] is the total size of files currently sitting in the trash (a subset of
 * [totalBytes] - a trashed file still occupies real storage until it's purged, see
 * `CloudUserService`'s own "Recycle bin / soft delete" Javadoc server-side), summed separately
 * from the live-tree walk below since trashed files aren't part of it. [sharedFileCount] is the
 * number of the signed-in account's *own* files that currently have at least one active share -
 * i.e. how many files this account has shared *with* someone else, not how many files
 * are shared *with* this account (that would be [CloudDriverClient.listSharedWithMe]'s own count -
 * fixed 2026-09-03, this field used to hold that count instead, the wrong direction entirely). Not
 * a subset of [fileCount]/[totalBytes] - a shared file is still counted there too, since it's still
 * owned by this account.
 *
 * [totalBytes] is *logical* size - every listed file's `sizeBytes()` summed, duplicates counted
 * once per copy - and can therefore exceed the account's `currentUploadedBytes`, which is the
 * server's *physical*, dedup-aware quota counter (a duplicate upload becomes an alias, stored
 * once and never charged). The two are equal only for an account with no dedup aliases; the
 * Dashboard's storage card surfaces the difference as deduplication savings.
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
 * /folders` are both scoped to one folder at a time - so this is a client-side recursive walk.
 *
 * **One flat, bounded-concurrency walk (fixed 2026-09-05; previously fully sequential, fixed
 * 2026-09-01 from an even worse concurrency bug before that).** The original implementation
 * recursed via `mapConcurrently` (one call per folder level, each with its own fresh, uncoordinated
 * semaphore) - the identical shape that made `AppViewModel.deleteEntries` throw `"too many
 * concurrent streams"` on a large-enough folder tree (see that function's own Javadoc for the full
 * mechanism): the real number of simultaneously in-flight HTTP requests multiplied with the tree's
 * depth/breadth instead of ever being capped. That was fixed by making the walk fully sequential -
 * safe, but it over-corrected: for an account with hundreds or thousands of folders, a fully
 * sequential walk means that many *serialized* round trips before the Dashboard can render at all,
 * directly against this app's own "fit for big data" goal.
 *
 * This walk now shares one [Semaphore] (capped at [ApiClient.DEFAULT_MAX_CONCURRENT_TRANSFERS],
 * matching this codebase's existing concurrency-cap convention) across the *entire* recursive
 * walk, not per level - every [walk] call, at any depth, acquires a permit from the same instance
 * before issuing its own `listFiles`/`listFolders` calls, so the total number of in-flight listing
 * calls never exceeds the cap regardless of the tree's shape, avoiding both the original streams
 * bug and the serialized-latency regression. Since branches can now genuinely run concurrently, the
 * running totals are [AtomicInteger]/[AtomicLong] rather than plain closed-over `var`s (the earlier
 * `Mutex`-guarded accumulator this function once needed, then removed when it went fully
 * sequential, would also have worked here, but a lock-free atomic add is simpler for a plain sum).
 */
suspend fun CloudDriverClient.computeAccountStats(): AccountStats {
    val fileCount = AtomicInteger(0)
    val folderCount = AtomicInteger(0)
    val totalBytes = AtomicLong(0)
    val semaphore = Semaphore(ApiClient.DEFAULT_MAX_CONCURRENT_TRANSFERS)

    suspend fun walk(folderId: String?) {
        val (files, folders) = semaphore.withPermit {
            this.listFiles(folderId) to this.listFolders(folderId)
        }

        fileCount.addAndGet(files.size)
        folderCount.addAndGet(folders.size)
        totalBytes.addAndGet(files.sumOf { it.sizeBytes() })

        coroutineScope {
            folders.map { folder -> async { walk(folder.folderId()) } }.forEach { it.await() }
        }
    }

    walk(null)
    val trashBytes = this.listDeletedFiles().sumOf { it.file().sizeBytes() }
    val sharedFileCount = this.countFilesSharedByMe()
    return AccountStats(fileCount.get(), folderCount.get(), totalBytes.get(), trashBytes, sharedFileCount)
}
