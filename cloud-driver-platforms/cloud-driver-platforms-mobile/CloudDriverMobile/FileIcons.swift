import Foundation
import SwiftUI

/// Chooses a representative SF Symbol for a file's server-assigned content type - shared by
/// every screen that lists files (`FileBrowserView`, `SharedWithMeView`, `SharedFolderBrowserView`,
/// `TrashView`) so the mapping never drifts between them.
func fileIcon(for contentType: String) -> String {
    if contentType.hasPrefix("image/") { return "photo" }
    if contentType.hasPrefix("video/") { return "film" }
    if contentType.hasPrefix("audio/") { return "music.note" }
    if contentType == "application/pdf" { return "doc.richtext" }
    if contentType.hasPrefix("text/") { return "doc.text" }
    if contentType.contains("zip") { return "doc.zipper" }
    return "doc"
}

/// A distinct tint per file category - the mobile counterpart to cloud-driver-platforms-desktop's
/// own `EntryIcons.kt#colorFor`, added 2026-09-05 per Lino's own request ("the icons of the app
/// are too boring... i want them to be creative like in the desktop-app"). Every file row used to
/// render with the exact same flat `CloudTheme.iconFile` tint regardless of `fileIcon(for:)`
/// already choosing a different *glyph* per type - so a photo, a video, and a PDF all looked like
/// variations on one color, only distinguishable by squinting at the small glyph shape itself.
/// Matches the desktop app's own category-to-color mapping exactly, so both clients agree on
/// "what color means what kind of file": green for images, pink for video, purple for audio, red
/// for PDF, orange for archives, blue for text (and folders, via `CloudTheme.iconFolder` - already
/// unchanged and already blue), gray for anything else.
func fileIconColor(for contentType: String) -> Color {
    if contentType.hasPrefix("image/") { return .green }
    if contentType.hasPrefix("video/") { return .pink }
    if contentType.hasPrefix("audio/") { return .purple }
    if contentType == "application/pdf" { return .red }
    if contentType.contains("zip") { return .orange }
    if contentType.hasPrefix("text/") { return CloudTheme.iconFile }
    return .gray
}
