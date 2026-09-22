package de.lino.cloud.platform.desktop.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * One cached copy of a file's plaintext content, as [ContentCache] hands it back.
 *
 * @property entityTag the server's `ETag` for exactly these bytes, or `null` when the server
 *     recorded none - such an entry can never be revalidated, so the caller must download again
 *     rather than guess
 * @property path the local file holding the plaintext; valid only as long as [ContentCache] keeps it
 * @property sizeBytes the size the entry was stored with, re-checked on every lookup
 */
data class CachedContent(val entityTag: String?, val path: Path, val sizeBytes: Long)

/**
 * A process-lifetime, per-account, on-disk cache of already-downloaded plaintext content, used by
 * the preview path so reopening the same file costs one conditional metadata round trip instead of
 * a whole download.
 *
 * Every rule here exists because the cached bytes are plaintext:
 *
 * - **Keyed by account id plus file id, never by name.** A file id is unique; a name is not, and
 *   two accounts signed in on one machine must never be able to read each other's cached content.
 * - **Backed by one temp directory created on first use and deleted on JVM shutdown.** Cached
 *   plaintext must not outlive the process that fetched it. Where the filesystem supports POSIX
 *   permissions, the directory and every file in it are owner-only.
 * - **[lookup] revalidates structurally.** An entry whose file has since been removed, or whose
 *   size no longer matches what was recorded, is dropped rather than handed back - a truncated or
 *   externally replaced cache file must never be served as content.
 * - **Bounded.** At most [MAX_ENTRIES] entries and [MAX_TOTAL_BYTES] in total, least-recently-used
 *   evicted first (and its file deleted), so a long browsing session cannot fill the disk.
 *
 * [clear] drops everything and is called on sign-out and uninstall: cached plaintext belongs to the
 * account that fetched it.
 */
object ContentCache {

    /** Hard cap on how many entries are retained before the least recently used one is evicted. */
    private const val MAX_ENTRIES = 25

    /** Hard cap on the cache's total on-disk size, in bytes, before eviction starts. */
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    /** Access-ordered so the first entry is always the least recently used one - see [store]'s eviction. */
    private val entries = LinkedHashMap<String, CachedContent>(16, 0.75f, true)

    /** The one temp directory every cached file lives in; created on first use, removed at shutdown. */
    private var cacheDirectory: Path? = null

    /**
     * Returns the cached plaintext for [fileId] under [accountId], or `null` if there is none
     * usable. A returned entry's [CachedContent.entityTag] may still be `null`, which means it
     * cannot be revalidated - download again instead.
     */
    @Synchronized
    fun lookup(accountId: String?, fileId: String): CachedContent? {
        val entry = this.entries[key(accountId, fileId)] ?: return null
        val stillValid = runCatching { Files.exists(entry.path) && Files.size(entry.path) == entry.sizeBytes }
            .getOrDefault(false)
        if (!stillValid) {
            this.entries.remove(key(accountId, fileId))
            runCatching { Files.deleteIfExists(entry.path) }
            return null
        }
        return entry
    }

    /**
     * Copies [source] into the cache as [fileId]'s content under [accountId] and returns the cached
     * file's own path - the caller reads from that path, not from [source], which it remains free
     * to delete.
     *
     * A failure to cache is never fatal: [source] is returned unchanged so the caller still has the
     * content it just downloaded.
     */
    @Synchronized
    fun store(accountId: String?, fileId: String, source: Path, entityTag: String?): Path {
        return runCatching {
            val directory = this.directory()
            val cached = directory.resolve("${java.util.UUID.randomUUID()}.bin")
            Files.copy(source, cached)
            restrictToOwner(cached)
            val previous = this.entries.put(key(accountId, fileId), CachedContent(entityTag, cached, Files.size(cached)))
            if (previous != null) {
                runCatching { Files.deleteIfExists(previous.path) }
            }
            this.evictDownToBounds()
            cached
        }.getOrDefault(source)
    }

    /** Deletes every cached file and forgets every entry - call on sign-out and uninstall. */
    @Synchronized
    fun clear() {
        this.entries.values.forEach { entry -> runCatching { Files.deleteIfExists(entry.path) } }
        this.entries.clear()
    }

    /** Drops least-recently-used entries until both the count and the total-size bounds hold again. */
    private fun evictDownToBounds() {
        var totalBytes = this.entries.values.sumOf { it.sizeBytes }
        val iterator = this.entries.entries.iterator()
        while (iterator.hasNext() && (this.entries.size > MAX_ENTRIES || totalBytes > MAX_TOTAL_BYTES)) {
            val evicted = iterator.next().value
            iterator.remove()
            totalBytes -= evicted.sizeBytes
            runCatching { Files.deleteIfExists(evicted.path) }
        }
    }

    /** The cache directory, created (owner-only, with a shutdown hook to remove it) on first use. */
    private fun directory(): Path {
        val existing = this.cacheDirectory
        if (existing != null && Files.isDirectory(existing)) {
            return existing
        }
        val created = Files.createTempDirectory("cloud-driver-content-cache")
        restrictToOwner(created)
        this.cacheDirectory = created
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { created.toFile().deleteRecursively() }
            },
        )
        return created
    }

    /** The cache key - account id and file id together, never a name. */
    private fun key(accountId: String?, fileId: String): String = "${accountId ?: "anonymous"}:$fileId"

    /**
     * Narrows [path] to owner-only where the filesystem supports POSIX permissions, and silently
     * does nothing where it doesn't (Windows) - a best-effort narrowing is still worth making on
     * the platforms that have it, since what is being written is another person's plaintext.
     */
    private fun restrictToOwner(path: Path) {
        runCatching {
            val ownerOnly = if (Files.isDirectory(path)) {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            Files.setPosixFilePermissions(path, ownerOnly)
        }
    }
}
