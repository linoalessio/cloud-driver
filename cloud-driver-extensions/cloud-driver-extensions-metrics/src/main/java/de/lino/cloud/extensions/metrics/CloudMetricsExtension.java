package de.lino.cloud.extensions.metrics;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionStatus;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.database.json.JsonDocument;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.logging.Level;

/**
 * Item 13 (Metrics/observability exporter, {@code architecture/SERVICES.md}) - registers a
 * {@link PrometheusMeterRegistry}, wires it to both push-style events ({@link
 * MicrometerMetricsRecorder}, published into {@code IServiceContainer#setMetricsRecorder} for
 * {@code DefaultFileFactory#upload}/{@code CloudUserService#uploadFile} to push counts through -
 * see {@link de.lino.cloud.api.metrics.MetricsRecorder}'s own Javadoc for why those two call sites
 * need a push rather than a poll) and poll-style gauges (pending-upload queue depth, one gauge per
 * {@link ExtensionStatus}, read fresh off {@link de.lino.cloud.api.CloudDriver} on every scrape -
 * no separate polling thread needed, since a Micrometer {@link Gauge} backed by a live supplier is
 * evaluated by the scrape itself), and serves the result over {@link MetricsHttpServer}.
 *
 * <p>Also publishes a {@link MicrometerMetricsSnapshotProvider} into {@code
 * IServiceContainer#setMetricsSnapshotProvider} - the in-process read side {@code
 * DefaultRestFactory}'s admin-gated {@code GET /admin/metrics} route uses to serve these same
 * numbers to the desktop app's Admin panel, without that route needing its own HTTP client to
 * reach this extension's separate, loopback-only {@link MetricsHttpServer} port.
 *
 * <p>Declares a dependency on {@code "cloud-driver-bootstrap"} only in its {@code extension.json} -
 * deliberately not {@code "cloud-driver-rest"}/{@code "cloud-driver-watcher"}, so this extension
 * (and therefore the metrics it exposes) comes up regardless of whether either of those does.
 */
public class CloudMetricsExtension extends Extension {

    /** {@code configuration.json} key for {@link MetricsHttpServer}'s listen port - defaults to {@link #DEFAULT_METRICS_PORT} if unset. */
    private static final String METRICS_PORT_CONFIG_KEY = "metrics-port";

    /** Default value for {@link #METRICS_PORT_CONFIG_KEY} - Prometheus' own conventional "misc exporter" range, not already used by {@code rest-server-port}. */
    private static final int DEFAULT_METRICS_PORT = 9404;

    /**
     * {@code configuration.json} key for {@link MetricsHttpServer}'s bind interface - defaults to
     * {@link #DEFAULT_METRICS_BIND_HOST} if unset. See {@link MetricsHttpServer}'s own Javadoc for
     * why this endpoint is unauthenticated and therefore relies on network placement (this key,
     * plus firewalling/a reverse-proxy IP allowlist) as its actual access control.
     */
    private static final String METRICS_BIND_HOST_CONFIG_KEY = "metrics-bind-host";

    /**
     * Default bind interface if {@link #METRICS_BIND_HOST_CONFIG_KEY} isn't set -
     * loopback-only, unlike {@code CloudRestExtension}'s own {@code "0.0.0.0"} default, since this
     * endpoint (unlike the JWT-gated REST API) has no authentication of its own to fall back on. A
     * deployment that scrapes from a different host must explicitly opt into a wider bind host (or
     * front this port with a reverse proxy) rather than being exposed to the network by default.
     */
    private static final String DEFAULT_METRICS_BIND_HOST = "127.0.0.1";

    private PrometheusMeterRegistry registry;
    private MetricsHttpServer httpServer;
    private int metricsPort;

