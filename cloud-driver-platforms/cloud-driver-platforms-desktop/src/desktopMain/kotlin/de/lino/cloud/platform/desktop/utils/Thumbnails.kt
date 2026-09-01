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
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Source-image size ceiling above which [rememberThumbnail] falls back to the generic [iconFor]
 * icon instead of downloading the whole file - there is no thumbnail endpoint server-side (see
 * `CLAUDE.md`'s "file" package section), so a real thumbnail always means fetching the file's full
 * content; a 20dp row icon has no business paying for a multi-hundred-MB download to do that.
 */
private const val MAX_THUMBNAIL_SOURCE_BYTES = 20L * 1024 * 1024

/** Whether [entry] is a candidate for a real thumbnail (an image, under [MAX_THUMBNAIL_SOURCE_BYTES]) rather than a generic type icon. */
fun isThumbnailable(entry: Entry): Boolean =
    entry is Entry.FileEntry &&
        entry.summary.contentType()?.lowercase()?.startsWith("image/") == true &&
        entry.sizeBytes <= MAX_THUMBNAIL_SOURCE_BYTES

/**
 * Process-wide, in-memory thumbnail cache keyed by file id - a Compose snapshot-state map, so a
 * row observing it recomposes the moment its own thumbnail finishes loading. Never evicted
 * (bounded by however many distinct image files were actually viewed this session), same
 * not-for-huge-scale trade-off [de.lino.cloud.platform.desktop.utils.AppSettingsStore] makes for a
 * different reason. [failed] remembers a decode/download failure so a broken image isn't
 * re-fetched on every recomposition/re-visit.
 */
private object ThumbnailCache {
    val bitmaps = mutableStateMapOf<String, ImageBitmap>()
    val failed = mutableStateMapOf<String, Boolean>()
}

/**
 * Resolves [entry]'s thumbnail if it's [isThumbnailable], triggering a background download+decode
 * on first use and caching the result process-wide in [ThumbnailCache] - a later recomposition (or
 * re-visiting the same folder) reuses the cached bitmap with no repeat network call. Returns `null`
 * while not yet loaded, not applicable, or on a failed decode - the caller falls back to [iconFor]
 * in that case. Downloads via [CloudDriverClient.downloadFileToPath] (streamed to a throwaway temp
 * file, never buffered as a whole HTTP response) rather than the JSON+base64 route, matching this
 * app's other file transfers.
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
                val tempFile = tempDir.resolve(entry.name)
                try {
                    client.downloadFileToPath(fileId, tempFile)
                    val bufferedImage = ImageIO.read(tempFile.toFile())
                    if (bufferedImage != null) {
                        ThumbnailCache.bitmaps[fileId] = bufferedImage.toComposeImageBitmap()
                    } else {
                        ThumbnailCache.failed[fileId] = true
                    }
                } finally {
                    Files.deleteIfExists(tempFile)
                    Files.deleteIfExists(tempDir)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ThumbnailCache.failed[fileId] = true
        }
    }

    return ThumbnailCache.bitmaps[fileId]
}
