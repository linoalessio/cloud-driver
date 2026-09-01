package de.lino.cloud.platform.desktop.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory

/**
 * Zips [sourceDirectory]'s contents (recursively) into a fresh temp file, for uploading a whole
 * local folder as one file - the server has no folder-tree upload endpoint, only single-file
 * `POST /files` (see CLAUDE.md's "`RestFactory`" section), so a client-side zip is how "upload a
 * folder" is represented on the wire, per this app's spec. The caller is responsible for deleting
 * the returned temp file once the upload completes.
 *
 * `suspend`, dispatched on [Dispatchers.IO] - walking, reading, and writing an entire local
 * directory tree is blocking filesystem I/O (with some DEFLATE compression alongside it, but the
 * I/O dominates for anything but a tiny folder), so it belongs on the IO dispatcher's large
 * thread pool rather than whichever dispatcher the caller happens to be running on - in
 * particular, never the single-threaded Compose UI dispatcher `rememberCoroutineScope()` defaults
 * to, where zipping a large folder would otherwise freeze the window for the duration.
 */
suspend fun zipDirectory(sourceDirectory: Path): Path = withContext(Dispatchers.IO) {
    val zipPath = Files.createTempFile(sourceDirectory.fileName.toString(), ".zip")
    ZipOutputStream(BufferedOutputStream(Files.newOutputStream(zipPath))).use { zos ->
        Files.walk(sourceDirectory).use { paths ->
            paths.filter { !it.isDirectory() }.forEach { file ->
                val entryName = sourceDirectory.relativize(file).toString().replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                Files.copy(file, zos)
                zos.closeEntry()
            }
        }
    }
    zipPath
}
