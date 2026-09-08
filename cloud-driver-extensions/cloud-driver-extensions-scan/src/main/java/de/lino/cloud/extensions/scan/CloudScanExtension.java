package de.lino.cloud.extensions.scan;

import de.lino.cloud.api.event.database.FileChangeListener;
import de.lino.cloud.api.extension.Extension;
import de.lino.database.json.JsonDocument;

import java.time.Duration;
import java.util.logging.Level;

/**
 * Content scanning - publishes a {@link
 * DefaultContentScanService} into {@code IServiceContainer#setContentScanService} and registers a
 * {@link FileChangeListener} that triggers a scan on every newly-uploaded {@link
 * de.lino.cloud.api.file.StoredFile}. See {@code ContentScanService}'s own Javadoc for the full
 * design, including why this is triggered via {@link FileChangeListener} rather than a synchronous
 * hook, and the confirmed-with-Lino ClamAV/{@code clamd} engine choice.
 *
 * <p>Built as an in-process extension, not a genuinely standalone deployable service, for the same
 * reason {@code cloud-driver-extensions-thumbnails} was. Reacts to <b>every</b> {@code "INSERT"}
 * notification, unlike {@code
 * CloudThumbnailsExtension} (which only reacts to image/PDF content types) - every uploaded file,
 * regardless of type, needs a scan verdict, not just the ones with a preview to generate.
 */
public class CloudScanExtension extends Extension {

    /** The only Postgres trigger operation this extension reacts to - a rename/move/trash never changes a file's actual bytes, so there's nothing new to scan. */
    private static final String INSERT_OPERATION = "INSERT";

    /** {@code configuration.json} key for the {@code clamd} host - defaults to {@link #DEFAULT_CLAMD_HOST} if unset. */
    private static final String CLAMD_HOST_CONFIG_KEY = "clamav-host";
    /** Default {@code clamd} host - the common "runs alongside this process" deployment shape. */
    private static final String DEFAULT_CLAMD_HOST = "localhost";
    /** {@code configuration.json} key for the {@code clamd} TCP port - defaults to {@link #DEFAULT_CLAMD_PORT} (clamd's own documented default) if unset. */
    private static final String CLAMD_PORT_CONFIG_KEY = "clamav-port";
    private static final int DEFAULT_CLAMD_PORT = 3310;
    /** {@code configuration.json} key for the per-scan socket timeout, in seconds - defaults to {@link #DEFAULT_CLAMD_TIMEOUT_SECONDS} if unset. */
    private static final String CLAMD_TIMEOUT_SECONDS_CONFIG_KEY = "clamav-timeout-seconds";
    private static final long DEFAULT_CLAMD_TIMEOUT_SECONDS = 30L;
    /** {@code configuration.json} key for the maximum content size this extension will submit to {@code clamd} at all - defaults to {@link #DEFAULT_MAX_SCANNABLE_BYTES} if unset. */
    private static final String MAX_SCANNABLE_BYTES_CONFIG_KEY = "content-scan-max-bytes";
    /** Default scan size cap - 100 MiB; a file larger than this is marked clean without scanning (see {@code DefaultContentScanService}'s own Javadoc) rather than risking clamd's own {@code StreamMaxLength} rejecting it as an error. */
    private static final long DEFAULT_MAX_SCANNABLE_BYTES = 100L * 1024 * 1024;

    private DefaultContentScanService contentScanService;
    private FileChangeListener listener;

    /**
     * Builds a {@link DefaultContentScanService} from this deployment's configured (or default)
     * {@code clamd} connection settings, publishes it into {@code
     * IServiceContainer#setContentScanService}, and registers {@link #listener} against {@code
     * FileChangeListenerRegistry}.
     */
    @Override
    public void onLoading() {

        final JsonDocument configuration = this.cloudDriver().getConfiguration();
        final String host = configuration.contains(CLAMD_HOST_CONFIG_KEY) ? configuration.getString(CLAMD_HOST_CONFIG_KEY) : DEFAULT_CLAMD_HOST;
        final int port = configuration.contains(CLAMD_PORT_CONFIG_KEY) ? configuration.getInteger(CLAMD_PORT_CONFIG_KEY) : DEFAULT_CLAMD_PORT;
        final long timeoutSeconds = configuration.contains(CLAMD_TIMEOUT_SECONDS_CONFIG_KEY)
                ? configuration.getLong(CLAMD_TIMEOUT_SECONDS_CONFIG_KEY) : DEFAULT_CLAMD_TIMEOUT_SECONDS;
        final long maxScannableBytes = configuration.contains(MAX_SCANNABLE_BYTES_CONFIG_KEY)
                ? configuration.getLong(MAX_SCANNABLE_BYTES_CONFIG_KEY) : DEFAULT_MAX_SCANNABLE_BYTES;

        this.contentScanService = new DefaultContentScanService(
                this.cloudDriver().getFactoryContainer().getDataFactory(),
                this.cloudDriver().getFactoryContainer().getFileFactory(),
                this.getLogger(), host, port, Duration.ofSeconds(timeoutSeconds), maxScannableBytes);
        this.cloudDriver().getServiceContainer().setContentScanService(this.contentScanService);

        this.listener = (storedFileId, operation) -> {
            if (!INSERT_OPERATION.equalsIgnoreCase(operation)) return;
            this.contentScanService.scanAsync(storedFileId);
        };
        this.cloudDriver().getFactoryContainer().getFileChangeListenerRegistry().register(this.listener);

    }

    /** Prints a confirmation once {@link #onLoading()} has registered {@link #listener}. */
    @Override
    public void onRunning(final String[] args) {
        this.cloudDriver().getTerminal().displayApproved("&dContent scanning &bready &7- watching for new uploads to scan via clamd");
    }

    /** Unregisters {@link #listener} and shuts {@link #contentScanService} down. */
    @Override
    public void onEnding() {
        this.shutdown();
    }

    /**
     * Unregisters {@link #listener} and shuts {@link #contentScanService} down, then logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(final RuntimeException reason) {
        this.shutdown();
        this.getLogger().log(Level.SEVERE, "An error occurred while running the content-scan extension.", reason);
    }

    private void shutdown() {
        if (this.listener != null) {
            this.cloudDriver().getFactoryContainer().getFileChangeListenerRegistry().unregister(this.listener);
        }
        if (this.contentScanService != null) {
            this.contentScanService.shutdown();
        }
    }

}
