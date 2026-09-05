package de.lino.cloud.platform.desktop.model

/**
 * How to order the folders shown in a folder view, or (independently) the files shown in it -
 * `FileBrowserScreen`'s two "Sort" menus each drive one of these separately, so e.g. folders can
 * stay alphabetical while files are sorted by size in the same view. See
 * `AppViewModel.folderSortOption`/`fileSortOption` and `utils/Sorting.kt`'s `sortedFolders`/
 * `sortedFiles`, which actually apply one of these to a list.
 */
enum class SortOption(val label: String) {
    /** Plain, case-insensitive lexicographic order by name (`"file10"` sorts before `"file2"`). */
    ALPHABETICAL("Alphabetical"),

    /** Natural/numeric order by name - embedded digit runs compare as numbers, so `"file2"` sorts before `"file10"`. */
    NUMERIC("Numeric"),

    /** Oldest first, by when the entry was created. */
    CREATED_AT("Created At"),

    /**
     * Smallest first, by byte size. For a file this is its own already-known size; for a folder
     * (which carries no size field of its own - see `Dtos.FolderResponse`'s own Javadoc) this is
     * its full recursive subtree total, computed on demand - see
     * `AppViewModel.computeFolderTotalSize`.
     */
    SIZE("Bytes-Size"),
}
