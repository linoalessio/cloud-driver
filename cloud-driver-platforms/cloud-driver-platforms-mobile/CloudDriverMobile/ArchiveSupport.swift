import Foundation

/// Whether `contentType` (a file's server-assigned content type) identifies a ZIP archive -
/// content-type only, mirroring cloud-driver-platforms-desktop's own
/// `PreviewSupport.kt#isZipArchive`. `StoredFileSummaryResponse.contentType` is always
/// server-derived from the file's own extension (`Constraints.CONTENT_TYPES`, cloud-driver-api),
/// so a real `.zip` file is guaranteed to already carry this type.
func isZipArchive(_ contentType: String) -> Bool {
    contentType.lowercased() == "application/zip"
}

/// `archiveFileName` with its extension stripped - `"test.zip"` -> `"test"` - used to name the
/// folder an archive's contents are extracted into. Mirrors cloud-driver-platforms-desktop's own
/// `AppViewModel.kt#archiveBaseName`.
func archiveBaseName(_ archiveFileName: String) -> String {
    guard let dotIndex = archiveFileName.lastIndex(of: "."),
          dotIndex > archiveFileName.startIndex,
          archiveFileName.index(after: dotIndex) < archiveFileName.endIndex else {
        return archiveFileName
    }
    return String(archiveFileName[archiveFileName.startIndex..<dotIndex])
}

/// Replaces any `/` in `fileName` with `_` so it's safe to append as a single path component to a
/// local `URL` (via `appendingPathComponent`/string concatenation) without being misread as
/// introducing a subdirectory. A file's display name is arbitrary user input and may legally
/// contain `/` (e.g. `"Gardasil 9 Impfung, Rezept/Rechnung.pdf"`) - `FileManager`/`URL` handling
/// otherwise treats that as a real directory separator, so `FileManager.moveItem` throws
/// `"couldn't be moved... because... the folder containing the latter doesn't exist"` the moment a
/// download/preview/extraction tries to write to a temp path built directly from the raw name.
/// Every local temp-path construction in `AppViewModel.swift` (`download`/`previewFile`/
/// `downloadAndExtractArchive`) must call this on `file.fileName` first, never append it raw.
func sanitizedForLocalPath(_ fileName: String) -> String {
    fileName.replacingOccurrences(of: "/", with: "_")
}

/// Picks a folder name for `baseName` that doesn't collide with anything in `existingNames` -
/// `"test"`, then `"test 2"`, `"test 3"`, ... if `"test"` is already taken. Mirrors
/// cloud-driver-platforms-desktop's own `AppViewModel.kt#uniqueFolderName` - deliberately not a
/// `"... copy"`/`"... copy 2"` convention, since a re-extracted archive isn't a duplicate of
/// anything, it's the same archive's content landing in a fresh folder each time.
func uniqueFolderName(_ baseName: String, existingNames: Set<String>) -> String {
    if !existingNames.contains(baseName) {
        return baseName
    }
    var suffix = 2
    while existingNames.contains("\(baseName) \(suffix)") {
        suffix += 1
    }
    return "\(baseName) \(suffix)"
}
