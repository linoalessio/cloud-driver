package de.lino.cloud.auth.audit;

import de.lino.cloud.api.audit.AuditEvent;
import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.terminal.logging.TerminalLogHandler;
import lombok.NonNull;

import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The only {@link AuditLogService} implementation: persists an {@link AuditEvent} via {@link
 * DataFactory#register} - envelope-encrypted at rest, exactly like any other {@code Serialized}
 * entity.
 *
 * <p><b>Redaction without a {@code cloud-driver-plugin} dependency.</b> {@code
 * cloud-driver-auth} must never depend on {@code cloud-driver-plugin} (see {@code CLAUDE.md}'s
 * "Module layout and dependency direction"), so this class cannot call {@code
 * de.lino.cloud.plugin.security.secrets.SecretRedactor} directly the way most other logged text
 * in this codebase does. Instead, the caller building this instance (today, only {@code
 * cloud-driver-extensions-rest}'s {@code CloudRestExtension}, which already depends on both
 * modules) injects a plain {@link UnaryOperator}&lt;{@link String}&gt; redaction function -
 * {@code SecretRedactor::redact} in production - applied to {@link AuditEvent#getMetadata()}
 * before persistence.
 *
 * <p><b>Never throws.</b> {@link #record(AuditEvent)} catches and logs any persistence failure
 * rather than propagating it - see {@link AuditLogService#record(AuditEvent)}'s own Javadoc for
 * why: losing one audit entry must never be allowed to fail the real action being audited, and
 * enforcing that guarantee once, here, is safer than trusting every one of {@code
 * AuthService}/{@code CloudUserService}'s several call sites to each wrap their own call in a
 * try/catch.
 */
public final class AuditLogServiceImpl implements AuditLogService {

    private static final Logger LOGGER = Logger.getLogger(AuditLogServiceImpl.class.getName());

    /** Persists {@link AuditEvent} rows. */
    private final DataFactory dataFactory;

    /** Applied to {@link AuditEvent#getMetadata()} before persistence - see this class's own Javadoc. */
    private final UnaryOperator<String> metadataRedactor;

    /**
     * Creates an {@code AuditLogServiceImpl} backed by the given collaborators.
     *
     * @param dataFactory persists {@link AuditEvent} rows
     * @param metadataRedactor applied to a non-{@code null} {@link AuditEvent#getMetadata()} before persistence
     */
    public AuditLogServiceImpl(@NonNull final DataFactory dataFactory, @NonNull final UnaryOperator<String> metadataRedactor) {
        this.dataFactory = dataFactory;
        this.metadataRedactor = metadataRedactor;
    }

    /** {@inheritDoc} */
    @Override
    public void record(@NonNull final AuditEvent event) {
        final AuditEvent toStore = event.getMetadata() == null
                ? event
                : event.withMetadata(this.metadataRedactor.apply(event.getMetadata()));
        try {
            this.dataFactory.register(toStore);
        } catch (final DatabaseClientException | KeyWrapException e) {
            LOGGER.log(Level.WARNING, "@AuditLogServiceImpl.record: failed to persist audit entry for action "
                    + event.getAction() + " - proceeding without it", e);
        }
    }

}
