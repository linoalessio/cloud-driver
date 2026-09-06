import Foundation

/// Which broad category of preview a file's server-assigned `contentType` supports - originally a
/// strict mirror of cloud-driver-platforms-desktop's `PreviewSupport.kt#PreviewKind` (text/PDF/DOCX
/// only), extended 2026-09-06 with `.image` (JPG/JPEG/PNG), per Lino's own request - a mobile-only
/// addition for now, not ported back to the desktop client. Unlike the desktop app (no JVM
/// equivalent to QuickLook exists, so it hand-rolls PDF/DOCX rendering via PDFBox/POI, and would
/// need a third library just for images), this app doesn't need its own renderer per kind -
/// `QLPreviewController` (`FilePreviewView.swift`) already knows how to display all four natively
/// from the downloaded file's own extension - `.image` needed no changes to that view at all, only
/// to this file's own classification/size-cap logic. This enum still exists to gate *which* files
/// are even offered a single-tap preview at all, and to pick the right size cap before downloading
/// anything.
enum PreviewKind {
    case text
    case pdf
    case docx
    case image
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

/// The exact content types `Constraints.CONTENT_TYPES` (`cloud-driver-api`) assigns `.jpg`/`.jpeg`/
/// `.png` - **JPG/JPEG/PNG only, per Lino's own explicit request**, not a broad `image/` prefix
/// match the way text uses one: an unlisted image format (GIF, HEIC, WEBP, ...) falls through to
/// `.none` below just like any other unsupported type, the same "flag it, don't scope-creep"
/// precedent this codebase applies elsewhere (e.g. archive extraction being ZIP-only, not
/// "zip/rar/7z").
private let imagePreviewContentTypes: Set<String> = ["image/jpeg", "image/png"]

/// 20 MB - matches `cloud-driver-platforms-desktop`'s `MAX_TEXT_PREVIEW_SOURCE_BYTES`.
let maxTextPreviewSourceBytes: Int64 = 20 * 1024 * 1024
/// 50 MB - matches `cloud-driver-platforms-desktop`'s `MAX_PDF_DOCX_PREVIEW_SOURCE_BYTES`.
let maxPdfDocxPreviewSourceBytes: Int64 = 50 * 1024 * 1024
/// 50 MB - same ceiling as PDF/DOCX rather than a smaller, image-specific one: a JPG/PNG is
/// typically far smaller than that in practice, but a high-resolution photo/screenshot can still
/// legitimately approach it, and QuickLook renders an image without this app decoding it into
/// memory itself first (unlike `rememberThumbnail`'s own row-icon path on the desktop client),
/// so there's no extra memory-pressure reason here to set a tighter cap than the other kinds.
let maxImagePreviewSourceBytes: Int64 = 50 * 1024 * 1024

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
    if imagePreviewContentTypes.contains(contentType) {
        return .image
    }
    return .none
}

/// The size cap that applies to `kind` - `nil` for `.none`, since there's nothing to size-check
/// for a file this app never offers to preview in the first place.
func maxPreviewSourceBytes(for kind: PreviewKind) -> Int64? {
    switch kind {
    case .text: return maxTextPreviewSourceBytes
    case .pdf, .docx: return maxPdfDocxPreviewSourceBytes
    case .image: return maxImagePreviewSourceBytes
    case .none: return nil
    }
}
