import Foundation

/// How to order the folders shown in a folder view, or (independently) the files shown in it -
/// `FileBrowserView`'s "Sort" toolbar menu drives one of each via `AppViewModel.folderSortOption`/
/// `fileSortOption`. The mobile counterpart to cloud-driver-platforms-desktop's own `SortOption`.
enum SortOption: String, CaseIterable, Identifiable {
    case alphabetical
    case numeric
    case createdAt
    case size

    var id: String { rawValue }

    var label: String {
        switch self {
        case .alphabetical: return "Alphabetical"
        case .numeric: return "Numeric"
        case .createdAt: return "Created At"
        case .size: return "Bytes-Size"
        }
    }
}

/// Orders `folders` per `option`. `.size` sorts by `sizes`' recursive total for each folder (see
/// `AppViewModel.computeFolderTotalSize`) - a folder not yet present there (its total hasn't been
/// computed this session) sorts as `0`, at the front, rather than crashing on a missing entry.
func sortedFolders(_ folders: [FolderResponse], by option: SortOption, sizes: [String: Int64]) -> [FolderResponse] {
    switch option {
    case .alphabetical:
        return folders.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    case .numeric:
        // `.numeric` compares embedded digit runs as numbers ("file2" before "file10") instead of
        // lexicographically - Foundation's built-in natural-order string comparison.
        return folders.sorted { $0.name.compare($1.name, options: [.numeric, .caseInsensitive]) == .orderedAscending }
    case .createdAt:
        return folders.sorted { $0.createdAtEpochMillis < $1.createdAtEpochMillis }
    case .size:
        return folders.sorted { (sizes[$0.folderId] ?? 0) < (sizes[$1.folderId] ?? 0) }
    }
}

/// Orders `files` per `option`. `.size` sorts by each file's own already-known `sizeBytes` - no
/// extra computation needed, unlike a folder's.
func sortedFiles(_ files: [StoredFileSummaryResponse], by option: SortOption) -> [StoredFileSummaryResponse] {
    switch option {
    case .alphabetical:
        return files.sorted { $0.fileName.localizedCaseInsensitiveCompare($1.fileName) == .orderedAscending }
    case .numeric:
        return files.sorted { $0.fileName.compare($1.fileName, options: [.numeric, .caseInsensitive]) == .orderedAscending }
    case .createdAt:
        return files.sorted { $0.createdAtEpochMilli < $1.createdAtEpochMilli }
    case .size:
        return files.sorted { $0.sizeBytes < $1.sizeBytes }
    }
}
