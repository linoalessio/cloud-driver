package de.lino.cloud.api.metrics;

import de.lino.cloud.api.factory.service.IServiceContainer;
import lombok.NonNull;

/**
 * The read side of item 13 (Metrics/observability exporter) - the counterpart to {@link
 * MetricsRecorder} (the write side). Published into {@link IServiceContainer#setMetricsSnapshotProvider}
 * by {@code cloud-driver-extensions-metrics}'s {@code CloudMetricsExtension} alongside the {@link
 * MetricsRecorder} it already publishes, backed by the exact same {@code PrometheusMeterRegistry} -
 * so a caller reading a snapshot here always sees the same numbers the Prometheus {@code GET
 * /metrics} scrape would report, without an extra HTTP hop to that separate, loopback-only port.
 * {@code null} until that extension has actually run, the same "may not exist yet" contract every
 * other {@link IServiceContainer} facet already carries - {@code DefaultRestFactory}'s admin-gated
 * {@code GET /admin/metrics} route null-checks this and responds accordingly rather than assuming
 * non-null.
 */
public interface MetricsSnapshotProvider {

    /**
     * Reads every currently-registered metric fresh off the backing registry - cheap, synchronous,
     * safe to call on every request (the same "evaluated at scrape time, no caching" contract the
     * Prometheus endpoint itself already has for its gauges).
     *
     * @return the current {@link MetricsSnapshot}
     */
    @NonNull MetricsSnapshot getSnapshot();

}
