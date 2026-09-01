package de.lino.cloud.platform.desktop.model

import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse

/**
 * Unifies a folder and a file summary into one row shape for the file browser's list - both
 * carry a name/created/updated timestamp, but only a file has a byte size (see
 * `FolderResponse`'s own Javadoc in CLAUDE.md: a folder tracks no membership list, so it has
 * nothing to sum a size from).
 */
sealed interface Entry {
    val id: String
    val name: String
    val createdAtEpochMilli: Long
    val updatedAtEpochMilli: Long
    val sizeBytes: Long?

    data class FileEntry(val summary: StoredFileSummaryResponse) : Entry {
        override val id: String get() = summary.fileId()
        override val name: String get() = summary.fileName()
        override val createdAtEpochMilli: Long get() = summary.createdAtEpochMilli()
        override val updatedAtEpochMilli: Long get() = summary.updatedAtEpochMilli()
        override val sizeBytes: Long get() = summary.sizeBytes()
    }

    data class FolderEntry(val folder: FolderResponse) : Entry {
        override val id: String get() = folder.folderId()
        override val name: String get() = folder.name()
        override val createdAtEpochMilli: Long get() = folder.createdAtEpochMillis()
        override val updatedAtEpochMilli: Long get() = folder.modifiedAtEpochMillis()
        override val sizeBytes: Long? get() = null
    }
}
