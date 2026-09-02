package de.lino.cloud.api.metrics;

import de.lino.cloud.api.factory.service.IServiceContainer;

/**
 * Item 13 (Metrics/observability exporter, {@code architecture/SERVICES.md}) - the vendor-agnostic
 * sink a small number of existing call sites push event-style counts through, once one has actually
 * been published into {@link IServiceContainer}. Lives in {@code cloud-driver-api} (not {@code
 * cloud-driver-plugin}/a new {@code cloud-driver-extensions-metrics} module) for the same reason
 * {@link de.lino.cloud.api.push.LiveUpdatePublisher}/{@link de.lino.cloud.api.audit.AuditLogService}
 * do: the call sites that need to push a count ({@code DefaultFileFactory#upload} in {@code
 * cloud-driver-plugin}, {@code CloudUserService#uploadFile} in {@code cloud-driver-auth}) cannot
 * depend on the extension that actually implements this, or on Micrometer at all.
 *
 * <p><b>Only for event-style counts, not gauges.</b> A few of the numbers item 13 asks for (queue
 * depth, per-{@code ExtensionStatus} counts) are naturally poll-based rather than push-based - the
 * metrics extension reads those directly off {@code CloudDriver} (e.g. {@code
 * DefaultFileFactory#getPendingUploadCache()#size()}, {@code ExtensionFactory#getExtensions()})
 * each time Prometheus scrapes, via a Micrometer {@code Gauge} backed by a live supplier, rather
 * than through this interface - there is no "current depth" method here on purpose, since nothing
 * upstream of the extension itself needs to know it's being measured.
 *
 * <p>{@code cloud-driver-extensions-metrics}'s {@code CloudMetricsExtension} publishes the real,
 * Micrometer-backed implementation into {@link IServiceContainer#setMetricsRecorder} once it starts;
 * until then {@link IServiceContainer#getMetricsRecorder()} returns {@code null} and every call site
 * below simply skips the record, the same "may not exist yet, null-check rather than assume"
 * contract {@code getCloudUserService()}/{@code getAuthService()}/{@code getLiveUpdatePublisher()}/
 * {@code getAuditLogService()} already carry.
 *
 * <p><b>Must never throw.</b> A metrics backend hiccup must never fail the real upload/rejection it
 * was only trying to count - the same guarantee {@link de.lino.cloud.api.audit.AuditLogService#record}
 * and {@link de.lino.cloud.api.push.LiveUpdatePublisher#publish} already make, enforced once here in
 * the implementation rather than trusting every call site to wrap its own call in a try/catch.
 */
public interface MetricsRecorder {

    /** Records one file successfully persisted by {@code DefaultFileFactory#upload} (online path, no retry needed). */
    void recordUploadSuccess();

    /**
     * Records one file that failed to persist and was <b>not</b> deferred to the pending-upload
     * queue - i.e. {@code DefaultFileFactory#upload} rethrew a {@code DatabaseClientException}
     * rather than queueing it, because connectivity was still reported available at the time of
     * failure (a genuine database problem, not an offline blip).
     */
    void recordUploadFailure();

    /**
     * Records one file deferred into the pending-upload queue instead of being persisted
     * immediately - either because connectivity was unavailable up front, or because a persist
     * attempt failed and connectivity had since dropped. Not itself a failure from the uploading
     * caller's perspective ({@code DefaultFileFactory#upload} returns normally either way) - see
     * {@code CLAUDE.md}'s "`connectivity` and pending-upload resilience" section for the full
     * offline-queueing contract this is counting instances of.
     */
    void recordUploadQueued();

    /** Records one upload rejected by {@code CloudUserService#uploadFile} for exceeding the uploading account's configured quota. */
    void recordUploadQuotaRejected();

}
