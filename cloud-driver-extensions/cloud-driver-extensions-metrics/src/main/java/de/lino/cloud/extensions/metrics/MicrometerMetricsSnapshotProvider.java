package de.lino.cloud.extensions.metrics;

import de.lino.cloud.api.extension.info.ExtensionStatus;
import de.lino.cloud.api.metrics.MetricsSnapshot;
import de.lino.cloud.api.metrics.MetricsSnapshotProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one {@link MetricsSnapshotProvider} implementation - reads every counter/gauge {@link
 * CloudMetricsExtension} registers, fresh, straight off the shared {@link MeterRegistry} it also
 * scrapes for {@code GET /metrics}. Constructed once, at {@link CloudMetricsExtension#onLoading()},
 * right after every gauge/counter it reads here has already been registered against that same
 * registry - so there's no ordering hazard between this class's own construction and the meters it
 * looks up existing yet.
 *
 * <p>Looked up via {@link MeterRegistry#find}, not {@link MeterRegistry#get} - {@code find} returns
 * {@code null} for an unregistered name/tag combination rather than throwing {@code
 * MeterNotFoundException}, since {@link #uploadCount}/{@link #counterValue} exist purely at zero
 * until their first event fires (a fresh deployment with no uploads yet is a normal state, not an
 * error) and {@link #gaugeValue}'s pending-upload-queue gauge is only ever registered for a real
 * {@code DefaultFileFactory} deployment in the first place.
 */
final class MicrometerMetricsSnapshotProvider implements MetricsSnapshotProvider {

    private final MeterRegistry registry;

    /** @param registry the shared {@link MeterRegistry} {@link CloudMetricsExtension} scrapes */
    MicrometerMetricsSnapshotProvider(@NotNull final MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    @NotNull
    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
                this.uploadCount("success"),
                this.uploadCount("failure"),
                this.uploadCount("queued"),
                this.counterValue("cloud_driver_upload_quota_rejections_total"),
                this.gaugeValue("cloud_driver_pending_upload_queue_depth"),
                this.extensionsByStatus()
        );
    }

    /** Reads one {@code outcome}-tagged series of {@code cloud_driver_uploads_total} - {@code 0} if it hasn't fired yet. */
    private long uploadCount(@NotNull final String outcome) {
        final Counter counter = this.registry.find("cloud_driver_uploads_total").tag("outcome", outcome).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    /** Reads a plain (untagged) counter by name - {@code 0} if it hasn't fired yet. */
    private long counterValue(@NotNull final String name) {
        final Counter counter = this.registry.find(name).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    /** Reads a plain (untagged) gauge by name - {@code 0} if it was never registered. */
    private long gaugeValue(@NotNull final String name) {
        final Gauge gauge = this.registry.find(name).gauge();
        return gauge == null ? 0L : (long) gauge.value();
    }

    /** Reads every {@code status}-tagged series of {@code cloud_driver_extensions}, keyed by {@link ExtensionStatus} name. */
    private Map<String, Long> extensionsByStatus() {
        final Map<String, Long> result = new LinkedHashMap<>();
        for (final ExtensionStatus status : ExtensionStatus.values()) {
            final Gauge gauge = this.registry.find("cloud_driver_extensions").tag("status", status.name()).gauge();
            result.put(status.name(), gauge == null ? 0L : (long) gauge.value());
        }
        return result;
    }

}
