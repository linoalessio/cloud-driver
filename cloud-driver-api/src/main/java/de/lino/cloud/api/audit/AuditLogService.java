package de.lino.cloud.api.audit;

import lombok.NonNull;

/**
 * Records a persisted, structured trail of security-relevant actions - see {@link AuditEvent}'s
 * own Javadoc for the schema and why it lives in {@code cloud-driver-api} alongside this
 * interface, and {@code CLAUDE.md}'s "Audit log service" section for the full picture (which
 * actions are recorded, from where, and how to read the trail back).
 *
 * <p>One method, deliberately: a caller builds a fully-formed {@link AuditEvent} itself (it knows
 * the actor/action/target far better than a generic logging facade could) and hands it here purely
 * to be persisted. Reached via {@link de.lino.cloud.api.factory.service.IServiceContainer#getAuditLogService()},
 * the same "published once the JWT-authenticated REST API actually starts, {@code null} until
 * then" convention {@link de.lino.cloud.api.jwt.auth.IAuthService}/{@link
 * de.lino.cloud.api.user.ICloudUserService} already use on that container.
 *
 * <p><b>A failure to record must never fail the action being audited.</b> Every call site
 * ({@code AuthService}/{@code CloudUserService}) treats this as a best-effort side effect, not a
 * precondition - see this interface's own implementation ({@code
 * de.lino.cloud.auth.audit.AuditLogServiceImpl}, {@code cloud-driver-auth}) for where that
 * guarantee is actually enforced (it never throws, logging any persistence failure instead), so a
 * call site can invoke {@link #record(AuditEvent)} directly without its own defensive try/catch.
 */
public interface AuditLogService {

    /**
     * Persists {@code event} - envelope-encrypted at rest like any other {@link
     * de.lino.database.database.entity.Serialized} entity. Never throws; a persistence failure is
     * logged by the implementation and otherwise swallowed, since losing one audit entry must
     * never be allowed to fail the real action it was recording.
     *
     * @param event the fully-formed entry to persist
     */
    void record(@NonNull AuditEvent event);

}
