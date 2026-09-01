package de.lino.cloud.platform.desktop.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Extracts [zipPath]'s entries into [destinationDirectory] (created if missing), recreating the
 * archive's own internal folder structure on disk - the read-side counterpart to [zipDirectory],
 * backing `AppViewModel.extractArchive`'s "double-click a zip to unarchive it into the current
 * folder" behavior.
 *
 * Reads via [ZipFile] (random-access, resolves entries off the archive's central directory) -
 * **not** `java.util.zip.ZipInputStream`, which was tried first and rejected a real downloaded
 * archive with `ZipException: only DEFLATED entries can have EXT descriptor`: several real-world
 * zip tools set the streaming "data descriptor follows" flag bit on a non-`DEFLATED` (e.g. a
 * `STORED` directory) entry, which `ZipInputStream`'s stricter, sequential reader rejects outright
 * even though the archive is otherwise perfectly valid. [ZipFile] never has to trust that flag -
 * it reads each entry's real size/offset from the central directory at the end of the file - so
 * it isn't affected by this class of malformed-but-common archive. This only works because
 * [zipPath] is always a real local file by the time this is called ([AppViewModel.extractArchive]
 * downloads the archive to a temp file first) - [ZipFile] needs random access, unlike
 * `ZipInputStream`, which could read directly off a network stream.
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
    ZipFile(zipPath.toFile()).use { zipFile ->
        for (entry in zipFile.entries()) {
            val entryName = entry.name.replace('\\', '/')
            val resolved = destinationDirectory.resolve(entryName).normalize()
            if (!resolved.startsWith(destinationDirectory)) {
                throw IOException("Zip entry escapes destination directory: ${entry.name}")
            }
            if (entry.isDirectory) {
                Files.createDirectories(resolved)
            } else {
                Files.createDirectories(resolved.parent)
                zipFile.getInputStream(entry).use { input ->
                    Files.newOutputStream(resolved).use { out -> input.copyTo(out) }
                }
            }
        }
    }
}
