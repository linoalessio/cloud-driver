package de.lino.cloud.api.factory.service;

import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.icloud.IcloudImportService;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.metrics.MetricsRecorder;
import de.lino.cloud.api.metrics.MetricsSnapshotProvider;
import de.lino.cloud.api.push.LiveUpdatePublisher;
import de.lino.cloud.api.user.ICloudUserService;
import lombok.NonNull;

/**
 * Bundles higher-level, cross-cutting services built on top of the raw
 * persistence facets in {@link de.lino.cloud.api.factory.container.IFactoryContainer} -
 * currently just the {@link ICloudUserService} used to scope end-user file
 * ownership over the REST API, and the {@link IAuthService} that verifies
 * logins/issues JWTs.
 *
 * <p>Unlike every other facet on {@link de.lino.cloud.api.CloudDriver}, these two
 * are not necessarily available the moment {@code CloudDriver} itself is
 * constructed: both are only ever built once the JWT-authenticated {@code RestFactory}
 * is - which happens later, inside {@code cloud-driver-extensions-rest}'s
 * {@code CloudRestExtension}, and only if a {@code "jwt-signing-key"} is configured
 * at all (see {@code CloudRestExtension#startRestApi}). {@link #setCloudUserService}/
 * {@link #setAuthService} are how that extension publishes its real instances back
 * here once they exist; {@link #getCloudUserService()}/{@link #getAuthService()}
 * return {@code null} until then - a caller reached before/without that extension
 * (e.g. a {@code Command} that can run concurrently with, or without ever depending
 * on, {@code cloud-driver-rest}) must handle that case rather than assume non-null.
 */
public interface IServiceContainer {

    /**
     * Returns the end-user file-ownership service, or {@code null} if {@code
     * CloudRestExtension} hasn't published one yet (not started, or the REST API
     * is disabled for this deployment).
     *
     * @return the {@link ICloudUserService}, or {@code null}
     */
    ICloudUserService getCloudUserService();

    /**
     * Publishes the real {@link ICloudUserService}, once built.
     *
     * @param cloudUserService the instance backing the JWT-authenticated REST API's {@code /files} routes
     */
    void setCloudUserService(@NonNull ICloudUserService cloudUserService);

    /**
     * Returns the login/JWT service, or {@code null} if {@code CloudRestExtension}
     * hasn't published one yet (not started, or the REST API is disabled for this
     * deployment).
     *
     * @return the {@link IAuthService}, or {@code null}
     */
    IAuthService getAuthService();

    /**
     * Publishes the real {@link IAuthService}, once built.
     *
     * @param authService the instance backing the JWT-authenticated REST API's login/registration routes
     */
    void setAuthService(@NonNull IAuthService authService);

    /**
     * Returns the live-update push transport (item 10, live push via WebSocket - see {@code
     * architecture/SERVICES.md}), or {@code null} if {@code CloudRestExtension} hasn't published
     * one yet (not started, the REST API is disabled for this deployment, or this deployment's
     * {@code cloud-driver-plugin} version predates this feature). {@link
     * de.lino.cloud.api.event.database.DatabaseWatchEvent#handle} must null-check this the same
     * way it already does for {@link #getCloudUserService()}.
     *
     * @return the {@link LiveUpdatePublisher}, or {@code null}
     */
    LiveUpdatePublisher getLiveUpdatePublisher();

    /**
     * Publishes the real {@link LiveUpdatePublisher}, once the WebSocket-backed {@code
     * RestFactory} it forwards to is actually running.
     *
     * @param liveUpdatePublisher the instance backing the JWT-authenticated REST API's WebSocket push route
     */
    void setLiveUpdatePublisher(@NonNull LiveUpdatePublisher liveUpdatePublisher);

    /**
     * Returns the audit-log service (item 11, audit log - see {@code architecture/SERVICES.md}),
     * or {@code null} if {@code CloudRestExtension} hasn't published one yet (not started, the
     * REST API is disabled for this deployment, or this deployment's {@code cloud-driver-plugin}
     * version predates this feature). A caller reached before/without that extension (e.g. a
     * terminal {@code Command}) must null-check this the same way it already does for {@link
     * #getCloudUserService()}.
     *
     * @return the {@link AuditLogService}, or {@code null}
     */
    AuditLogService getAuditLogService();

    /**
     * Publishes the real {@link AuditLogService}, once built.
     *
     * @param auditLogService the instance backing {@code AuthService}/{@code CloudUserService}'s audit trail
     */
    void setAuditLogService(@NonNull AuditLogService auditLogService);

    /**
     * Returns the metrics sink (item 13, metrics/observability exporter - see {@code
     * architecture/SERVICES.md}), or {@code null} if {@code cloud-driver-extensions-metrics}'s
     * {@code CloudMetricsExtension} hasn't published one yet (not started, or this deployment
     * doesn't run that extension at all). A caller reached before/without that extension (e.g.
     * {@code DefaultFileFactory#upload}, {@code CloudUserService#uploadFile}) must null-check
     * this the same way it already does for {@link #getCloudUserService()}.
     *
     * @return the {@link MetricsRecorder}, or {@code null}
     */
    MetricsRecorder getMetricsRecorder();

    /**
     * Publishes the real {@link MetricsRecorder}, once built.
     *
     * @param metricsRecorder the instance backing this deployment's Prometheus-scrapeable {@code /metrics} endpoint
     */
    void setMetricsRecorder(@NonNull MetricsRecorder metricsRecorder);

    /**
     * Returns the metrics read side (item 13, metrics/observability exporter - see {@code
     * architecture/SERVICES.md}), or {@code null} if {@code cloud-driver-extensions-metrics}'s
     * {@code CloudMetricsExtension} hasn't published one yet (not started, or this deployment
     * doesn't run that extension at all). {@code DefaultRestFactory}'s admin-gated {@code GET
     * /admin/metrics} route (backing the desktop app's Admin panel metrics section) must
     * null-check this the same way it already does for {@link #getCloudUserService()}.
     *
     * @return the {@link MetricsSnapshotProvider}, or {@code null}
     */
    MetricsSnapshotProvider getMetricsSnapshotProvider();

    /**
     * Publishes the real {@link MetricsSnapshotProvider}, once built.
     *
     * @param metricsSnapshotProvider the instance backing {@code GET /admin/metrics}
     */
    void setMetricsSnapshotProvider(@NonNull MetricsSnapshotProvider metricsSnapshotProvider);

    /**
     * Returns the on-demand "Sync from iCloud" import service, or {@code null} if {@code
     * CloudRestExtension} hasn't published one yet (not started, the REST API is disabled for this
     * deployment, or the host running this process has no {@code python3}/{@code pyicloud}
     * available - see {@code PythonIcloudBridge}'s own Javadoc, this feature disables itself rather
     * than failing to boot when that dependency is missing). {@code DefaultRestFactory}'s {@code
     * /icloud/import} routes must null-check this the same way they already do for {@link
     * #getCloudUserService()}.
     *
     * @return the {@link IcloudImportService}, or {@code null}
     */
    IcloudImportService getIcloudImportService();

    /**
     * Publishes the real {@link IcloudImportService}, once built.
     *
     * @param icloudImportService the instance backing the JWT-authenticated REST API's {@code /icloud/import} routes
     */
    void setIcloudImportService(@NonNull IcloudImportService icloudImportService);

}
