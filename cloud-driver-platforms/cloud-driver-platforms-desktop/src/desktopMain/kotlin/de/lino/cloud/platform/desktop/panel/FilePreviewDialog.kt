package de.lino.cloud.platform.desktop.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.lino.cloud.platform.desktop.client.CloudDriverClient
import de.lino.cloud.platform.desktop.model.Entry
import de.lino.cloud.platform.desktop.utils.MAX_PDF_DOCX_PREVIEW_SOURCE_BYTES
import de.lino.cloud.platform.desktop.utils.MAX_TEXT_PREVIEW_DISPLAY_BYTES
import de.lino.cloud.platform.desktop.utils.MAX_TEXT_PREVIEW_SOURCE_BYTES
import de.lino.cloud.platform.desktop.utils.PreviewKind
import de.lino.cloud.platform.desktop.utils.formatBytes
import de.lino.cloud.platform.desktop.utils.previewKindFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Double-clicking a text/PDF/DOCX file row (see `EntryRow` in `FileBrowserScreen.kt`) opens this
 * dialog instead of only offering "Download" - the whole file is still streamed to a throwaway
 * local temp file first (via [CloudDriverClient.downloadFileToPath], same as every other transfer
 * in this app), then parsed in place: a plain-text file is decoded and shown directly; a PDF is
 * rendered page-by-page via Apache PDFBox; a DOCX has its text extracted via Apache POI (formatting
 * is not reproduced - this is a content preview, not a document renderer). Any other file type
 * shows a "preview not supported" message - the file is still reachable via "Download".
 *
 * [entry]'s own [Entry.FileEntry.sizeBytes] (already known from the folder listing) is checked
 * against [MAX_TEXT_PREVIEW_SOURCE_BYTES]/[MAX_PDF_DOCX_PREVIEW_SOURCE_BYTES] *before* any network
 * call, so an oversized file never gets downloaded just to be rejected.
 */
@Composable
fun FilePreviewDialog(entry: Entry.FileEntry, client: CloudDriverClient, onDismiss: () -> Unit) {
    val kind = remember(entry.id) { previewKindFor(entry.summary.contentType()) }

    var loading by remember(entry.id) { mutableStateOf(true) }
    var tooLarge by remember(entry.id) { mutableStateOf(false) }
    var errorMessage by remember(entry.id) { mutableStateOf<String?>(null) }

    var textContent by remember(entry.id) { mutableStateOf<String?>(null) }
    var textTruncated by remember(entry.id) { mutableStateOf(false) }

    var pdfDocument by remember(entry.id) { mutableStateOf<PDDocument?>(null) }
    var pdfPageCount by remember(entry.id) { mutableStateOf(0) }
    var pdfPageIndex by remember(entry.id) { mutableStateOf(0) }
    var pdfPageBitmap by remember(entry.id) { mutableStateOf<ImageBitmap?>(null) }

    // PDDocument holds native (non-JVM-GC'd) resources open for as long as this dialog lets the
    // user page through it - must be closed explicitly once the dialog is dismissed or a different
    // entry replaces it, not left to garbage collection.
    DisposableEffect(entry.id) {
        onDispose { pdfDocument?.close() }
    }

    LaunchedEffect(entry.id) {
        if (kind == PreviewKind.NONE) {
            loading = false
            return@LaunchedEffect
        }
        val sourceLimit = if (kind == PreviewKind.TEXT) MAX_TEXT_PREVIEW_SOURCE_BYTES else MAX_PDF_DOCX_PREVIEW_SOURCE_BYTES
        if (entry.sizeBytes > sourceLimit) {
            tooLarge = true
            loading = false
            return@LaunchedEffect
        }
        try {
            withContext(Dispatchers.IO) {
                val tempDir = Files.createTempDirectory("cloud-driver-preview")
                val tempFile = tempDir.resolve(entry.name)
                try {
                    client.downloadFileToPath(entry.id, tempFile)
                    when (kind) {
                        PreviewKind.TEXT -> {
                            val (bytes, truncated) = readLimited(tempFile, MAX_TEXT_PREVIEW_DISPLAY_BYTES)
                            textContent = String(bytes, Charsets.UTF_8)
                            textTruncated = truncated
                        }
                        PreviewKind.DOCX -> {
                            XWPFDocument(Files.newInputStream(tempFile)).use { document ->
                                textContent = XWPFWordExtractor(document).text
                            }
                        }
                        PreviewKind.PDF -> {
                            // PDDocument.load(File) parses the whole document into memory eagerly
                            // (unlike a lazily-streamed InputStream source), so it's safe to delete
                            // the temp file/directory in this same `finally` block right after -
                            // page rendering later (renderImageWithDPI) never re-reads from disk.
                            val document = PDDocument.load(tempFile.toFile())
                            pdfDocument = document
                            pdfPageCount = document.numberOfPages
                        }
                        PreviewKind.NONE -> Unit
                    }
                } finally {
                    Files.deleteIfExists(tempFile)
                    Files.deleteIfExists(tempDir)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load preview"
        } finally {
            loading = false
        }
    }

    // Renders whichever PDF page is currently selected - re-runs on every pdfPageIndex change (Next/Previous).
    LaunchedEffect(pdfDocument, pdfPageIndex) {
        val document = pdfDocument ?: return@LaunchedEffect
        try {
            pdfPageBitmap = withContext(Dispatchers.IO) {
                PDFRenderer(document).renderImageWithDPI(pdfPageIndex, 110f).toComposeImageBitmap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to render page"
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator()
                        errorMessage != null -> Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        tooLarge -> Text(
                            "This file is too large to preview here - download it to view the full content.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        kind == PreviewKind.NONE -> Text(
                            "Preview isn't available for this file type - download it to view it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        kind == PreviewKind.PDF -> PdfPreview(
                            pageBitmap = pdfPageBitmap,
                            pageIndex = pdfPageIndex,
                            pageCount = pdfPageCount,
                            onPageIndexChange = { pdfPageIndex = it },
                        )
                        else -> TextPreview(text = textContent ?: "", truncated = textTruncated, monospace = kind == PreviewKind.TEXT)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPreview(pageBitmap: ImageBitmap?, pageIndex: Int, pageCount: Int, onPageIndexChange: (Int) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (pageBitmap != null) {
                Image(pageBitmap, contentDescription = null, modifier = Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator()
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPageIndexChange(pageIndex - 1) }, enabled = pageIndex > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
            }
            Text("Page ${pageIndex + 1} of $pageCount", style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { onPageIndexChange(pageIndex + 1) }, enabled = pageIndex < pageCount - 1) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
            }
        }
    }
}

@Composable
private fun TextPreview(text: String, truncated: Boolean, monospace: Boolean) {
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (truncated) {
                Text(
                    "Showing the first ${formatBytes(MAX_TEXT_PREVIEW_DISPLAY_BYTES)} of this file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Text(text, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Reads up to [limitBytes] of [path] - the whole file if it's under the limit, otherwise just its first [limitBytes] (`truncated = true`), without ever reading the rest off disk. */
private fun readLimited(path: Path, limitBytes: Long): Pair<ByteArray, Boolean> {
    val size = Files.size(path)
    if (size <= limitBytes) return Files.readAllBytes(path) to false
    RandomAccessFile(path.toFile(), "r").use { file ->
        val buffer = ByteArray(limitBytes.toInt())
        file.readFully(buffer)
        return buffer to true
    }
}
