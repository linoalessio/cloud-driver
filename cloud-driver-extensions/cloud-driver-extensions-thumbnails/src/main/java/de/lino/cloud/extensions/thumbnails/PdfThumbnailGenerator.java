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

    /**
     * Largest first page this generator will rasterize, in pixels.
     *
     * <p>A page box is author-controlled and unbounded in the format: a 20000-by-20000 point page
     * renders to well over a gigabyte at even modest resolution, allocated in one buffer before
     * anything is scaled down. Real documents are a tiny fraction of this.
     */
    private static final long MAX_RENDERED_PIXELS = 40_000_000L;

    /** {@inheritDoc} */
    @Override
    public byte[] generate(final byte[] sourceContent, final int maxDimensionPixels) throws IOException {
        try (PDDocument document = PDDocument.load(sourceContent)) {
            if (document.getNumberOfPages() == 0) {
                throw new IOException("@PdfThumbnailGenerator.generate: PDF has no pages");
            }
            // Measure the page before rendering it: the resulting raster's size follows from the
            // page box and the DPI, both known here, so an implausible page is refused rather
            // than allocated.
            final org.apache.pdfbox.pdmodel.common.PDRectangle box = document.getPage(0).getCropBox();
            final float scale = RENDER_DPI / 72f;
            final long renderedPixels = (long) Math.ceil(box.getWidth() * scale) * (long) Math.ceil(box.getHeight() * scale);
            if (renderedPixels > MAX_RENDERED_PIXELS) {
                throw new IOException("@PdfThumbnailGenerator.generate: first page would render to " + renderedPixels
                        + " pixels, above the " + MAX_RENDERED_PIXELS + " budget - refusing to rasterize it");
            }
            final PDFRenderer renderer = new PDFRenderer(document);
            final BufferedImage firstPage = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
            return ImageScaling.scaleAndEncodeJpeg(firstPage, maxDimensionPixels);
        }
    }

}
