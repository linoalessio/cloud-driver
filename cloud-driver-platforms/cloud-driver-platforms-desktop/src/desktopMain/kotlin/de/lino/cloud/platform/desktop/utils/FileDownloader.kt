package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.desktop.client.CloudDriverClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Downloads [fileId] (whose current name is [fileName]) straight to disk under
 * [destinationDirectory], via [CloudDriverClient.downloadFileToPath] - the server streams
 * decrypted, decompressed bytes directly to the response body (see `DefaultRestFactory`'s `GET
 * /files/{id}/content`) and [ApiClient.downloadFileToPathAsync][de.lino.cloud.platform.rest.api.ApiClient.downloadFileToPathAsync]
 * writes them straight to the destination file as they arrive - content never exists as a base64
 * string, a decoded [ByteArray], or any other full-size in-memory copy in this process at all,
 * unlike the older `StoredFileResponse`-based path this replaces (base64-decode +
 * DEFLATE-inflate into a `ByteArrayOutputStream`, then one more `Files.write`).
 *
 * Resolves the target path the same collision-avoidance way `StoredFile#downloadToDevice` does
 * server-side: if `destinationDirectory/fileName` already exists, the file is written under a
 * fresh `<uuid>_fileName` instead, rather than overwriting - required here specifically because
 * [BodyHandlers.ofFile][java.net.http.HttpResponse.BodyHandlers.ofFile] (what the underlying
 * `ApiClient` call is built on) refuses to write to a path that already exists.
 *
 * `suspend`; the blocking directory-creation/existence-check runs on [Dispatchers.IO], the same
 * dispatcher every other blocking local-filesystem call in this app uses.
 *
 * [onBytesTransferred], if given, is invoked with the cumulative number of bytes written so far -
 * see [CloudDriverClient.downloadFileToPath]'s own Javadoc on which thread this runs on.
 *
 * @return the path the file was actually written to (may differ from `destinationDirectory/fileName` - see above)
 */
suspend fun CloudDriverClient.downloadFileStreaming(
    fileId: String,
    fileName: String,
    destinationDirectory: Path,
    onBytesTransferred: (Long) -> Unit = {},
): Path {
    val target = withContext(Dispatchers.IO) {
        Files.createDirectories(destinationDirectory)
        val candidate = destinationDirectory.resolve(fileName)
        if (Files.exists(candidate)) destinationDirectory.resolve("${UUID.randomUUID()}_$fileName") else candidate
    }
    return this.downloadFileToPath(fileId, target, onBytesTransferred)
}
