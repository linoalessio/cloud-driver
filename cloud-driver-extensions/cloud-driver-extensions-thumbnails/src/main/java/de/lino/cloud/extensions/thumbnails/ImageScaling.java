package de.lino.cloud.extensions.thumbnails;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** Shared scale-down-and-JPEG-encode helper used by both {@link ImageThumbnailGenerator} and {@link PdfThumbnailGenerator}. */
final class ImageScaling {

    private ImageScaling() {
    }

    /**
     * Scales {@code source} down to fit within a {@code maxDimensionPixels} square (preserving
     * aspect ratio, never upscaling a source already smaller than that) and JPEG-encodes the
     * result. The scaled image is always drawn onto a fresh {@code TYPE_INT_RGB} buffer first,
     * since JPEG has no alpha channel - a source with transparency (e.g. a PNG) would otherwise
     * fail to encode or render with a black background depending on the JDK's own JPEG writer.
     *
     * @param source the image to scale
     * @param maxDimensionPixels the longest edge to scale down to fit within
     * @return the scaled, JPEG-encoded bytes
     * @throws IOException if JPEG encoding fails
     */
    static byte[] scaleAndEncodeJpeg(final BufferedImage source, final int maxDimensionPixels) throws IOException {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final double scaleFactor = Math.min(1.0, (double) maxDimensionPixels / Math.max(width, height));
        final int targetWidth = Math.max(1, (int) Math.round(width * scaleFactor));
        final int targetHeight = Math.max(1, (int) Math.round(height * scaleFactor));

        final BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "jpg", buffer)) {
            throw new IOException("@ImageScaling.scaleAndEncodeJpeg: no JPEG writer available");
        }
        return buffer.toByteArray();
    }

}
