package de.lino.cloud.api.audit;

import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * One entry in the persisted, structured audit trail - who did what, when. See {@code
 * architecture/SERVICES.md} item 11 for the original design brief and {@code CLAUDE.md}'s "Audit
 * log service" section for the full picture (schema, exactly which actions are recorded and from
 * where, and how to read the trail back).
 *
 * <p><b>Deliberately placed in {@code cloud-driver-api}, not {@code cloud-driver-auth}</b> - the
 * doc's own suggested placement (alongside {@link AuditLogService}, in {@code cloud-driver-auth})
 * would violate this codebase's hard one-way dependency rule (see {@code CLAUDE.md}'s "Module
 * layout and dependency direction": {@code cloud-driver-api} must never depend on {@code
 * cloud-driver-auth}). {@link AuditLogService#record(AuditEvent)} needs this type visible from
 * {@code cloud-driver-api} itself (so {@code IServiceContainer} can expose an {@code
 * AuditLogService} getter without {@code cloud-driver-api} gaining a new dependency), so this
 * entity lives here instead - the same reasoning that already places {@code AuthUser} in {@code
 * cloud-driver-api} rather than {@code cloud-driver-auth}, even though {@code AuthService} (its
 * biggest consumer) lives in the latter.
 *
 * <p>Envelope-encrypted at rest like every other {@link Serialized} entity - no special-casing
 * needed for a record that may itself carry sensitive {@link #metadata} text, since encryption is
 * applied uniformly regardless of entity type.
 */
@Getter @ToString
@EqualsAndHashCode(callSuper = false)
public final class AuditEvent extends Serialized {

    /** This entry's own primary key - a fresh random id, since an audit entry has no natural key of its own. */
    private final String id;

    /**
     * The {@code AuthUser#getId()} that performed this action, or {@code null} if no account could
     * be identified as the actor (e.g. a failed login attempt against an email with no matching
     * account).
     */
    @Nullable
    private final String actorAuthUserId;

    /** Which kind of action this entry records. */
    @NotNull
    private final AuditAction action;

    /**
     * The id of whatever this action was performed against (e.g. the deleted file's id, the
     * attempted login's email address), or {@code null} if the action has no single natural
     * target.
     */
    @Nullable
    private final String targetId;

    /** When this action happened, in epoch milliseconds. */
    private final long timestampEpochMillis;

    /**
     * A short, free-form description of additional context - already redacted (via {@link
     * AuditLogService}'s injected redaction function) before this instance is persisted, so this
     * field never carries a raw secret by the time it reaches storage. {@code null} if the action
     * needed no extra context beyond {@link #action}/{@link #targetId}.
     */
    @Nullable
    private final String metadata;

    /**
     * Creates a fresh audit entry, timestamped now, with a freshly generated id.
     *
     * @param actorAuthUserId the acting account's id, or {@code null} if none could be identified
     * @param action which kind of action this entry records
     * @param targetId the id of whatever this action was performed against, or {@code null}
     * @param metadata short additional context, or {@code null} - redacted by {@link AuditLogService#record} before persistence
     */
    public AuditEvent(@Nullable final String actorAuthUserId, @NotNull final AuditAction action,
                       @Nullable final String targetId, @Nullable final String metadata) {
        this(UUID.randomUUID().toString(), actorAuthUserId, action, targetId, System.currentTimeMillis(), metadata);
    }

    /** Full constructor, used internally by {@link AuditLogService#record}'s redaction step to produce a copy with {@link #metadata} replaced. */
    public AuditEvent(@NotNull final String id, @Nullable final String actorAuthUserId, @NotNull final AuditAction action,
                       @Nullable final String targetId, final long timestampEpochMillis, @Nullable final String metadata) {
        this.id = id;
        this.actorAuthUserId = actorAuthUserId;
        this.action = action;
        this.targetId = targetId;
        this.timestampEpochMillis = timestampEpochMillis;
        this.metadata = metadata;
    }

    /**
     * Returns a copy of this entry with {@link #metadata} replaced - used by {@link
     * AuditLogService}'s implementation to persist a redacted copy rather than the raw value
     * passed to the constructor. The immutable "return a new instance" convention this codebase's
     * other entities ({@code Folder#renamedTo}/{@code RefreshToken#revoked}) already use.
     *
     * @param redactedMetadata the already-redacted replacement for {@link #metadata}
     * @return a copy of this entry with {@link #metadata} replaced
     */
    @NotNull
    public AuditEvent withMetadata(@Nullable final String redactedMetadata) {
        return new AuditEvent(this.id, this.actorAuthUserId, this.action, this.targetId, this.timestampEpochMillis, redactedMetadata);
    }

    /** @return this entity's primary key, {@link #id} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

}
