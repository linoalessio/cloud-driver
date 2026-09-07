package de.lino.cloud.extensions.thumbnails;

import java.io.IOException;

/**
 * Generates a JPEG-encoded thumbnail from a source file's raw content - one implementation per
 * supported content type family, dispatched by {@link CloudThumbnailsExtension} via {@link
 * #supports(String)}.
 */
interface ThumbnailGenerator {

    /**
     * @param contentType the source file's MIME content type
     * @return {@code true} if this generator can produce a thumbnail for {@code contentType}
     */
    boolean supports(String contentType);

    /**
     * @param sourceContent the source file's raw, decrypted bytes
     * @param maxDimensionPixels the longest edge to scale the result down to fit within - never upscaled
     * @return the generated thumbnail's raw JPEG-encoded bytes
     * @throws IOException if {@code sourceContent} cannot be decoded/rendered
     */
    byte[] generate(byte[] sourceContent, int maxDimensionPixels) throws IOException;

}
