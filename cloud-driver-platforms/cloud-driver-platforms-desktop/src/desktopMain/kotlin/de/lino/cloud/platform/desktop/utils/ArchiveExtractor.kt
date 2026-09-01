package de.lino.cloud.platform.desktop.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Extracts [zipPath]'s entries into [destinationDirectory] (created if missing), recreating the
 * archive's own internal folder structure on disk - the read-side counterpart to [zipDirectory],
 * backing `AppViewModel.extractArchive`'s "double-click a zip to unarchive it into the current
 * folder" behavior.
 *
 * Rejects ("zip slip" protection) any entry whose resolved path would land outside
 * [destinationDirectory] rather than writing it - an entry name inside a zip is untrusted input
 * (the archive being extracted may have come from anywhere), and a `../`-prefixed entry name is a
 * well-known way to escape the intended extraction directory. Entry names are normalized from
 * `\` to `/` first (some Windows-authored zips use backslashes), mirroring [zipDirectory]'s own
 * entry-name normalization on the write side.
 *
 * `suspend`, dispatched on [Dispatchers.IO] - matches [zipDirectory]'s own reasoning: blocking
 * filesystem I/O plus DEFLATE decompression has no business running on the calling coroutine's
 * own dispatcher, in particular never the single-threaded Compose UI dispatcher.
 *
 * @throws IOException if the zip is malformed, or an entry's path would escape [destinationDirectory]
 */
suspend fun extractZip(zipPath: Path, destinationDirectory: Path): Unit = withContext(Dispatchers.IO) {
    Files.createDirectories(destinationDirectory)
    ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val entryName = entry.name.replace('\\', '/')
            val resolved = destinationDirectory.resolve(entryName).normalize()
            if (!resolved.startsWith(destinationDirectory)) {
                throw IOException("Zip entry escapes destination directory: ${entry.name}")
            }
            if (entry.isDirectory) {
                Files.createDirectories(resolved)
            } else {
                Files.createDirectories(resolved.parent)
                Files.newOutputStream(resolved).use { out -> zis.copyTo(out) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}
