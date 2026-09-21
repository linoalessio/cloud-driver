package de.lino.cloud.auth.pending;

import de.lino.cloud.api.file.PresignedUploadTicket;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.api.factory.SecondaryIndexed;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Tracks a {@link PresignedUploadTicket} issued by {@link CloudUserService#beginPresignedUpload}
 * that hasn't been confirmed via {@link CloudUserService#completePresignedUpload} yet - the
 * bookkeeping row that closes a real, previously-accepted gap: {@code beginPresignedUpload} used
 * to persist nothing at all, so a client that abandoned the upload (crash, closed app, network
 * failure, or simply never finishing) left its already-uploaded S3 object permanently orphaned,
 * with nothing anywhere ever tracking or cleaning it up.
 *
 * <p>{@code fileId} doubles as this entity's own primary key - it is also the object's own S3 key
 * (see {@code S3ObjectStorageService}'s "every call site passes {@code StoredFile#fileId()} as
 * {@code objectKey}" convention), so a purge job needs no extra lookup to know what to delete.
 *
 * <p>This row's absence is the normal, expected outcome once an upload actually completes -
 * {@link CloudUserService#completePresignedUpload} deletes it (best-effort) the moment the real
 * {@code StoredFile} is successfully registered. Its continued presence past a configured
 * retention window is exactly what {@code PendingPresignedUploadPurgeScheduler} (cloud-driver-plugin)
 * treats as "this upload was abandoned."
 *
 * <p>Envelope-encrypted like every other {@link Serialized} entity, though nothing here is
 * actually sensitive - the same "every entity gets the same treatment" convention this codebase
 * applies uniformly, not a deliberate confidentiality requirement for this particular row.
 */
@Getter @ToString
@EqualsAndHashCode(callSuper = false)
public final class PendingPresignedUpload extends Serialized implements SecondaryIndexed {

    /**
     * Secondary-index name for lookups by {@link #getAuthUserId()} - how the account's open
     * sessions are counted (concurrency cap) and summed (quota reservation) without a full scan
     * over every pending upload in the deployment.
     */
    public static final String INDEX_AUTH_USER_ID = "authUserId";

    /**
     * {@inheritDoc} Hand-declared, never reflective. Defensive against {@code null} fields, since
     * Gson rehydration bypasses the constructor's own null checks.
     */
    @NotNull
    @Override
    public java.util.Map<String, String> secondaryIndexKeys() {
        final java.util.Map<String, String> keys = new java.util.HashMap<>(1);
        if (this.authUserId != null) keys.put(INDEX_AUTH_USER_ID, this.authUserId);
        return keys;
    }


    /** The {@link PresignedUploadTicket#fileId()} this ticket was issued under; also this entity's primary key and the object's own S3 key. */
    private final String fileId;

    /** The account {@link CloudUserService#beginPresignedUpload} issued this ticket to. */
    private final String authUserId;

    /** When this ticket was issued (epoch millis) - a purge job ages this row out relative to this instant, not the (much shorter) presigned URL's own expiry. */
    private final long createdAtEpochMillis;

    /**
     * Base64 of the streaming header carrying the content-encryption key issued with this ticket
     * (see {@code ContentKeyService}) - carried from {@code beginPresignedUpload} to {@code
     * completePresignedUpload}, where it becomes the {@code StoredFile}'s own {@code
     * contentKeyHeaderBase64}. {@code null} on a ticket issued without client-side encryption
     * (no {@code ContentKeyService} on the deployment, or a row persisted before the feature
     * existed) - completion then treats the object as legacy plaintext.
     */
    @Nullable
    private final String contentKeyHeaderBase64;

    /**
     * The plaintext size, in bytes, the client declared at {@code beginPresignedUpload} time -
     * for an encrypted ticket, completion verifies the object store's confirmed ciphertext length
     * is <em>exactly</em> what this size produces under the issued key's chunked scheme. {@code
     * null} on a legacy row persisted before this field existed.
     */
    @Nullable
    private final Long declaredSizeBytes;

    /**
     * The object store's multipart upload id, when this row tracks a <b>resumable session</b>
     * rather than a single-{@code PUT} presigned ticket - what {@code
     * ListParts}/part-presign/complete/abort address the in-progress upload by. {@code null} on
     * every single-{@code PUT} ticket and every row persisted before sessions existed. A purge
     * sweep must {@code AbortMultipartUpload} a session row it ages out - S3 bills for
     * incomplete parts until aborted - where a single-{@code PUT} row only needs its (possibly
     * uploaded) object deleted.
     */
    @Nullable
    private final String multipartUploadId;

    /**
     * When this row was last touched by its client (epoch millis) - refreshed by every part
     * presign and every status poll on a resumable session.
     *
     * <p>A session's retention is measured from this, not from {@link #createdAtEpochMillis}: a
     * multi-hour upload over a slow link is still alive, and ageing it out from its start time
     * discards every part already stored - defeating exactly the crash-resume the feature exists
     * for. A single-{@code PUT} ticket has no activity to speak of and keeps ageing from creation.
     *
     * <p>{@code 0} for rows written before this field existed; readers must fall back to {@link
     * #createdAtEpochMillis} (see {@link #lastActivityOrCreatedAtEpochMillis()}).
     */
    private final long lastActivityAtEpochMillis;

    /**
     * This row's effective activity stamp, tolerating rows written before that field existed.
     *
     * @return {@link #lastActivityAtEpochMillis}, or {@link #createdAtEpochMillis} when it is unset
     */
    public long lastActivityOrCreatedAtEpochMillis() {
        return this.lastActivityAtEpochMillis > 0 ? this.lastActivityAtEpochMillis : this.createdAtEpochMillis;
    }

    /**
     * A copy of this row with its activity stamp moved to {@code atEpochMillis} - how a live
     * session defers the purge sweep.
     *
     * @param atEpochMillis the new activity stamp, in epoch millis
     * @return a copy carrying the new stamp; every other field is unchanged
     */
    @NotNull
    public PendingPresignedUpload withLastActivityAt(final long atEpochMillis) {
        return new PendingPresignedUpload(this.fileId, this.authUserId, this.createdAtEpochMillis,
                this.contentKeyHeaderBase64, this.declaredSizeBytes, this.multipartUploadId, atEpochMillis);
    }

    /**
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}, also this entity's {@link #primaryKey()}
     * @param authUserId the account this ticket was issued to
     * @param createdAtEpochMillis when this ticket was issued (epoch millis)
     * @param contentKeyHeaderBase64 base64 of the issued content key's streaming header, or {@code null} for an unencrypted ticket
     * @param declaredSizeBytes the plaintext size the client declared, or {@code null} if unknown
     * @param multipartUploadId the store's multipart upload id for a resumable session, or {@code null} for a single-{@code PUT} ticket
     */
    public PendingPresignedUpload(@NotNull final String fileId, @NotNull final String authUserId, final long createdAtEpochMillis,
                                   @Nullable final String contentKeyHeaderBase64, @Nullable final Long declaredSizeBytes,
                                   @Nullable final String multipartUploadId) {
        this(fileId, authUserId, createdAtEpochMillis, contentKeyHeaderBase64, declaredSizeBytes, multipartUploadId, createdAtEpochMillis);
    }

    /**
     * The full constructor, carrying an explicit activity stamp - used by {@link
     * #withLastActivityAt} and by Gson rehydration.
     *
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}, also this entity's {@link #primaryKey()}
     * @param authUserId the account this ticket was issued to
     * @param createdAtEpochMillis when this ticket was issued (epoch millis)
     * @param contentKeyHeaderBase64 base64 of the issued content key's streaming header, or {@code null} for an unencrypted ticket
     * @param declaredSizeBytes the plaintext size the client declared, or {@code null} if unknown
     * @param multipartUploadId the store's multipart upload id for a resumable session, or {@code null} for a single-{@code PUT} ticket
     * @param lastActivityAtEpochMillis when this row was last touched by its client (epoch millis)
     */
    public PendingPresignedUpload(@NotNull final String fileId, @NotNull final String authUserId, final long createdAtEpochMillis,
                                   @Nullable final String contentKeyHeaderBase64, @Nullable final Long declaredSizeBytes,
                                   @Nullable final String multipartUploadId, final long lastActivityAtEpochMillis) {
        this.fileId = Objects.requireNonNull(fileId, "@PendingPresignedUpload.init: fileId cannot be null");
        this.authUserId = Objects.requireNonNull(authUserId, "@PendingPresignedUpload.init: authUserId cannot be null");
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.contentKeyHeaderBase64 = contentKeyHeaderBase64;
        this.declaredSizeBytes = declaredSizeBytes;
        this.multipartUploadId = multipartUploadId;
        this.lastActivityAtEpochMillis = lastActivityAtEpochMillis;
    }

    /**
     * Same as the six-argument constructor with no multipart upload id - a single-{@code PUT}
     * presigned ticket, exactly the shape every row had before resumable sessions existed.
     *
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}, also this entity's {@link #primaryKey()}
     * @param authUserId the account this ticket was issued to
     * @param createdAtEpochMillis when this ticket was issued (epoch millis)
     * @param contentKeyHeaderBase64 base64 of the issued content key's streaming header, or {@code null} for an unencrypted ticket
     * @param declaredSizeBytes the plaintext size the client declared, or {@code null} if unknown
     */
    public PendingPresignedUpload(@NotNull final String fileId, @NotNull final String authUserId, final long createdAtEpochMillis,
                                   @Nullable final String contentKeyHeaderBase64, @Nullable final Long declaredSizeBytes) {
        this(fileId, authUserId, createdAtEpochMillis, contentKeyHeaderBase64, declaredSizeBytes, null);
    }

    /**
     * Same as the five-argument constructor with no content key and no declared size - an
     * unencrypted (legacy-behavior) ticket.
     *
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}, also this entity's {@link #primaryKey()}
     * @param authUserId the account this ticket was issued to
     * @param createdAtEpochMillis when this ticket was issued (epoch millis)
     */
    public PendingPresignedUpload(@NotNull final String fileId, @NotNull final String authUserId, final long createdAtEpochMillis) {
        this(fileId, authUserId, createdAtEpochMillis, null, null);
    }

    /** @return this entity's primary key, {@link #fileId} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.fileId);
    }

}
