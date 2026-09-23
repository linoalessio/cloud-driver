package de.lino.cloud.api.file.exception;

/**
 * Thrown when a part URL is asked for a part number the session cannot hold.
 *
 * <p>A session's geometry - its part size and its part count - is fixed when it begins, from the
 * length the stored object must have. A part number outside {@code 1..partCount} addresses bytes
 * that object can never contain, and a presigned part URL carries no signed content length, so
 * issuing one would accept content nothing accounts for.
 *
 * <p>Deliberately an {@link IllegalArgumentException}: every in-process caller and the documented
 * {@code @throws IllegalArgumentException} contract keep holding, and only the layer that maps a
 * failure onto a response needs to know the finer distinction - an out-of-range part number is a
 * bad request against a session that exists, not a missing session.
 */
public final class ResumableUploadPartRangeException extends IllegalArgumentException {

    /** The part number the caller asked for. */
    private final int partNumber;

    /** How many parts the session actually has. */
    private final int partCount;

    /**
     * @param partNumber the part number the caller asked for
     * @param partCount how many parts the session actually has
     */
    public ResumableUploadPartRangeException(final int partNumber, final int partCount) {
        super("partNumber must be between 1 and " + partCount + ", got " + partNumber);
        this.partNumber = partNumber;
        this.partCount = partCount;
    }

    /** @return the part number the caller asked for */
    public int partNumber() {
        return this.partNumber;
    }

    /** @return how many parts the session actually has */
    public int partCount() {
        return this.partCount;
    }

}
