package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.rest.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runs [action] over every element of this [Iterable] concurrently, capped at [maxConcurrency] in
 * flight at once, and returns every result once all have completed - the Kotlin-side counterpart
 * to [ApiClient]'s own `uploadFilesAsync`/`deleteFilesAsync` concurrency-cap idiom (default cap
 * borrowed directly from [ApiClient.DEFAULT_MAX_CONCURRENT_TRANSFERS]). Reimplemented here rather
 * than delegated to those Java methods because they always target the root - `ApiClient`'s batch
 * upload has no `folderId` parameter - so a folder-aware per-item call (e.g.
 * [CloudDriverClient.uploadFile]/`(fileName, path, folderId)`) needs its own concurrency, not
 * `ApiClient`'s.
 *
 * Every element is attempted, even if one fails - a `supervisorScope` (not a plain
 * `coroutineScope`) so one child's exception doesn't cancel its siblings mid-flight - and once
 * every element has been attempted, the first failure encountered (in iteration order) is
 * rethrown. Matches this codebase's own batch-operation convention (see `EntityDatabaseClient`'s
 * Javadoc: "throws the first failure encountered once every item has been attempted"). A real
 * [CancellationException] (the scope itself being cancelled, e.g. the app closing mid-batch) is
 * rethrown immediately rather than collected - it is not a per-item failure.
 */
suspend fun <T, R> Iterable<T>.mapConcurrently(
    maxConcurrency: Int = ApiClient.DEFAULT_MAX_CONCURRENT_TRANSFERS,
    action: suspend (T) -> R,
): List<R> = supervisorScope {
    val semaphore = Semaphore(maxConcurrency)
    val deferred = this@mapConcurrently.map { item -> async { semaphore.withPermit { action(item) } } }

    var firstFailure: Throwable? = null
    val results = ArrayList<R>(deferred.size)
    for (d in deferred) {
        try {
            results.add(d.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (firstFailure == null) firstFailure = e
        }
    }
    firstFailure?.let { throw it }
    results
}
