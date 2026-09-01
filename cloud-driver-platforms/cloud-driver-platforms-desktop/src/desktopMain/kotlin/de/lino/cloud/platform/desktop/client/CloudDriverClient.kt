package de.lino.cloud.platform.desktop.client

import de.lino.cloud.platform.rest.api.ApiClient
import de.lino.cloud.platform.rest.api.dto.Dtos.CloudUserResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.MessageResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse
import kotlinx.coroutines.future.await
import java.nio.file.Path

/**
 * A coroutine-friendly Kotlin facade over [ApiClient] - every network call here is a `suspend`
 * function built directly on one of [ApiClient]'s own `*Async` methods via
 * `CompletableFuture<T>.await()`, rather than re-implementing HTTP handling. [ApiClient] already
 * does the real work (connection pooling, HTTP/2 multiplexing, response parsing); this class only
 * adapts its `CompletableFuture`-based async surface to structured concurrency.
 *
 * A [ApiClient.ApiException] thrown by the wrapped async call surfaces here unwrapped - `await()`
 * rethrows the `CompletionException`'s cause rather than the wrapper itself.
 *
 * Not thread-safe, for the same reason [ApiClient] itself isn't: one instance models one logged-in
 * session. Call [close] (or use it in a `use { }` block) once done, to shut down the underlying
 * [ApiClient]'s executor.
 */
