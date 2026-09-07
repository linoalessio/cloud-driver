package de.lino.cloud.extensions.thumbnails;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Renders a PDF's first page (only) to a JPEG thumbnail, via Apache PDFBox. */
final class PdfThumbnailGenerator implements ThumbnailGenerator {

    /** Render resolution before scaling down to {@code maxDimensionPixels} - high enough that small text stays legible in the thumbnail. */
    private static final float RENDER_DPI = 72f;

    /** {@inheritDoc} */
    @Override
    public boolean supports(final String contentType) {
        return "application/pdf".equals(contentType);
    }

    /** {@inheritDoc} */
    @Override
    public byte[] generate(final byte[] sourceContent, final int maxDimensionPixels) throws IOException {
        try (PDDocument document = PDDocument.load(sourceContent)) {
            if (document.getNumberOfPages() == 0) {
                throw new IOException("@PdfThumbnailGenerator.generate: PDF has no pages");
            }
            final PDFRenderer renderer = new PDFRenderer(document);
            final BufferedImage firstPage = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
            return ImageScaling.scaleAndEncodeJpeg(firstPage, maxDimensionPixels);
        }
    }

}
