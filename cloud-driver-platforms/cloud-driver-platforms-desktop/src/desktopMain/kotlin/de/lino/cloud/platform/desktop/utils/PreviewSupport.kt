package de.lino.cloud.platform.desktop.utils

/** Which kind of in-app preview `FilePreviewDialog` renders for a file, based on its content type. */
enum class PreviewKind { TEXT, PDF, DOCX, NONE }

private const val PDF_CONTENT_TYPE = "application/pdf"
private const val DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/**
 * Content types (beyond a plain leading `"text/"` prefix) treated as plain-text-previewable - mirrors the
 * text-ish entries of `Constraints.CONTENT_TYPES` (`cloud-driver-api`), reimplemented client-side
 * rather than depended on, same "this module never depends on cloud-driver-api" reasoning
 * [formatBytes] already documents for its own port of `Constraints#resolveBytesToUnit`.
 */
private val TEXT_PREVIEW_CONTENT_TYPES = setOf(
    "application/json", "application/xml", "application/yaml", "application/toml",
)

/** The content type `Constraints.CONTENT_TYPES` (`cloud-driver-api`) maps the `.zip` extension to, server-side - see [isZipArchive]. */
private const val ZIP_CONTENT_TYPE = "application/zip"

/**
 * Whether a file identified by [contentType] is a ZIP archive - double-clicking such a file
 * extracts it into the current folder instead of opening a preview (see
 * `AppViewModel.extractArchive`, `FileBrowserScreen`'s `EntryRow` double-click wiring). Content-type
 * only, not a filename-extension fallback: [contentType] is always server-derived from the file's
 * own extension (`StoredFile#contentType()`, `cloud-driver-api`), so a file actually named `.zip`
 * is guaranteed to carry [ZIP_CONTENT_TYPE] already. Deliberately narrower than [iconFor]'s own
 * archive-icon classification (which also covers 7z/tar/gzip/rar/bzip2/xz for icon purposes) -
 * double-click-to-extract only supports ZIP, the one format the JDK can read without a
 * third-party library.
 */
fun isZipArchive(contentType: String?): Boolean = contentType?.lowercase() == ZIP_CONTENT_TYPE

/**
 * Classifies [contentType] (a file's already-server-assigned content type, e.g.
 * `StoredFileSummaryResponse#contentType()`) into a [PreviewKind] - [PreviewKind.NONE] means
 * `FilePreviewDialog` falls back to a "preview not supported, download to view" message rather
 * than attempting to render anything.
 */
fun previewKindFor(contentType: String?): PreviewKind {
    val type = contentType?.lowercase() ?: return PreviewKind.NONE
    return when {
        type == PDF_CONTENT_TYPE -> PreviewKind.PDF
        type == DOCX_CONTENT_TYPE -> PreviewKind.DOCX
        type.startsWith("text/") || type in TEXT_PREVIEW_CONTENT_TYPES -> PreviewKind.TEXT
        else -> PreviewKind.NONE
    }
}

/**
 * Source-file size ceiling above which `FilePreviewDialog` refuses to even download the file for a
 * PDF/DOCX preview, showing a "too large to preview" message instead - a full in-memory
 * PDFBox/POI parse of a huge file is a real memory/latency cost a preview pane has no business
 * paying; the file is still fully reachable via the existing "Download" action.
 */
const val MAX_PDF_DOCX_PREVIEW_SOURCE_BYTES = 50L * 1024 * 1024

/** Same ceiling as [MAX_PDF_DOCX_PREVIEW_SOURCE_BYTES], for a plain-text file - text decoding is cheap, but the file is still downloaded in full first. */
const val MAX_TEXT_PREVIEW_SOURCE_BYTES = 20L * 1024 * 1024

/** How many bytes of an under-[MAX_TEXT_PREVIEW_SOURCE_BYTES] text file are actually read/rendered - keeps the dialog responsive for e.g. a multi-MB single-line minified file; the rest is never read off disk. */
const val MAX_TEXT_PREVIEW_DISPLAY_BYTES = 2L * 1024 * 1024
