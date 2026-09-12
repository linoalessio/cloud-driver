package de.lino.cloud.api.file;

import org.jetbrains.annotations.Nullable;

/**
 * The outcome of beginning a single-{@code PUT} presigned upload with a declared checksum -
 * exactly one of the two is set, with the same dedup-precheck semantics {@link
 * ResumableUploadBegin} documents: a checksum match against
 * content the caller's account already stores skips the upload entirely and registers a dedup
 * alias instead.
 *
 * @param alreadyStored the dedup alias's summary, when the checksum precheck hit
 * @param ticket the presigned upload ticket, when a real upload is needed
 */
public record PresignedUploadBegin(@Nullable StoredFileSummary alreadyStored, @Nullable PresignedUploadTicket ticket) {

    /** A dedup-hit outcome - see {@link #alreadyStored()}. */
    public static PresignedUploadBegin deduplicated(final StoredFileSummary alreadyStored) {
        return new PresignedUploadBegin(alreadyStored, null);
    }

    /** A real-upload outcome - see {@link #ticket()}. */
    public static PresignedUploadBegin ticket(final PresignedUploadTicket ticket) {
        return new PresignedUploadBegin(null, ticket);
    }
}
