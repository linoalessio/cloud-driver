package de.lino.cloud.extensions.thumbnails;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Direct resize for JPEG/PNG via the JDK's own built-in {@link ImageIO} - no third-party
 * dependency needed. <b>WebP is deliberately not supported in v1</b>, despite {@code
 * architecture/MICRO.md} section 1 listing it - {@code ImageIO} has no built-in WebP reader, and
 * adding one (e.g. TwelveMonkeys' {@code imageio-webp}) would need the exact same
 * cloud-driver-bootstrap-shading fix {@code cloud-driver-extensions-metrics}' Micrometer
 * dependency already needed (see that module's own incident writeup in {@code CLAUDE.md}) for a
 * format this deployment doesn't yet have evidence of needing - the same "skip it in v1, note as
 * a follow-up" treatment the doc itself explicitly grants video.
 */
final class ImageThumbnailGenerator implements ThumbnailGenerator {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    /** {@inheritDoc} */
    @Override
    public boolean supports(final String contentType) {
        return SUPPORTED_CONTENT_TYPES.contains(contentType);
    }

    /** {@inheritDoc} */
    @Override
    public byte[] generate(final byte[] sourceContent, final int maxDimensionPixels) throws IOException {
        final BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceContent));
        if (source == null) {
            throw new IOException("@ImageThumbnailGenerator.generate: unable to decode image content");
        }
        return ImageScaling.scaleAndEncodeJpeg(source, maxDimensionPixels);
    }

}
