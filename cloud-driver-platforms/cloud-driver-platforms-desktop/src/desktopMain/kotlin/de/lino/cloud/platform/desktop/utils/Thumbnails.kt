package de.lino.cloud.platform.desktop.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.desktop.model.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Source-image size ceiling above which [rememberThumbnail] falls back to the generic [iconFor]
 * icon instead of downloading the whole file - there is no thumbnail endpoint server-side, so a
 * real thumbnail always means fetching the file's full content; a 20dp row icon has no business
 * paying for a multi-hundred-MB download to do that.
 */
private const val MAX_THUMBNAIL_SOURCE_BYTES = 20L * 1024 * 1024

/**
 * Longest edge, in pixels, a decoded thumbnail is downscaled to before being cached - generous
 * enough to stay sharp on a HiDPI/Retina display at the ~20dp row-icon size this is actually
 * rendered at, far below a typical decoded source photo's own resolution.
 */
private const val THUMBNAIL_TARGET_SIZE_PX = 96

/**
 * Maximum number of distinct thumbnails [ThumbnailCache] holds before evicting the
 * least-recently-added one - bounds this cache's memory footprint for an account with a very
 * large photo library browsed in one session, rather than retaining every image ever viewed for
 * the lifetime of the process.
 */
private const val MAX_CACHED_THUMBNAILS = 300

/** Whether [entry] is a candidate for a real thumbnail (an image, under [MAX_THUMBNAIL_SOURCE_BYTES]) rather than a generic type icon. */
fun isThumbnailable(entry: Entry): Boolean =
    entry is Entry.FileEntry &&
        entry.summary.contentType()?.lowercase()?.startsWith("image/") == true &&
        entry.sizeBytes <= MAX_THUMBNAIL_SOURCE_BYTES

/**
 * Process-wide, in-memory thumbnail cache keyed by file id - a Compose snapshot-state map, so a
 * row observing it recomposes the moment its own thumbnail finishes loading. [failed] remembers a
 * decode/download failure so a broken image isn't re-fetched on every recomposition/re-visit.
 *
 * **Bounded and downscaled (fixed a real bug, 2026-09-05).** Previously cached the decoded image
 * at full source resolution (up to [MAX_THUMBNAIL_SOURCE_BYTES]) with no eviction at all - a
 * session that scrolled through a few thousand photos accumulated that many full-size decoded
 * bitmaps in heap indefinitely, risking an `OutOfMemoryError`. Every bitmap is now downscaled to
 * [THUMBNAIL_TARGET_SIZE_PX] before being stored (see [downscaleForThumbnail]), and [recordAccess]
 * bounds the cache to [MAX_CACHED_THUMBNAILS] entries via a private [LinkedHashMap]-backed LRU
 * tracker (access-ordered, evicting the least-recently-added key once the cap is exceeded) - a
 * plain, non-Compose-state structure, since it exists purely to decide what to evict, never to
 * drive recomposition itself. [recordAccess] is `@Synchronized` since [rememberThumbnail]'s
 * callers can invoke it concurrently from more than one background `Dispatchers.IO` thread (one
 * per currently-loading row), and a plain [LinkedHashMap] isn't safe under concurrent mutation.
 */
private object ThumbnailCache {
    val bitmaps = mutableStateMapOf<String, ImageBitmap>()
    val failed = mutableStateMapOf<String, Boolean>()

    private val lruOrder = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
            if (size > MAX_CACHED_THUMBNAILS) {
                bitmaps.remove(eldest.key)
                failed.remove(eldest.key)
                return true
            }
            return false
        }
    }

    @Synchronized
    fun recordAccess(fileId: String) {
        this.lruOrder[fileId] = true
    }
}

/**
 * Downscales [source] so its longest edge is at most [maxDimensionPx], preserving aspect ratio -
 * a no-op (returns [source] unchanged) if it's already within bounds. Uses bilinear interpolation
 * (good enough for a small row-icon thumbnail, far cheaper than a higher-quality resampling
 * algorithm) via a fresh [BufferedImage] rather than [java.awt.Image.getScaledInstance], which
 * would only produce another [java.awt.Image] still needing a second draw pass to materialize.
 */
private fun downscaleForThumbnail(source: BufferedImage, maxDimensionPx: Int): BufferedImage {
    if (source.width <= maxDimensionPx && source.height <= maxDimensionPx) return source

    val scale = maxDimensionPx.toDouble() / maxOf(source.width, source.height)
    val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)

    val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return scaled
}

/**
 * Resolves [entry]'s thumbnail if it's [isThumbnailable], triggering a background download+decode
 * on first use and caching the downscaled result process-wide in [ThumbnailCache] - a later
 * recomposition (or re-visiting the same folder) reuses the cached bitmap with no repeat network
 * call. Returns `null` while not yet loaded, not applicable, or on a failed decode - the caller
 * falls back to [iconFor] in that case. Downloads via [CloudDriverClient.downloadFileToPath]
 * (streamed to a throwaway temp file, never buffered as a whole HTTP response) rather than the
 * JSON+base64 route, matching this app's other file transfers.
 */
@Composable
fun rememberThumbnail(entry: Entry, client: CloudDriverClient): ImageBitmap? {
    if (!isThumbnailable(entry)) return null
    val fileId = entry.id

    LaunchedEffect(fileId) {
        if (ThumbnailCache.bitmaps.containsKey(fileId) || ThumbnailCache.failed.containsKey(fileId)) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                val tempDir = Files.createTempDirectory("cloud-driver-thumbnail")
                val tempFile = tempDir.resolve(sanitizedForLocalPath(entry.name))
                try {
                    client.downloadFileToPath(fileId, tempFile)
                    val bufferedImage = ImageIO.read(tempFile.toFile())
                    if (bufferedImage != null) {
                        val thumbnail = downscaleForThumbnail(bufferedImage, THUMBNAIL_TARGET_SIZE_PX)
                        ThumbnailCache.bitmaps[fileId] = thumbnail.toComposeImageBitmap()
                    } else {
                        ThumbnailCache.failed[fileId] = true
                    }
                    ThumbnailCache.recordAccess(fileId)
                } finally {
                    Files.deleteIfExists(tempFile)
                    Files.deleteIfExists(tempDir)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ThumbnailCache.failed[fileId] = true
            ThumbnailCache.recordAccess(fileId)
        }
    }

    return ThumbnailCache.bitmaps[fileId]
}
