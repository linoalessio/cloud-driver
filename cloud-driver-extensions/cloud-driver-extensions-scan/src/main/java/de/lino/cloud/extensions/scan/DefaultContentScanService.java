package de.lino.cloud.extensions.scan;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.ScanStatus;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.scan.ContentScanService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.user.ICloudUserService;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one {@link ContentScanService} implementation - talks to {@code clamd} via {@link
 * ClamAvClient}. Runs entirely on {@link #executor} (the first attempt) / {@link #retryScheduler}
 * (retries), the same small-bounded-daemon-pool shape {@code CloudThumbnailsExtension}'s own
 * generation worker already established - never the Postgres notification thread {@link
 * #scanAsync} is ultimately triggered from.
 *
 * <p><b>Deduplication aliases are scanned independently, not resolved through their canonical
 * file's own result</b> - a deliberate simplicity-over-optimization trade-off: an alias's content
 * is always byte-identical to its canonical's (guaranteed by how a dedup match is found), so
 * re-scanning it always reproduces the same verdict - genuinely redundant work, but it keeps
 * {@link StoredFile#scanStatus()} independently accurate and directly readable on every entity, no
 * "resolve through the canonical" logic needed anywhere else in this codebase. Accepted because
 * dedup and scanning both being enabled, on identical content uploaded more than once, is expected
 * to be a rare combination in practice.
 *
 * <p><b>On a scan failure (connection refused, timeout, an unexpected {@code clamd} response) -
 * retried up to {@link #MAX_ATTEMPTS} times, then <em>fails open</em>: the file is marked {@link
 * ScanStatus#CLEAN} with a loud {@code WARNING} log, not left stuck at {@link ScanStatus#PENDING}
 * forever.</b> A deliberate, flagged trade-off, not a silent decision - the alternative
 * (fail-closed, leaving a file permanently inaccessible whenever {@code clamd} has any hiccup)
 * would turn a scanner outage into an upload-availability outage, which this codebase's own
 * "must keep working with every microservice turned off" constraint argues against; the cost is
 * that a scanner outage during upload silently disables real protection for that file. Revisit
 * with Lino if this deployment's threat model wants fail-closed instead.
 */
final class DefaultContentScanService implements ContentScanService {

    /** Total attempts (first attempt plus retries) before failing open - see this class's own Javadoc. */
    private static final int MAX_ATTEMPTS = 3;

    /** Delay before each retry, indexed by (attemptNumber - 1) - fixed, not exponential, matching {@code DefaultWebhookService}'s own precedent. */
    private static final Duration[] RETRY_DELAYS = {Duration.ofSeconds(3), Duration.ofSeconds(15)};

    /** Bounded pool size for {@link #executor} - matches {@code CloudThumbnailsExtension}'s own "do not over-engineer this in v1" sizing. */
    private static final int SCAN_EXECUTOR_THREADS = 2;

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final Logger logger;
    private final ClamAvClient clamAvClient;
    private final long maxScannableBytes;
    private final ExecutorService executor;
    private final ScheduledExecutorService retryScheduler;

    DefaultContentScanService(@NotNull final DataFactory dataFactory, @NotNull final FileFactory fileFactory,
                               @NotNull final Logger logger, @NotNull final String clamdHost, final int clamdPort,
                               @NotNull final Duration clamdTimeout, final long maxScannableBytes) {
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
        this.logger = logger;
        this.clamAvClient = new ClamAvClient(clamdHost, clamdPort, clamdTimeout);
        this.maxScannableBytes = maxScannableBytes;
        this.executor = Executors.newFixedThreadPool(SCAN_EXECUTOR_THREADS, daemonThreadFactory("content-scan"));
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("content-scan-retry"));
    }

    private static ThreadFactory daemonThreadFactory(final String namePrefix) {
        return runnable -> {
            final Thread thread = new Thread(runnable, namePrefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Shuts {@link #executor}/{@link #retryScheduler} down - called by {@code CloudScanExtension#onEnding}/{@code #onException}. */
    void shutdown() {
        this.executor.shutdown();
        this.retryScheduler.shutdown();
    }

    /** {@inheritDoc} */
    @Override
    public void scanAsync(@NotNull final String storedFileId) {
        try {
            this.executor.execute(() -> performScan(storedFileId, 1));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see ContentScanService's own Javadoc.
        }
    }

    private void performScan(final String storedFileId, final int attemptNumber) {
        final StoredFile file;
        try {
            file = this.fileFactory.findById(storedFileId).orElse(null);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException
                       | RuntimeException e) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to fetch " + storedFileId + " for scanning", e);
            scheduleRetryOrFailOpen(storedFileId, attemptNumber);
            return;
        }
        if (file == null) {
            return; // deleted/never existed by the time this ran - nothing to scan
        }

        final byte[] content;
        try {
            content = file.content();
        } catch (final RuntimeException contentUnresolvable) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to resolve content for " + storedFileId, contentUnresolvable);
            scheduleRetryOrFailOpen(storedFileId, attemptNumber);
            return;
        }

        if (content.length > this.maxScannableBytes) {
            this.logger.warning("@DefaultContentScanService: " + storedFileId + " (" + content.length
                    + " bytes) exceeds the configured scan size cap (" + this.maxScannableBytes + ") - marking clean without scanning");
            persistStatus(storedFileId, ScanStatus.CLEAN);
            return;
        }

        try {
            final ClamAvScanResult result = this.clamAvClient.scan(content);
            persistStatus(storedFileId, result.clean() ? ScanStatus.CLEAN : ScanStatus.FLAGGED);
            if (!result.clean()) {
                this.logger.warning("@DefaultContentScanService: " + storedFileId + " flagged as " + result.malwareName());
            }
        } catch (final IOException | ClamAvScanException scanFailed) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: scan attempt " + attemptNumber + " failed for " + storedFileId, scanFailed);
            scheduleRetryOrFailOpen(storedFileId, attemptNumber);
        }
    }

    private void scheduleRetryOrFailOpen(final String storedFileId, final int attemptNumber) {
        if (attemptNumber >= MAX_ATTEMPTS) {
            this.logger.severe("@DefaultContentScanService: giving up on " + storedFileId + " after " + attemptNumber
                    + " attempts - failing open (marking clean) rather than leaving it permanently inaccessible");
            persistStatus(storedFileId, ScanStatus.CLEAN);
            return;
        }
        final Duration delay = RETRY_DELAYS[attemptNumber - 1];
        try {
            this.retryScheduler.schedule(() -> performScan(storedFileId, attemptNumber + 1), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final RuntimeException schedulingFailed) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to schedule retry for " + storedFileId, schedulingFailed);
            persistStatus(storedFileId, ScanStatus.CLEAN);
        }
    }

    private void persistStatus(final String storedFileId, final ScanStatus status) {
        final StoredFile file;
        try {
            file = this.fileFactory.findById(storedFileId).orElse(null);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException
                       | RuntimeException e) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to re-fetch " + storedFileId + " to persist scan status", e);
            return;
        }
        if (file == null) {
            return;
        }
        try {
            this.dataFactory.update(file.withScanStatus(status));
        } catch (final DatabaseClientException | KeyWrapException | RuntimeException e) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to persist scan status for " + storedFileId, e);
            return;
        }
        refreshCachedListingStatus(storedFileId, status);
    }

    /**
     * Pushes the just-persisted status into {@code StoredFileOwnership}'s own cached mirror (see
     * {@link ICloudUserService#updateCachedFileScanStatus(String, String)}'s own Javadoc) so a
     * listing (which never reads {@link StoredFile} directly) reflects the real, finished scan
     * result instead of staying pinned at its initial-upload-time value. Reached directly off
     * {@link CloudDriver#getInstance()} rather than a constructor-injected collaborator - this
     * module has no dependency on {@code cloud-driver-auth} (only the {@code cloud-driver-api}
     * contract), and the service may not have been published yet (a deployment starting this
     * extension before {@code cloud-driver-rest} has run) - so this is deliberately best-effort,
     * never allowed to affect the scan result itself.
     */
    private void refreshCachedListingStatus(final String storedFileId, final ScanStatus status) {
        try {
            final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
            if (cloudUserService != null) {
                cloudUserService.updateCachedFileScanStatus(storedFileId, status.name());
            }
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

}
