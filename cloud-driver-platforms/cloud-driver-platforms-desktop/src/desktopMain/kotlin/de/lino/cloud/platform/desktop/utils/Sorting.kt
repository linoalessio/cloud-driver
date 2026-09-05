package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.desktop.model.SortOption
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse

/**
 * Splits [value] into alternating runs of digits and non-digits - `"file10b2"` ->
 * `["file", "10", "b", "2"]` - so [NATURAL_ORDER_COMPARATOR] can compare each digit run as a
 * number instead of lexicographically, character by character, the way plain string comparison
 * ([SortOption.ALPHABETICAL]) would.
 */
private fun splitIntoChunks(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    var digitsRun = value[0].isDigit()
    for (i in 1 until value.length) {
        val isDigit = value[i].isDigit()
        if (isDigit != digitsRun) {
            chunks += value.substring(start, i)
            start = i
            digitsRun = isDigit
        }
    }
    chunks += value.substring(start)
    return chunks
}

/**
 * Natural/numeric-order string comparator backing [SortOption.NUMERIC] - unlike plain
 * lexicographic ([SortOption.ALPHABETICAL]) comparison, an embedded run of digits compares as a
 * number, so `"file2"` sorts before `"file10"` instead of after it. Falls back to a plain
 * lexicographic comparison of the digit run itself on the (practically unreachable, but not worth
 * crashing over) case of a digit run too large for a [Long].
 */
val NATURAL_ORDER_COMPARATOR: Comparator<String> = Comparator { a, b ->
    val chunksA = splitIntoChunks(a)
    val chunksB = splitIntoChunks(b)
    var i = 0
    var result = 0
    while (i < chunksA.size && i < chunksB.size && result == 0) {
        val chunkA = chunksA[i]
        val chunkB = chunksB[i]
        result = if (chunkA.firstOrNull()?.isDigit() == true && chunkB.firstOrNull()?.isDigit() == true) {
            val numA = chunkA.toLongOrNull()
            val numB = chunkB.toLongOrNull()
            if (numA != null && numB != null) numA.compareTo(numB) else chunkA.compareTo(chunkB)
        } else {
            chunkA.compareTo(chunkB, ignoreCase = true)
        }
        i++
    }
    if (result != 0) result else chunksA.size - chunksB.size
}

/**
 * Orders [folders] per [option]. [SortOption.SIZE] sorts by [folderSizes]' recursive total for
 * each folder (see `AppViewModel.computeFolderTotalSize`) - a folder not yet present there (its
 * total hasn't been computed this session) sorts as `0`, at the front, rather than throwing on a
 * missing entry.
 */
fun sortedFolders(folders: List<FolderResponse>, option: SortOption, folderSizes: Map<String, Long>): List<FolderResponse> =
    when (option) {
        SortOption.ALPHABETICAL -> folders.sortedBy { it.name().lowercase() }
        SortOption.NUMERIC -> folders.sortedWith(compareBy(NATURAL_ORDER_COMPARATOR) { it.name() })
        SortOption.CREATED_AT -> folders.sortedBy { it.createdAtEpochMillis() }
        SortOption.SIZE -> folders.sortedBy { folderSizes[it.folderId()] ?: 0L }
    }

/** Orders [files] per [option]. [SortOption.SIZE] sorts by each file's own already-known [StoredFileSummaryResponse.sizeBytes] - no extra computation needed, unlike a folder's. */
fun sortedFiles(files: List<StoredFileSummaryResponse>, option: SortOption): List<StoredFileSummaryResponse> =
    when (option) {
        SortOption.ALPHABETICAL -> files.sortedBy { it.fileName().lowercase() }
        SortOption.NUMERIC -> files.sortedWith(compareBy(NATURAL_ORDER_COMPARATOR) { it.fileName() })
        SortOption.CREATED_AT -> files.sortedBy { it.createdAtEpochMilli() }
        SortOption.SIZE -> files.sortedBy { it.sizeBytes() }
    }
