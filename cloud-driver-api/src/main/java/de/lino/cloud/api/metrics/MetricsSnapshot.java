package de.lino.cloud.api.metrics;

import java.util.Map;

/**
 * A point-in-time read of every metric {@code cloud-driver-extensions-metrics} exposes over its
 * Prometheus {@code GET /metrics} scrape - returned by {@link MetricsSnapshotProvider#getSnapshot()}
 * and, from there, served by {@code DefaultRestFactory}'s admin-gated {@code GET /admin/metrics}
 * route so the desktop app's Admin panel can render these numbers without needing network access
 * to the separate, loopback-only Prometheus port ({@code cloud-driver-extensions-metrics}'s own
 * {@code MetricsHttpServer}) at all - this snapshot is read in-process, off the very same {@code
 * PrometheusMeterRegistry} that endpoint scrapes, never over an extra HTTP hop.
 *
 * @param uploadsSucceeded        {@code cloud_driver_uploads_total{outcome="success"}}
 * @param uploadsFailed           {@code cloud_driver_uploads_total{outcome="failure"}}
 * @param uploadsQueued           {@code cloud_driver_uploads_total{outcome="queued"}}
 * @param uploadQuotaRejections   {@code cloud_driver_upload_quota_rejections_total}
 * @param pendingUploadQueueDepth {@code cloud_driver_pending_upload_queue_depth} - {@code 0} if this
 *     deployment's {@code FileFactory} isn't a {@code DefaultFileFactory} and the gauge was
 *     therefore never registered, same value a real, empty queue would report
 * @param extensionsByStatus      {@code cloud_driver_extensions{status=...}}, keyed by {@code
 *     ExtensionStatus} name (e.g. {@code "RUNNING"}, {@code "ERROR"})
 */
public record MetricsSnapshot(
        long uploadsSucceeded,
        long uploadsFailed,
        long uploadsQueued,
        long uploadQuotaRejections,
        long pendingUploadQueueDepth,
        Map<String, Long> extensionsByStatus
) {
}
