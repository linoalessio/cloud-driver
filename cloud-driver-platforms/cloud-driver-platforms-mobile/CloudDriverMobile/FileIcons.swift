import Foundation

/// Chooses a representative SF Symbol for a file's server-assigned content type - shared by
/// every screen that lists files (`FileBrowserView`, `SharedWithMeView`, `SharedFolderBrowserView`)
/// so the mapping never drifts between them.
func fileIcon(for contentType: String) -> String {
    if contentType.hasPrefix("image/") { return "photo" }
    if contentType.hasPrefix("video/") { return "film" }
    if contentType.hasPrefix("audio/") { return "music.note" }
    if contentType == "application/pdf" { return "doc.richtext" }
    if contentType.hasPrefix("text/") { return "doc.text" }
    if contentType.contains("zip") { return "doc.zipper" }
    return "doc"
}