    /**
     * Builds the {@link PrometheusMeterRegistry}, registers every gauge, publishes the {@link
     * MicrometerMetricsRecorder} into {@code IServiceContainer#setMetricsRecorder} and the {@link
     * MicrometerMetricsSnapshotProvider} into {@code IServiceContainer#setMetricsSnapshotProvider},
     * and starts {@link MetricsHttpServer} listening.
     */
    @Override
    public void onLoading() {

        final JsonDocument configuration = this.cloudDriver().getConfiguration();
        this.metricsPort = configuration.contains(METRICS_PORT_CONFIG_KEY)
                ? configuration.getInteger(METRICS_PORT_CONFIG_KEY)
                : DEFAULT_METRICS_PORT;
        final String bindHost = configuration.contains(METRICS_BIND_HOST_CONFIG_KEY)
                ? configuration.getString(METRICS_BIND_HOST_CONFIG_KEY)
                : DEFAULT_METRICS_BIND_HOST;

        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.registerPendingUploadQueueDepthGauge();
        this.registerExtensionStatusGauges();

        this.cloudDriver().getServiceContainer().setMetricsRecorder(new MicrometerMetricsRecorder(this.registry));
        this.cloudDriver().getServiceContainer().setMetricsSnapshotProvider(new MicrometerMetricsSnapshotProvider(this.registry));

        this.httpServer = new MetricsHttpServer(this.registry);
        this.httpServer.start(bindHost, this.metricsPort);

    }

    /**
     * Registers a gauge reporting {@code DefaultFileFactory#getPendingUploadCache()#size()} -
     * how many files are currently waiting for connectivity to return. Only registers if the
     * configured {@code FileFactory} is actually a {@code DefaultFileFactory} (true for every
     * real deployment; guards against a future/alternate {@code FileFactory} implementation this
     * cast wouldn't apply to).
     */
    private void registerPendingUploadQueueDepthGauge() {
        final FileFactory fileFactory = this.cloudDriver().getFactoryContainer().getFileFactory();
        if (!(fileFactory instanceof DefaultFileFactory defaultFileFactory)) return;

        Gauge.builder("cloud_driver_pending_upload_queue_depth", defaultFileFactory,
                        factory -> factory.getPendingUploadCache().size())
                .description("Files currently queued in PendingUploadCache, awaiting connectivity to persist")
                .register(this.registry);
    }

    /**
     * Registers one gauge per {@link ExtensionStatus}, each counting how many currently-registered
     * extensions are in that status - read fresh off {@link ExtensionFactory#getExtensions()} on
     * every scrape, so this reflects a start/stop/crash without this extension having to observe
     * it itself.
     */
    private void registerExtensionStatusGauges() {
        final ExtensionFactory extensionFactory = this.cloudDriver().getFactoryContainer().getExtensionFactory();
        for (final ExtensionStatus status : ExtensionStatus.values()) {
            Gauge.builder("cloud_driver_extensions", extensionFactory,
                            factory -> factory.getExtensions().stream()
                                    .filter(extension -> extension.getExtensionProperties().getExtensionStatus() == status)
                                    .count())
                    .description("Currently registered extensions, by ExtensionStatus")
                    .tag("status", status.name())
                    .register(this.registry);
        }
    }

    /**
     * Prints a confirmation once {@link #onLoading()}'s {@link MetricsHttpServer} is listening.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {
        this.cloudDriver().getTerminal().displayApproved(
                "&dMetrics endpoint &bopened &7and listening on port &b&l%s &7(&b/metrics&7)", this.metricsPort);
    }

    /** Stops {@link MetricsHttpServer}, if it was ever started. */
    @Override
    public void onEnding() {
        if (this.httpServer != null) this.httpServer.stop();
    }

    /**
     * Stops {@link MetricsHttpServer}, if it was ever started, and logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(RuntimeException reason) {
        if (this.httpServer != null) this.httpServer.stop();
        this.cloudDriver().getLogger().severe("An error occurred while trying to start the cloud metrics extension.");
        this.cloudDriver().getLogger().log(Level.SEVERE, reason.getMessage(), reason);
    }

}
