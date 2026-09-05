package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.desktop.model.Entry
import de.lino.cloud.platform.desktop.theme.CloudColors
import de.lino.cloud.platform.desktop.theme.FolderColorOption
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** A folder icon, or a file icon chosen by [Entry.FileEntry.summary]'s content type - `File`/`Folder` icons for everything would read as generic, not "adapted to the Cloud system theme." */
fun iconFor(entry: Entry): ImageVector = when (entry) {
    is Entry.FolderEntry -> Icons.Filled.Folder
    is Entry.FileEntry -> iconForContentType(entry.summary.contentType())
}

/**
 * The [CloudColors] tint [iconFor]'s glyph renders in for a given row - a folder and every file
 * category gets its own distinct color (mirroring the real macOS iCloud app's own colorful,
 * per-service icon tiles) instead of every row sharing one flat monochrome tint. Kept as a
 * separate function from [iconFor] rather than folded into one "icon + color" pair, since some
 * callers (e.g. [de.lino.cloud.platform.desktop.theme.IconTile] elsewhere in this app) only ever
 * need a plain [ImageVector]. A folder's own tint comes from its individually-set
 * [de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse.color] (via [FolderColorOption.forName],
 * defaulting to blue) rather than always [CloudColors.Blue].
 */
fun colorFor(entry: Entry): Color = when (entry) {
    is Entry.FolderEntry -> FolderColorOption.forName(entry.folder.color()).color
    is Entry.FileEntry -> colorForContentType(entry.summary.contentType())
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

private fun colorForContentType(contentType: String?): Color {
    val type = contentType?.lowercase() ?: return CloudColors.Gray
    return when {
        type.startsWith("image/") -> CloudColors.Green
        type.startsWith("video/") -> CloudColors.Pink
        type.startsWith("audio/") -> CloudColors.Purple
        type == "application/pdf" -> CloudColors.Red
        type in ARCHIVE_CONTENT_TYPES -> CloudColors.Orange
        type.startsWith("text/") || type == "application/json" || type == "application/xml" -> CloudColors.Blue
        else -> CloudColors.Gray
    }
}
