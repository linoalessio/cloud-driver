package de.lino.cloud.api.factory.service;

import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.backup.BackupService;
import de.lino.cloud.api.intelligence.IntelligenceService;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.metrics.MetricsRecorder;
import de.lino.cloud.api.metrics.MetricsSnapshotProvider;
import de.lino.cloud.api.push.LiveUpdatePublisher;
import de.lino.cloud.api.ratelimit.RateLimitAdmin;
import de.lino.cloud.api.scan.ContentScanService;
import de.lino.cloud.api.search.SearchIndexService;
import de.lino.cloud.api.thumbnail.ThumbnailService;
import de.lino.cloud.api.webhook.WebhookService;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.versioning.FileVersioningService;
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
     * Returns the live-update push transport (live push via WebSocket), or {@code null} if {@code CloudRestExtension} hasn't published
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
     * Returns the audit-log service, or {@code null} if {@code CloudRestExtension} hasn't published one yet (not started, the
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
     * Returns the metrics sink, or {@code null} if {@code cloud-driver-extensions-metrics}'s
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
     * Returns the metrics read side, or {@code null} if {@code cloud-driver-extensions-metrics}'s
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
     * Returns the thumbnail lookup service, or {@code null} if {@code cloud-driver-extensions-thumbnails}'s
     * {@code CloudThumbnailsExtension} hasn't published one yet (not started, or this deployment
     * doesn't run that extension at all). {@code DefaultRestFactory}'s {@code GET
     * /files/{id}/thumbnail} route must null-check this the same way it already does for {@link
     * #getMetricsSnapshotProvider()}.
     *
     * @return the {@link ThumbnailService}, or {@code null}
     */
    ThumbnailService getThumbnailService();

    /**
     * Publishes the real {@link ThumbnailService}, once built.
     *
     * @param thumbnailService the instance backing {@code GET /files/{id}/thumbnail}
     */
    void setThumbnailService(@NonNull ThumbnailService thumbnailService);

    /**
     * Returns the file-versioning service, or {@code null} if {@code cloud-driver-extensions-versioning}'s
     * {@code CloudVersioningExtension} hasn't published one yet (not started, or this deployment
     * doesn't run that extension at all). {@code de.lino.cloud.auth.CloudUserService#replaceFileContent}
     * and {@code DefaultRestFactory}'s {@code /files/{id}/versions*} routes must null-check this
     * the same way they already do for {@link #getMetricsSnapshotProvider()}.
     *
     * @return the {@link FileVersioningService}, or {@code null}
     */
    FileVersioningService getFileVersioningService();

    /**
     * Publishes the real {@link FileVersioningService}, once built.
     *
     * @param fileVersioningService the instance backing content-replacement version capture and {@code GET /files/{id}/versions}
     */
    void setFileVersioningService(@NonNull FileVersioningService fileVersioningService);

    /**
     * Returns the search index, or
     * {@code null} if {@code cloud-driver-extensions-search}'s {@code CloudSearchExtension} hasn't
     * published one yet (not started, or this deployment doesn't run that extension at all). {@code
     * de.lino.cloud.auth.CloudUserService}'s upload/rename/move/delete/restore/content-replace
     * methods and {@code DefaultRestFactory}'s {@code GET /search} route must null-check this the
     * same way they already do for {@link #getFileVersioningService()}.
     *
     * @return the {@link SearchIndexService}, or {@code null}
     */
    SearchIndexService getSearchIndexService();

    /**
     * Publishes the real {@link SearchIndexService}, once built.
     *
     * @param searchIndexService the instance backing {@code GET /search} and every file mutation's indexing hook
     */
    void setSearchIndexService(@NonNull SearchIndexService searchIndexService);

    /**
     * Returns the webhook dispatcher, or
     * {@code null} if {@code cloud-driver-extensions-webhooks}'s {@code CloudWebhooksExtension}
     * hasn't published one yet (not started, or this deployment doesn't run that extension at
     * all). {@code de.lino.cloud.auth.CloudUserService}'s upload/delete/share methods and {@code
     * DefaultRestFactory}'s {@code /webhooks*} routes must null-check this the same way they
     * already do for {@link #getSearchIndexService()}.
     *
     * @return the {@link WebhookService}, or {@code null}
     */
    WebhookService getWebhookService();

    /**
     * Publishes the real {@link WebhookService}, once built.
     *
     * @param webhookService the instance backing {@code /webhooks*} and every file mutation's dispatch hook
     */
    void setWebhookService(@NonNull WebhookService webhookService);

    /**
     * Returns the content-scan trigger, or {@code null} if {@code cloud-driver-extensions-scan}'s {@code
     * CloudScanExtension} hasn't published one yet (not started, or this deployment doesn't run
     * that extension at all). {@code de.lino.cloud.auth.CloudUserService#uploadFile} and {@code
     * DefaultRestFactory}'s content-serving routes must null-check this the same way they already
     * do for {@link #getWebhookService()}.
     *
     * @return the {@link ContentScanService}, or {@code null}
     */
    ContentScanService getContentScanService();

    /**
     * Publishes the real {@link ContentScanService}, once built.
     *
     * @param contentScanService the instance backing the {@code FileChangeListener}-triggered scan-on-upload hook
     */
    void setContentScanService(@NonNull ContentScanService contentScanService);

    /**
     * Returns the semantic-search service, or {@code null} if {@code
     * cloud-driver-extensions-intelligence}'s {@code CloudIntelligenceExtension} hasn't published
     * one yet (not started, or this deployment doesn't run that extension at all). {@code
     * de.lino.cloud.auth.CloudUserService}'s own indexing hooks and {@code
     * DefaultRestFactory}'s semantic-search route must null-check this the same way they already
     * do for {@link #getContentScanService()}.
     *
     * @return the {@link IntelligenceService}, or {@code null}
     */
    IntelligenceService getIntelligenceService();

    /**
     * Publishes the real {@link IntelligenceService}, once built.
     *
     * @param intelligenceService the bridge to this deployment's {@code cloud-driver-intelligence} Python service
     */
    void setIntelligenceService(@NonNull IntelligenceService intelligenceService);

    /**
     * Returns the {@link EmailSender} this deployment actually resolved at startup, or {@code null}
     * if {@code CloudRestExtension} hasn't published one yet.
     *
     * <p>Published specifically so an operator can verify mail delivery without registering a real
     * account to trigger it. Which sender was chosen is a silent, three-way fallback (SES, then
     * SMTP, then a {@code LoggingEmailSender} that delivers nothing at all), decided once at
     * startup - so a deployment can look entirely healthy while every verification e-mail it
     * "sends" is being written to a log. That failure mode has already cost this project a
     * production incident, where every registration to an unverified address failed with a bare
     * {@code 500} whose real cause was only visible in a stack trace.
     *
     * @return the {@link EmailSender}, or {@code null}
     */
    EmailSender getEmailSender();

    /**
     * Publishes the real {@link EmailSender}, once resolved.
     *
     * @param emailSender the sender {@code AuthService} delivers verification codes through
     */
    void setEmailSender(@NonNull EmailSender emailSender);

    /**
     * Returns operator control over the running REST layer's rate limiters, or {@code null} if
     * {@code CloudRestExtension} hasn't published one yet. See {@link RateLimitAdmin}'s own Javadoc
     * for why this is a separate published facet rather than something reachable off {@code
     * IFactoryContainer#getRestFactory()}.
     *
     * @return the {@link RateLimitAdmin}, or {@code null}
     */
    RateLimitAdmin getRateLimitAdmin();

    /**
     * Publishes the real {@link RateLimitAdmin}, once the JWT-gated {@code RestFactory} is built.
     *
     * @param rateLimitAdmin the running REST layer's own limiter control
     */
    void setRateLimitAdmin(@NonNull RateLimitAdmin rateLimitAdmin);

    /**
     * Returns on-demand access to the database backup job, or {@code null} if {@code
     * cloud-driver-extensions-backup}'s {@code CloudBackupExtension} hasn't published one yet.
     *
     * @return the {@link BackupService}, or {@code null}
     */
    BackupService getBackupService();

    /**
     * Publishes the real {@link BackupService}, once built.
     *
     * @param backupService the instance wrapping this deployment's backup scheduler
     */
    void setBackupService(@NonNull BackupService backupService);

}
