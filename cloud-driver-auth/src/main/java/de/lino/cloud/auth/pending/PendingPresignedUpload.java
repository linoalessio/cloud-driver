package de.lino.cloud.auth.pending;

import de.lino.cloud.api.file.PresignedUploadTicket;
import de.lino.cloud.auth.CloudUserService;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

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
public final class PendingPresignedUpload extends Serialized {

    /** The {@link PresignedUploadTicket#fileId()} this ticket was issued under; also this entity's primary key and the object's own S3 key. */
    private final String fileId;

    /** The account {@link CloudUserService#beginPresignedUpload} issued this ticket to. */
    private final String authUserId;

    /** When this ticket was issued (epoch millis) - a purge job ages this row out relative to this instant, not the (much shorter) presigned URL's own expiry. */
    private final long createdAtEpochMillis;

    /**
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}, also this entity's {@link #primaryKey()}
     * @param authUserId the account this ticket was issued to
     * @param createdAtEpochMillis when this ticket was issued (epoch millis)
     */
    public PendingPresignedUpload(@NotNull final String fileId, @NotNull final String authUserId, final long createdAtEpochMillis) {
        this.fileId = Objects.requireNonNull(fileId, "@PendingPresignedUpload.init: fileId cannot be null");
        this.authUserId = Objects.requireNonNull(authUserId, "@PendingPresignedUpload.init: authUserId cannot be null");
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    /** @return this entity's primary key, {@link #fileId} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.fileId);
    }

}
