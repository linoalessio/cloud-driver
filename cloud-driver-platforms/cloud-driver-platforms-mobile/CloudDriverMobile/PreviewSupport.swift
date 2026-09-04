import Foundation

/// Which broad category of preview a file's server-assigned `contentType` supports - the mobile
/// counterpart to cloud-driver-platforms-desktop's `PreviewSupport.kt#PreviewKind`, same scope
/// (text/PDF/DOCX only) for consistency across both clients. Unlike the desktop app (no JVM
/// equivalent to QuickLook exists, so it hand-rolls PDF/DOCX rendering via PDFBox/POI), this app
/// doesn't need its own renderer per kind - `QLPreviewController` (`FilePreviewView.swift`) already
/// knows how to display all three natively from the downloaded file's own extension. This enum
/// still exists to gate *which* files are even offered a single-tap preview at all, and to pick
/// the right size cap before downloading anything.
enum PreviewKind {
    case text
    case pdf
    case docx
    case none
}

/// Content types QuickLook can't infer are text from a `text/` prefix alone - matches the desktop
/// app's own `previewKindFor`'s additional text-ish grouping (json/xml/yaml/toml), reimplemented
/// here rather than shared code, the same "hand-kept-in-sync port" convention this module's own
/// `ByteFormat.swift`/`FileIcons.swift` already document for their server-mirrored logic.
private let additionalTextPreviewContentTypes: Set<String> = [
    "application/json", "application/xml", "application/x-yaml", "text/yaml",
    "application/x-yaml", "application/toml"
]

/// 20 MB - matches `cloud-driver-platforms-desktop`'s `MAX_TEXT_PREVIEW_SOURCE_BYTES`.
let maxTextPreviewSourceBytes: Int64 = 20 * 1024 * 1024
/// 50 MB - matches `cloud-driver-platforms-desktop`'s `MAX_PDF_DOCX_PREVIEW_SOURCE_BYTES`.
let maxPdfDocxPreviewSourceBytes: Int64 = 50 * 1024 * 1024

func previewKind(for contentType: String) -> PreviewKind {
    if contentType.hasPrefix("text/") || additionalTextPreviewContentTypes.contains(contentType) {
        return .text
    }
    if contentType == "application/pdf" {
        return .pdf
    }
    if contentType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" {
        return .docx
    }
    return .none
}

/// The size cap that applies to `kind` - `nil` for `.none`, since there's nothing to size-check
/// for a file this app never offers to preview in the first place.
func maxPreviewSourceBytes(for kind: PreviewKind) -> Int64? {
    switch kind {
    case .text: return maxTextPreviewSourceBytes
    case .pdf, .docx: return maxPdfDocxPreviewSourceBytes
    case .none: return nil
    }
}
