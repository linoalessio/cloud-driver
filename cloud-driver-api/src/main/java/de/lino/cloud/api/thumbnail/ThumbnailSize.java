package de.lino.cloud.api.thumbnail;

/**
 * A thumbnail size variant a caller can request from {@link ThumbnailService#getThumbnail}.
 *
 * <p>Exactly one variant exists in v1 ({@link #SMALL}), deliberately - do not over-engineer this
 * in v1, and nothing in this codebase yet requests more than one size. The type is still a real enum (not a
 * hardcoded constant) so a second size can be added later without a REST/entity schema change -
 * {@code FileThumbnail}'s primary key already includes {@link #name()}, not just the source
 * file's id.
 */
public enum ThumbnailSize {

    /** A single-edge cap of 256px, never upscaled - the only size generated/served today. */
    SMALL(256);

    private final int maxDimensionPixels;

    ThumbnailSize(final int maxDimensionPixels) {
        this.maxDimensionPixels = maxDimensionPixels;
    }

    /**
     * @return the longest edge a generated thumbnail of this size is scaled down to fit within -
     *     a source image/page smaller than this is never upscaled, only re-encoded
     */
    public int maxDimensionPixels() {
        return this.maxDimensionPixels;
    }

}
