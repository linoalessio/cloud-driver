package de.lino.cloud.extensions.thumbnails;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Direct resize for JPEG/PNG via the JDK's own built-in {@link ImageIO} - no third-party
 * dependency needed. <b>WebP is deliberately not supported in v1</b> - {@code
 * ImageIO} has no built-in WebP reader, and
 * adding one (e.g. TwelveMonkeys' {@code imageio-webp}) would need the exact same
 * cloud-driver-bootstrap-shading fix {@code cloud-driver-extensions-metrics}' Micrometer
 * dependency already needed, for a
 * format this deployment doesn't yet have evidence of needing - flagged as a follow-up, the same
 * treatment given to video.
 */
final class ImageThumbnailGenerator implements ThumbnailGenerator {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    /**
     * Largest source image this generator will decode, in pixels.
     *
     * <p>Read from the image's own header before any raster is allocated - that check is the whole
     * defence against a decompression bomb, since a few kilobytes declaring an enormous image would
     * otherwise make the decoder reserve gigabytes up front. A sane value is far beyond any real
     * photograph a preview is wanted for, and far below what a deliberately crafted header can claim.
     */
    private final long maxSourcePixels;

    /**
     * @param maxSourcePixels the largest image, in pixels, this generator may allocate a raster for -
     *     the deployment's {@code thumbnail-max-decoded-pixels} setting
     */
    ImageThumbnailGenerator(final long maxSourcePixels) {
        this.maxSourcePixels = maxSourcePixels;
    }

    /** {@inheritDoc} */
    @Override
    public boolean supports(final String contentType) {
        return SUPPORTED_CONTENT_TYPES.contains(contentType);
    }

    /** {@inheritDoc} */
    @Override
    public byte[] generate(final byte[] sourceContent, final int maxDimensionPixels) throws IOException {
        try (javax.imageio.stream.ImageInputStream input =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(sourceContent))) {
            if (input == null) {
                throw new IOException("@ImageThumbnailGenerator.generate: unable to read image content");
            }
            final java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("@ImageThumbnailGenerator.generate: unable to decode image content");
            }
            final javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                // Dimensions come from the header, before any raster is allocated. This is the
                // whole defence against a decompression bomb: a few kilobytes declaring a
                // 23000x23000 image makes the decoder reserve gigabytes up front, and the
                // allocation lands wherever in the process happens to ask for memory next.
                final long pixels = (long) reader.getWidth(0) * (long) reader.getHeight(0);
                if (pixels > this.maxSourcePixels) {
                    throw new IOException("@ImageThumbnailGenerator.generate: image declares " + pixels
                            + " pixels, above the " + this.maxSourcePixels + " budget - refusing to decode it");
                }
                final BufferedImage source = reader.read(0);
                if (source == null) {
                    throw new IOException("@ImageThumbnailGenerator.generate: unable to decode image content");
                }
                return ImageScaling.scaleAndEncodeJpeg(source, maxDimensionPixels);
            } finally {
                reader.dispose();
            }
        }
    }

}
