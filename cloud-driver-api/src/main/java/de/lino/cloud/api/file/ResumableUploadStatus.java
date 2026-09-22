package de.lino.cloud.api.file;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One resumable upload session's durable progress - what {@code
 * CloudUserService#getResumableUploadStatus} answers after a reconnect: the same part geometry
 * the session's {@link ResumableUploadTicket} declared, plus which part numbers the object
 * store confirms it already holds (from the store's own listing, never a local mirror) - the
 * client re-sends exactly the missing ones. {@code encryption} carries the session's recovered
 * encryption parameters (key material unwrapped from the durably tracked header), so a client
 * that crashed and lost the original ticket can still regenerate its deterministic ciphertext for
 * the content the session was begun for.
 *
 * @param fileId the session's id
 * @param partSizeBytes the fixed byte size of every part except the last
 * @param partCount how many parts the session's object splits into
 * @param totalObjectBytes the exact total byte size the parts must assemble into
 * @param uploadedPartNumbers every 1-based part number the store already holds, ascending
 * @param encryption the session's recovered encryption parameters, or {@code null} for an
 *     unencrypted (no content-key service) session
 * @param checksumSha256Hex the plaintext digest recorded when the session began, or {@code null}
 *     for a session begun before it was recorded; a resuming client compares its file's digest
 *     against this and must not encrypt different content under the session's key
 */
public record ResumableUploadStatus(String fileId, long partSizeBytes, int partCount, long totalObjectBytes,
                                     List<Integer> uploadedPartNumbers, @Nullable PresignedUploadEncryption encryption,
                                     @Nullable String checksumSha256Hex) {
}
