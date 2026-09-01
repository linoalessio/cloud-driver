package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.desktop.model.Entry
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector

/** A folder icon, or a file icon chosen by [Entry.FileEntry.summary]'s content type - `File`/`Folder` icons for everything would read as generic, not "adapted to the Cloud system theme." */
fun iconFor(entry: Entry): ImageVector = when (entry) {
    is Entry.FolderEntry -> Icons.Filled.Folder
    is Entry.FileEntry -> iconForContentType(entry.summary.contentType())
}

private val ARCHIVE_CONTENT_TYPES = setOf(
    "application/zip", "application/x-7z-compressed", "application/x-tar",
    "application/gzip", "application/vnd.rar", "application/x-bzip2", "application/x-xz",
)

private fun iconForContentType(contentType: String?): ImageVector {
    val type = contentType?.lowercase() ?: return Icons.AutoMirrored.Filled.InsertDriveFile
    return when {
        type.startsWith("image/") -> Icons.Filled.Image
        type.startsWith("video/") -> Icons.Filled.Movie
        type.startsWith("audio/") -> Icons.Filled.AudioFile
        type == "application/pdf" -> Icons.Filled.PictureAsPdf
        type in ARCHIVE_CONTENT_TYPES -> Icons.Filled.FolderZip
        type.startsWith("text/") || type == "application/json" || type == "application/xml" -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
