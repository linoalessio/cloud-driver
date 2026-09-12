package de.lino.cloud.api.file;

import org.jetbrains.annotations.Nullable;

/**
 * The outcome of beginning a resumable upload session - exactly one of the two is set (roadmap
 * Phase 5, dedup sign-off resolved 2026-09-12): when the declared checksum matches content the
 * caller's account already stores, the upload is skipped outright and {@code alreadyStored}
 * carries the freshly registered dedup alias (zero bytes transferred - the whole point of
 * declaring the checksum up front); otherwise {@code ticket} carries the session to upload
 * through. Safe to trust the declared checksum here because deduplication is strictly
 * per-account: a false claim can only alias the caller to content the caller already owns.
 *
 * @param alreadyStored the dedup alias's summary, when the checksum precheck hit
 * @param ticket the session ticket, when a real upload is needed
 */
public record ResumableUploadBegin(@Nullable StoredFileSummary alreadyStored, @Nullable ResumableUploadTicket ticket) {

    /** A dedup-hit outcome - see {@link #alreadyStored()}. */
    public static ResumableUploadBegin deduplicated(final StoredFileSummary alreadyStored) {
        return new ResumableUploadBegin(alreadyStored, null);
    }

    /** A real-upload outcome - see {@link #ticket()}. */
    public static ResumableUploadBegin session(final ResumableUploadTicket ticket) {
        return new ResumableUploadBegin(null, ticket);
    }
}
