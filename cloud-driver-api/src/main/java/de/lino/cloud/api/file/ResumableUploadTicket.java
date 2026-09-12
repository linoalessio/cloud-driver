package de.lino.cloud.api.file;

import org.jetbrains.annotations.Nullable;

/**
 * One freshly begun resumable upload session - what {@code
 * CloudUserService#beginResumableUpload} answers when the declared checksum did <b>not</b>
 * short-circuit into a dedup alias: the session's identity plus the fixed part geometry the
 * client cuts its (encrypted, when {@code encryption} is set) byte stream into. Part URLs are
 * deliberately not included - the client presigns each part on demand, so a session with
 * hundreds of parts never pays for hundreds of URLs it may not live long enough to use.
 *
 * @param fileId the session's id - the object key, the future {@link StoredFile#fileId()}, and
 *     what every follow-up call (status/part/complete/abort) addresses the session by
 * @param partSizeBytes the fixed byte size of every part except the last - a byte range over
 *     the final object stream, so a client resuming after a crash regenerates any part
 *     deterministically (the chunked encryption is deterministic per issued key/nonce base)
 * @param partCount how many parts the session's object splits into
 * @param totalObjectBytes the exact total byte size of the object the parts must assemble into -
 *     ciphertext size when {@code encryption} is set, the declared plaintext size otherwise
 * @param encryption the client-side encryption parameters (same contract as a plain presigned
 *     upload's {@link PresignedUploadTicket#encryption()}), or {@code null} on a deployment
 *     without a content-key service (the object is stored as the client sends it)
 */
public record ResumableUploadTicket(String fileId, long partSizeBytes, int partCount, long totalObjectBytes,
                                     @Nullable PresignedUploadEncryption encryption) {
}