class CloudDriverClient(
    authPanelBaseUrl: String,
    apiBaseUrl: String,
) : AutoCloseable {

    /** The wrapped client, exposed for blocking/[java.util.concurrent.CompletableFuture] callers that don't want coroutines. */
    val apiClient: ApiClient = ApiClient(authPanelBaseUrl, apiBaseUrl)

    val isAuthenticated: Boolean
        get() = this.apiClient.isAuthenticated

    /** Restores a previously persisted token (e.g. loaded from the OS keychain) without a fresh login. */
    fun restoreSession(previouslyIssuedToken: String) = this.apiClient.restoreSession(previouslyIssuedToken)

    /** Discards the in-memory token; the caller is responsible for also clearing any persisted copy. */
    fun logout() = this.apiClient.logout()

    /** @return the freshly issued JWT, already stored on [apiClient] for subsequent calls */
    suspend fun login(emailAddress: String, password: String): String =
        this.apiClient.loginAsync(emailAddress, password).await()

    /** Step one of registration - e-mails a verification code, does not yet create the account. */
    suspend fun register(emailAddress: String, password: String): MessageResponse =
        this.apiClient.registerAsync(emailAddress, password).await()

    /** Step two of registration - submits the e-mailed code, creates the account, returns a fresh JWT. */
    suspend fun confirmRegistration(emailAddress: String, code: String): String =
        this.apiClient.confirmRegistrationAsync(emailAddress, code).await()

    /**
     * Step one of a password reset - if (and only if) an account exists under [emailAddress],
     * e-mails a 6-digit verification code. Responds identically either way; the server never
     * reveals through this call whether an account exists.
     */
    suspend fun requestPasswordReset(emailAddress: String): MessageResponse =
        this.apiClient.requestPasswordResetAsync(emailAddress).await()

    /** Step two of a password reset - submits the e-mailed code and [newPassword], returns a fresh JWT. */
    suspend fun confirmPasswordReset(emailAddress: String, code: String, newPassword: String): String =
        this.apiClient.confirmPasswordResetAsync(emailAddress, code, newPassword).await()

    /**
     * Uploads [content] as [fileName], optionally directly into [folderId] (`null` = root).
     * Returns a [StoredFileSummaryResponse] - not the uploaded content echoed back, since the
     * caller already has the bytes it just sent (see [ApiClient.uploadFile]'s own Javadoc).
     */
    suspend fun uploadFile(fileName: String, content: ByteArray, folderId: String? = null): StoredFileSummaryResponse =
        this.apiClient.uploadFileAsync(fileName, content, folderId).await()

    /**
     * Streams [filePath] straight from disk as the uploaded file's content, optionally into
     * [folderId] (`null` = root). [onBytesTransferred], if given, is invoked with the cumulative
     * number of bytes sent so far as the upload progresses - see [ApiClient.uploadFileAsync]'s own
     * Javadoc on which thread this runs on (not this coroutine's own dispatcher).
     */
    suspend fun uploadFile(filePath: Path, folderId: String? = null, onBytesTransferred: (Long) -> Unit = {}): StoredFileSummaryResponse =
        this.apiClient.uploadFileAsync(filePath.fileName.toString(), filePath, folderId, onBytesTransferred).await()

    /** Same as [uploadFile] on a [Path], with an explicit [fileName] instead of the path's own file name. */
    suspend fun uploadFile(
        fileName: String,
        filePath: Path,
        folderId: String? = null,
        onBytesTransferred: (Long) -> Unit = {},
    ): StoredFileSummaryResponse = this.apiClient.uploadFileAsync(fileName, filePath, folderId, onBytesTransferred).await()

    /**
     * Every file directly inside [folderId] (`null` = the root's own files, **not** every file
     * regardless of folder). Always calls [ApiClient]'s one-arg `listFilesAsync(String)` overload
     * - which maps a `null` argument to `?folderId=root` server-side - and deliberately never the
     * no-arg `listFilesAsync()` overload, which lists every owned file flat, ignoring folder
     * placement entirely; calling that one for a `null` [folderId] here previously made every
     * file appear at the root in addition to its real folder, since "root" and "everything"
     * are two different server-side scopes even though both start from a `null` Kotlin value.
     */
    suspend fun listFiles(folderId: String? = null): List<StoredFileSummaryResponse> =
        this.apiClient.listFilesAsync(folderId).await()

    /** Fetches one file's full content. */
    suspend fun downloadFile(fileId: String): StoredFileResponse =
        this.apiClient.downloadFileAsync(fileId).await()

    /**
     * Streams a file's content directly to [destination] on disk - no base64 decode, content
     * never fully materializes as a Kotlin [ByteArray] in this process. [destination] must not
     * already exist (see [ApiClient.downloadFileToPath]'s own Javadoc). [onBytesTransferred], if
     * given, is invoked with the cumulative number of bytes written so far as the download
     * progresses - see [ApiClient.downloadFileToPathAsync]'s own Javadoc on which thread this runs on.
     */
    suspend fun downloadFileToPath(fileId: String, destination: Path, onBytesTransferred: (Long) -> Unit = {}): Path =
        this.apiClient.downloadFileToPathAsync(fileId, destination, onBytesTransferred).await()

    suspend fun deleteFile(fileId: String) {
        this.apiClient.deleteFileAsync(fileId).await()
    }

    /** Moves [fileId] into [folderId] (`null` = back to the root). */
    suspend fun moveFile(fileId: String, folderId: String?) {
        this.apiClient.moveFileAsync(fileId, folderId).await()
    }

    /** Creates a new folder, optionally nested under [parentFolderId] (`null` = top-level). */
    suspend fun createFolder(name: String, parentFolderId: String? = null): FolderResponse =
        this.apiClient.createFolderAsync(name, parentFolderId).await()

    /** Lists the caller's own folders directly inside [parentFolderId] (`null` = top-level). */
    suspend fun listFolders(parentFolderId: String? = null): List<FolderResponse> =
        this.apiClient.listFoldersAsync(parentFolderId).await()

    /** Renames and/or moves a folder in one step (`newParentFolderId` `null` = top level). */
    suspend fun updateFolder(folderId: String, newName: String, newParentFolderId: String?): FolderResponse =
        this.apiClient.updateFolderAsync(folderId, newName, newParentFolderId).await()

    suspend fun deleteFolder(folderId: String) {
        this.apiClient.deleteFolderAsync(folderId).await()
    }

    /** The caller's own [CloudUserResponse] - its `timeStamp` is the account's creation time (see that DTO's own Javadoc). */
    suspend fun getCloudUser(authUserId: String): CloudUserResponse =
        this.apiClient.getCloudUserAsync(authUserId).await()

    /** Shuts down the wrapped [ApiClient]'s executor. Idempotent. */
    override fun close() = this.apiClient.close()

}
