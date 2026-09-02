package de.lino.cloud.plugin.factory.container;

import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.metrics.MetricsRecorder;
import de.lino.cloud.api.push.LiveUpdatePublisher;
import de.lino.cloud.api.user.ICloudUserService;
import lombok.NonNull;

/**
 * Starts out empty - {@code cloudUserService}/{@code authService} are only ever
 * published later, by {@code cloud-driver-extensions-rest}'s {@code CloudRestExtension},
 * once it has actually built the JWT-authenticated {@code RestFactory} those two are
 * backing. See {@link IServiceContainer}'s Javadoc for why this container can never be
 * built eagerly from the {@code CloudDriver}-level {@code RestFactory} (that one is
 * deliberately unauthenticated and never carries real {@code AuthService}/{@code
 * CloudUserService} instances).
 */
public class ServiceContainer implements IServiceContainer {

    /** The end-user file-ownership service, {@code null} until {@link #setCloudUserService} publishes one. */
    private volatile ICloudUserService cloudUserService;
    /** The login/JWT service, {@code null} until {@link #setAuthService} publishes one. */
    private volatile IAuthService authService;
    /** The live-update push transport, {@code null} until {@link #setLiveUpdatePublisher} publishes one. */
    private volatile LiveUpdatePublisher liveUpdatePublisher;
    /** The audit-log service, {@code null} until {@link #setAuditLogService} publishes one. */
    private volatile AuditLogService auditLogService;
    /** The metrics sink, {@code null} until {@link #setMetricsRecorder} publishes one. */
    private volatile MetricsRecorder metricsRecorder;

    /** {@inheritDoc} */
    @Override
    public ICloudUserService getCloudUserService() {
        return this.cloudUserService;
    }

    /** {@inheritDoc} */
    @Override
    public void setCloudUserService(@NonNull final ICloudUserService cloudUserService) {
        this.cloudUserService = cloudUserService;
    }

    /** {@inheritDoc} */
    @Override
    public IAuthService getAuthService() {
        return this.authService;
    }

    /** {@inheritDoc} */
    @Override
    public void setAuthService(@NonNull final IAuthService authService) {
        this.authService = authService;
    }

    /** {@inheritDoc} */
    @Override
    public LiveUpdatePublisher getLiveUpdatePublisher() {
        return this.liveUpdatePublisher;
    }

    /** {@inheritDoc} */
    @Override
    public void setLiveUpdatePublisher(@NonNull final LiveUpdatePublisher liveUpdatePublisher) {
        this.liveUpdatePublisher = liveUpdatePublisher;
    }

    /** {@inheritDoc} */
    @Override
    public AuditLogService getAuditLogService() {
        return this.auditLogService;
    }

    /** {@inheritDoc} */
    @Override
    public void setAuditLogService(@NonNull final AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** {@inheritDoc} */
    @Override
    public MetricsRecorder getMetricsRecorder() {
        return this.metricsRecorder;
    }

    /** {@inheritDoc} */
    @Override
    public void setMetricsRecorder(@NonNull final MetricsRecorder metricsRecorder) {
        this.metricsRecorder = metricsRecorder;
    }

}
