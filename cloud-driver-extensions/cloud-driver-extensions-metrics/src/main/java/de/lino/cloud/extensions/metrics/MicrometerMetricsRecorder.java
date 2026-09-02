package de.lino.cloud.extensions.metrics;

import de.lino.cloud.api.metrics.MetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jetbrains.annotations.NotNull;

/**
 * The one real {@link MetricsRecorder} implementation - four {@link Counter}s registered against
 * the shared {@link MeterRegistry} {@link CloudMetricsExtension} builds/scrapes. Constructed once,
 * at {@link CloudMetricsExtension#onLoading()}, and published into {@code
 * IServiceContainer#setMetricsRecorder} immediately afterward - every counter therefore already
 * exists (at zero) from the extension's very first scrape onward, rather than only appearing in
 * {@code /metrics} once its first event happens to fire (Prometheus/Grafana both handle an
 * always-present zero-valued counter better than one that silently doesn't exist yet).
 */
final class MicrometerMetricsRecorder implements MetricsRecorder {

    private final Counter uploadSuccessCounter;
    private final Counter uploadFailureCounter;
    private final Counter uploadQueuedCounter;
    private final Counter uploadQuotaRejectedCounter;

    /**
     * Registers this recorder's four counters against {@code registry}.
     *
     * @param registry the shared {@link MeterRegistry} {@link CloudMetricsExtension} scrapes
     */
    MicrometerMetricsRecorder(@NotNull final MeterRegistry registry) {
        this.uploadSuccessCounter = Counter.builder("cloud_driver_uploads_total")
                .description("Files persisted by DefaultFileFactory#upload")
                .tag("outcome", "success")
                .register(registry);
        this.uploadFailureCounter = Counter.builder("cloud_driver_uploads_total")
                .description("Files persisted by DefaultFileFactory#upload")
                .tag("outcome", "failure")
                .register(registry);
        this.uploadQueuedCounter = Counter.builder("cloud_driver_uploads_total")
                .description("Files persisted by DefaultFileFactory#upload")
                .tag("outcome", "queued")
                .register(registry);
        this.uploadQuotaRejectedCounter = Counter.builder("cloud_driver_upload_quota_rejections_total")
                .description("Uploads rejected by CloudUserService#uploadFile for exceeding the uploading account's quota")
                .register(registry);
    }

    /** {@inheritDoc} */
    @Override
    public void recordUploadSuccess() {
        this.uploadSuccessCounter.increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordUploadFailure() {
        this.uploadFailureCounter.increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordUploadQueued() {
        this.uploadQueuedCounter.increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordUploadQuotaRejected() {
        this.uploadQuotaRejectedCounter.increment();
    }

}
