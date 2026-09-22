package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.rest.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Reduces [name] to something safe to pass to [Path.resolve] as a single path component.
 *
 * A file or folder's display name is arbitrary text chosen by whoever uploaded it - and on a
 * shared folder, that is not necessarily the person downloading it. The server accepts almost
 * anything as a name, so a name may legally contain `/` (e.g.
 * `"Gardasil 9 Impfung, Rezept/Rechnung.pdf"`), which `Path#resolve` would otherwise read as a
 * real separator.
 *
 * Every separator and relative segment is neutralised here, not just `/`: on Windows `\` is a
 * real separator too, and `..` as a whole name walks up a directory on every platform - so a
 * shared file named `..\..\Startup\x.exe` would otherwise be written outside the directory the
 * user picked. Windows reserved device names are replaced as well, since a file called `CON` or
 * `NUL` cannot be created there at all.
 *
 * This is one half of the defence; [requireContainedIn] is the other. Every local path built from
 * a remote name must pass through both.
 */
fun sanitizedForLocalPath(name: String): String {
    val withoutSeparators = name.replace('/', '_').replace('\\', '_')
    val trimmed = withoutSeparators.trim().trim('.').trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return "file"
    val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    val stem = trimmed.substringBefore('.').uppercase()
    return if (stem in reserved) "_$trimmed" else trimmed
}

/**
 * Returns [candidate] if it really sits inside [root], and throws otherwise.
 *
 * The structural backstop behind [sanitizedForLocalPath]: a sanitiser can always be incomplete,
 * and this app writes to whatever directory the user chose, so the last check before creating or
 * writing anything is simply whether the resolved path is still under that directory. The same
 * shape the archive-extraction path already uses.
 */
fun requireContainedIn(root: Path, candidate: Path): Path {
    val normalisedRoot = root.toAbsolutePath().normalize()
    val normalisedCandidate = candidate.toAbsolutePath().normalize()
    require(normalisedCandidate.startsWith(normalisedRoot)) {
        "refusing to write outside the chosen download directory: $normalisedCandidate"
    }
    return normalisedCandidate
}

/**
 * The one way to turn a remote name into a local path directly under [root]: [sanitizedForLocalPath]
 * reduces [remoteName] to a single path component, and [requireContainedIn] re-checks the result
 * structurally before anything is created or written.
 *
 * Both halves are needed, which is why they are bound together here rather than left to each call
 * site. A sanitiser can always be incomplete: `:` is a legal character in a file name on macOS and
 * Linux, so it is deliberately kept - but on Windows `"D:payload.exe"` is a drive-relative path, and
 * [Path.resolve] hands such a name back instead of making it a child of [root], so the write would
 * land outside [root] despite the name having been sanitised. The containment check is what catches
 * that, and it costs nothing on the paths where the sanitiser was already enough.
 *
 * @throws IllegalArgumentException if the resulting path would sit outside [root]
 */
fun safeLocalChildOf(root: Path, remoteName: String): Path =
    requireContainedIn(root, root.resolve(sanitizedForLocalPath(remoteName)))

/**
 * Downloads [fileId] (whose current name is [fileName]) straight to disk under
 * [destinationDirectory], preferring the presigned direct-to-client path
 * ([CloudDriverClient.downloadFileViaPresignedUrl], bypassing this app's own server for the data
 * path entirely) and transparently falling back to the
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
        val candidate = safeLocalChildOf(destinationDirectory, fileName)
        if (Files.exists(candidate)) {
            safeLocalChildOf(destinationDirectory, "${UUID.randomUUID()}_${candidate.fileName}")
        } else {
            candidate
        }
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
