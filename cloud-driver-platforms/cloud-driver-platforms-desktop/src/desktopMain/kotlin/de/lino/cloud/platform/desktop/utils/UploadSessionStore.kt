package de.lino.cloud.platform.desktop.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * One in-flight resumable upload, as [UploadSessionStore] persists it.
 *
 * Holds nothing but what is needed to ask the server for the session's own state again: no content,
 * no key material, no ticket. The real check that the local file still matches the session is the
 * server's own digest gate at resume time, not anything recorded here.
 *
 * @property sessionFileId the session's id, as returned when it was begun
 * @property accountId the account that begun it - a record is never offered to a different account
 * @property localPath the absolute path of the file being uploaded
 * @property fileName the name the file is being uploaded under
 * @property folderId the destination folder, or `null` for the root
 * @property sizeBytes the local file's size when the session was begun
 * @property checksumSha256 the plaintext digest the session was begun with
 * @property startedAtEpochMillis when the session was begun, for the age cutoff on load
 */
data class UploadSessionRecord(
    val sessionFileId: String,
    val accountId: String,
    val localPath: String,
    val fileName: String,
    val folderId: String?,
    val sizeBytes: Long,
    val checksumSha256: String,
    val startedAtEpochMillis: Long,
)

/**
 * Crash-resume records for large uploads, persisted as JSON at
 * `~/.cloud-driver-desktop/upload-sessions.json` - the directory [uninstallApp] already removes.
 *
 * A session id is only useful if it survives the crash it exists to recover from, so a record is
 * written *before* the first part is uploaded and forgotten on success or on a deliberate abort.
 *
 * Rules, each for a reason:
 *
 * - **No content and no key material is ever written here.** A record names a server-side session;
 *   everything secret about that session stays server-side and is re-fetched on resume.
 * - **The file is written atomically** (temp file plus [StandardCopyOption.ATOMIC_MOVE]) and
 *   narrowed to owner-only where the filesystem supports POSIX permissions, so a crash mid-write
 *   can never leave a half-parsed file behind and another account on the machine cannot read it.
 * - **A record older than [MAX_RECORD_AGE_MILLIS] is dropped on load.** The server expires sessions
 *   on its own schedule; holding a record past that only produces a confusing failed resume.
 * - **[find] matches on account, absolute path and size.** That is enough to offer a resume; the
 *   authoritative check is the server's digest gate, which refuses a file that has really changed.
 */
object UploadSessionStore {

    /** Records older than this are dropped when the store is read - a week is well past any server-side session lifetime. */
    private const val MAX_RECORD_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Shared JSON codec - the same Gson the rest of this app's local persistence uses. */
    private val GSON = Gson()

    /** Type token for the on-disk shape: a flat list of records. */
    private val RECORD_LIST_TYPE = object : TypeToken<MutableList<UploadSessionRecord>>() {}.type

    /** Remembers [record], replacing any earlier record for the same session id. */
    @Synchronized
    fun record(record: UploadSessionRecord) {
        val records = this.load().filterNot { it.sessionFileId == record.sessionFileId }.toMutableList()
        records += record
        this.save(records)
    }

    /**
     * The most recently begun record for [accountId]'s upload of [localPath] at [sizeBytes], or
     * `null` if there is none.
     */
    @Synchronized
    fun find(accountId: String, localPath: Path, sizeBytes: Long): UploadSessionRecord? {
        val absolute = localPath.toAbsolutePath().normalize().toString()
        return this.load()
            .filter { it.accountId == accountId && it.localPath == absolute && it.sizeBytes == sizeBytes }
            .maxByOrNull { it.startedAtEpochMillis }
    }

    /** Forgets [sessionFileId] - call on a completed upload and on a deliberate abort. */
    @Synchronized
    fun forget(sessionFileId: String) {
        val records = this.load()
        val remaining = records.filterNot { it.sessionFileId == sessionFileId }
        if (remaining.size != records.size) {
            this.save(remaining)
        }
    }

    /** Drops every record - call on sign-out and uninstall, like the content cache. */
    @Synchronized
    fun clear() {
        runCatching { Files.deleteIfExists(this.storeFile()) }
    }

    /** Reads the store, silently returning an empty list for an absent or unreadable file, and dropping records past their age cutoff. */
    private fun load(): List<UploadSessionRecord> = runCatching {
        val file = this.storeFile()
        if (!Files.exists(file)) {
            return@runCatching emptyList<UploadSessionRecord>()
        }
        val parsed: MutableList<UploadSessionRecord> =
            GSON.fromJson(Files.readString(file), RECORD_LIST_TYPE) ?: mutableListOf()
        val cutoff = System.currentTimeMillis() - MAX_RECORD_AGE_MILLIS
        parsed.filter { it.startedAtEpochMillis >= cutoff }
    }.getOrDefault(emptyList())

    /** Writes [records] atomically, so a crash mid-write can never leave a half-parsed store behind. */
    private fun save(records: List<UploadSessionRecord>) {
        runCatching {
            val file = this.storeFile()
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "upload-sessions", ".json")
            Files.writeString(temporary, GSON.toJson(records))
            restrictToOwner(temporary)
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** `~/.cloud-driver-desktop/upload-sessions.json`. */
    private fun storeFile(): Path =
        Paths.get(System.getProperty("user.home"), ".cloud-driver-desktop", "upload-sessions.json")

    /** Owner-only where the filesystem supports POSIX permissions; a no-op where it doesn't. */
    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
