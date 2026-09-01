package de.lino.cloud.platform.desktop

import de.lino.cloud.platform.rest.api.ApiClient
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

    /** Uploads [content] as [fileName], optionally directly into [folderId] (`null` = root). */
    suspend fun uploadFile(fileName: String, content: ByteArray, folderId: String? = null): StoredFileResponse =
        this.apiClient.uploadFileAsync(fileName, content, folderId).await()

    /** Streams [filePath] straight from disk as the uploaded file's content, optionally into [folderId] (`null` = root). */
    suspend fun uploadFile(filePath: Path, folderId: String? = null): StoredFileResponse =
        this.apiClient.uploadFileAsync(filePath.fileName.toString(), filePath, folderId).await()

    /** Every file owned by the caller, scoped to [folderId] (`null` = every file, regardless of folder). */
    suspend fun listFiles(folderId: String? = null): List<StoredFileSummaryResponse> =
        if (folderId == null) this.apiClient.listFilesAsync().await() else this.apiClient.listFilesAsync(folderId).await()

    /** Fetches one file's full content. */
    suspend fun downloadFile(fileId: String): StoredFileResponse =
        this.apiClient.downloadFileAsync(fileId).await()

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

    /** Shuts down the wrapped [ApiClient]'s executor. Idempotent. */
    override fun close() = this.apiClient.close()

}
