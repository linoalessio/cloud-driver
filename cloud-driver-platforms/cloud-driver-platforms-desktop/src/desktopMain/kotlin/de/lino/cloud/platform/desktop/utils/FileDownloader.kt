package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.Inflater

/**
 * Reconstructs a [StoredFileResponse]'s original plaintext bytes: base64-decodes
 * [StoredFileResponse.contentBase64], then - only if [StoredFileResponse.contentCompressed] -
 * DEFLATE-inflates it. Mirrors `StoredFile`'s own server-side compress-then-encrypt scheme
 * (DEFLATE, not gzip - see CLAUDE.md's "file package" section); by the time this DTO reaches the
 * client the server has already decrypted the payload, so only the (optional) compression step
 * remains to be undone here. This module has no dependency on `StoredFile` itself (see
 * `ByteFormat.kt`'s own Javadoc on the "client never depends on cloud-driver-api" boundary), so
 * this is a client-side reimplementation of exactly the inverse of `StoredFile`'s constructor,
 * not a call into it.
 *
 * `suspend`, dispatched on [Dispatchers.Default] - base64-decoding and DEFLATE-inflating a large
 * (this codebase's own docs mention uploads in the tens/hundreds of MB) file is real CPU work
 * with no blocking I/O involved, so it belongs on the CPU-bound dispatcher, not whichever
 * dispatcher the caller happens to be running on (in particular, never the single-threaded
 * Compose UI dispatcher `rememberCoroutineScope()` defaults to - running this there would freeze
 * the window for the duration).
 */
suspend fun StoredFileResponse.decodedContent(): ByteArray {
    val response = this
    return withContext(Dispatchers.Default) {
        val raw = Base64.getDecoder().decode(response.contentBase64())
        if (!response.contentCompressed()) return@withContext raw

        val inflater = Inflater()
        inflater.setInput(raw)
        val out = ByteArrayOutputStream(raw.size * 2)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            out.write(buffer, 0, count)
        }
        inflater.end()
        out.toByteArray()
    }
}

/**
 * Writes this file's [decodedContent] under `destinationDirectory/`[StoredFileResponse.fileName],
 * creating the directory if needed. `suspend`, dispatched on [Dispatchers.IO] - directory
 * creation and the final write are blocking filesystem I/O, so they belong on the IO dispatcher's
 * large thread pool, not the caller's own dispatcher (same reasoning as [decodedContent]'s own
 * Javadoc).
 */
suspend fun StoredFileResponse.downloadTo(destinationDirectory: Path): Path {
    val response = this
    val content = response.decodedContent()
    return withContext(Dispatchers.IO) {
        Files.createDirectories(destinationDirectory)
        val target = destinationDirectory.resolve(response.fileName())
        Files.write(target, content)
        target
    }
}
