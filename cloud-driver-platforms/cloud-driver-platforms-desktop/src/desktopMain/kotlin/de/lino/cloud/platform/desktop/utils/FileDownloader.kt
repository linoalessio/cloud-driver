package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.rest.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Downloads [fileId] (whose current name is [fileName]) straight to disk under
 * [destinationDirectory], preferring the presigned direct-to-client path
 * ([CloudDriverClient.downloadFileViaPresignedUrl], bypassing this app's own server for the data
 * path entirely - see `architecture/AWS_S3_IMPL.md`) and transparently falling back to the
 * ordinary server-mediated [CloudDriverClient.downloadFileToPath] the moment the server reports
 * (`503`) presigned transfer isn't available for this file/deployment - so this works unchanged
 * against an older, non-S3-configured, or app-encrypted-content deployment too. Either way, the
 * server streams decrypted, decompressed bytes directly to the response body (see
 * `DefaultRestFactory`'s `GET /files/{id}/content`) and
 * [ApiClient.downloadFileToPathAsync][de.lino.cloud.platform.rest.api.ApiClient.downloadFileToPathAsync]
 * writes them straight to the destination file as they arrive - content never exists as a base64
 * string, a decoded [ByteArray], or any other full-size in-memory copy in this process at all,
 * unlike the older `StoredFileResponse`-based path this replaces (base64-decode +
 * DEFLATE-inflate into a `ByteArrayOutputStream`, then one more `Files.write`).
 *
 * Resolves the target path the same collision-avoidance way `StoredFile#downloadToDevice` does
 * server-side: if `destinationDirectory/fileName` already exists, the file is written under a
 * fresh `<uuid>_fileName` instead, rather than overwriting - required here specifically because
 * [BodyHandlers.ofFile][java.net.http.HttpResponse.BodyHandlers.ofFile] (what both the presigned
 * and server-mediated `ApiClient` calls are built on) refuses to write to a path that already exists.
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
    return try {
        this.downloadFileViaPresignedUrl(fileId, target, onBytesTransferred)
    } catch (e: ApiClient.ApiException) {
        if (e.statusCode() == 503) {
            this.downloadFileToPath(fileId, target, onBytesTransferred)
        } else {
            throw e
        }
    }
}
