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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * <p><b>Content is never materialized.</b> The size cap is decided from the row's own recorded
 * size before any content is fetched from storage, and content within the cap is streamed to
 * {@code clamd} in chunks rather than held in memory - so a file's size bounds how long a scan
 * takes, not how much heap it costs. A row that records no size of its own (a legacy row) has the
 * same ceiling applied to the stream itself, which stops the moment it is crossed.
 *
 * <p><b>On a scan failure (connection refused, timeout, an unexpected {@code clamd} response) -
 * retried up to {@link #MAX_ATTEMPTS} times, then <em>fails open</em>: the file is marked {@link
 * ScanStatus#CLEAN} with a loud {@code WARNING} log, not left stuck at {@link ScanStatus#PENDING}
 * forever.</b> A deliberate, flagged trade-off, not a silent decision - the alternative
 * (fail-closed, leaving a file permanently inaccessible whenever {@code clamd} has any hiccup)
 * would turn a scanner outage into an upload-availability outage, which this codebase's own
 * "must keep working with every microservice turned off" constraint argues against; the cost is
 * that a scanner outage during upload silently disables real protection for that file.
 *
 * <p><b>The one thing that never fails open is content that cannot be read back at all</b> - a
 * checksum mismatch against what the uploading client declared, or an authentication failure
 * (tampered, truncated, or not this file's stored content). That failure is deterministic, it
 * repeats on every retry, and on the direct-transfer path the account itself chose the stored
 * bytes, so it is marked {@link ScanStatus#FLAGGED} immediately and never retried: otherwise
 * unreadable bytes would earn a permanent {@link ScanStatus#CLEAN} with nothing ever scanned. A
 * file in that state is refused by every read, exactly as a scanner-flagged one is; the {@code
 * SEVERE} log line names which of the two rejections it was.
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

    /**
     * {@inheritDoc} Delegates to {@code clamd}'s own {@code PING} command via {@link
     * ClamAvClient#ping()} - see that method's Javadoc for why a ping rather than a socket
     * connect, and {@link de.lino.cloud.api.scan.ContentScanService#isScannerReachable()} for why
     * this probe matters more here than the mere presence of this service does.
     */
    @Override
    public boolean isScannerReachable() {
        return this.clamAvClient.ping();
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
        // Metadata first, deliberately: this resolves no content, so an object far larger than the
        // heap is refused by the size cap below without ever being pulled out of the object store.
        // Resolving first and measuring afterwards is what made a single large upload able to kill
        // the process.
        final StoredFile metadata;
        try {
            metadata = this.dataFactory.findById(storedFileId, StoredFile.class).orElse(null);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | RuntimeException e) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to fetch metadata for " + storedFileId + " for scanning", e);
            scheduleRetryOrFailOpen(storedFileId, attemptNumber);
            return;
        }
        if (metadata == null) {
            return; // deleted/never existed by the time this ran - nothing to scan
        }

        final Long knownSizeBytes = metadata.sizeBytesIfKnown();
        if (knownSizeBytes != null && knownSizeBytes > this.maxScannableBytes) {
            this.logger.warning("@DefaultContentScanService: " + storedFileId + " (" + knownSizeBytes
                    + " bytes) exceeds the configured scan size cap (" + this.maxScannableBytes + ") - marking clean without scanning");
            persistStatus(storedFileId, metadata, ScanStatus.CLEAN);
            return;
        }

        final InputStream content;
        try {
            content = this.fileFactory.openContentStream(storedFileId).orElse(null);
        } catch (final FileIntegrityException | AuthenticationFailedException contentRejected) {
            // Neither transient nor a scanner problem: the stored bytes either fail the checksum the
            // uploading client declared or fail authentication outright (tampered, truncated, or not
            // this file's content at all), so they can never be read for scanning and must never be
            // served. Retrying reproduces the identical failure and then fails *open* - which is how
            // content the server cannot read at all would buy a permanent CLEAN verdict without a
            // single byte reaching the scanner, on the one path where the account writes the stored
            // object's bytes itself.
            this.logger.log(Level.SEVERE, "@DefaultContentScanService: " + storedFileId
                    + " could not be read back for scanning (" + contentRejected.getClass().getSimpleName()
                    + ") - flagging rather than failing open", contentRejected);
            persistStatus(storedFileId, metadata, ScanStatus.FLAGGED);
            return;
        } catch (final DatabaseClientException | KeyWrapException | RuntimeException e) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to fetch " + storedFileId + " for scanning", e);
            scheduleRetryOrFailOpen(storedFileId, attemptNumber);
            return;
        }
        if (content == null) {
            return; // deleted/never existed by the time this ran - nothing to scan
        }

        // The stream never escapes this block: closing it releases the object-store connection.
        try (CappedInputStream scannable = new CappedInputStream(content, this.maxScannableBytes)) {
            final ClamAvScanResult result = this.clamAvClient.scan(scannable);
            persistStatus(storedFileId, metadata, result.clean() ? ScanStatus.CLEAN : ScanStatus.FLAGGED);
            if (!result.clean()) {
                this.logger.warning("@DefaultContentScanService: " + storedFileId + " flagged as " + result.malwareName());
            }
        } catch (final ContentTooLargeException tooLarge) {
            // Backstop for a row whose size metadata was unknown above (a legacy row that predates
            // recorded sizes). Nothing beyond the cap was ever held: the stream stops the moment it
            // crosses it.
            this.logger.warning("@DefaultContentScanService: " + storedFileId + " (over " + this.maxScannableBytes
                    + " bytes) exceeds the configured scan size cap (" + this.maxScannableBytes + ") - marking clean without scanning");
            persistStatus(storedFileId, metadata, ScanStatus.CLEAN);
        } catch (final IOException | ClamAvScanException scanFailed) {
            // A streamed read can only report a checksum mismatch or a mid-stream authentication
            // failure once it has reached that point, so both arrive here as an IOException cause -
            // and both mean the content cannot be read back, which must fail closed exactly as the
            // eager rejections above do rather than retry into a fail-open CLEAN.
            final Throwable cause = scanFailed.getCause();
            if (cause instanceof FileIntegrityException || cause instanceof AuthenticationFailedException) {
                this.logger.log(Level.SEVERE, "@DefaultContentScanService: " + storedFileId
                        + " could not be read back for scanning (" + cause.getClass().getSimpleName()
                        + ") - flagging rather than failing open", scanFailed);
                persistStatus(storedFileId, metadata, ScanStatus.FLAGGED);
                return;
            }
            this.logger.log(Level.WARNING, "@DefaultContentScanService: scan attempt " + attemptNumber + " failed for " + storedFileId, scanFailed);
            scheduleRetryOrFailOpen(storedFileId, attemptNumber);
        }
    }

    /**
     * Signals that a scanned stream crossed the configured size ceiling - the backstop for a row
     * that records no size of its own, where the metadata gate before the fetch cannot apply.
     * An {@link IOException} so it travels out of a read the same way any other stream failure
     * does; the caller separates it from a real failure by type.
     */
    private static final class ContentTooLargeException extends IOException {

        /**
         * @param maxBytes the ceiling the stream crossed
         */
        private ContentTooLargeException(final long maxBytes) {
            super("@DefaultContentScanService: content exceeds the " + maxBytes + "-byte scan size cap");
        }
    }

    /**
     * Stops a content stream the moment it has yielded more than {@code maxBytes}, so a file whose
     * recorded size was unknown can never be read past the configured cap. Closing it closes the
     * wrapped stream, releasing the object-store connection behind it.
     */
    private static final class CappedInputStream extends FilterInputStream {

        /** The most this stream may yield before it refuses to continue. */
        private final long maxBytes;

        /** How many bytes have been read so far. */
        private long bytesRead;

        /**
         * @param content the content stream to cap
         * @param maxBytes the most {@code content} may yield
         */
        private CappedInputStream(final InputStream content, final long maxBytes) {
            super(content);
            this.maxBytes = maxBytes;
        }

        /** {@inheritDoc} Refuses to yield a byte past the cap. */
        @Override
        public int read() throws IOException {
            final int value = super.read();
            if (value < 0) {
                return value;
            }
            this.bytesRead++;
            this.requireWithinCap();
            return value;
        }

        /** {@inheritDoc} Refuses to yield bytes past the cap. */
        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            final int read = super.read(buffer, offset, length);
            if (read <= 0) {
                return read;
            }
            this.bytesRead += read;
            this.requireWithinCap();
            return read;
        }

        /**
         * @throws ContentTooLargeException once more than {@link #maxBytes} have been read
         */
        private void requireWithinCap() throws ContentTooLargeException {
            if (this.bytesRead > this.maxBytes) {
                throw new ContentTooLargeException(this.maxBytes);
            }
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
            // Metadata only: this needs the entity to stamp the status onto, never its content.
            file = this.dataFactory.findById(storedFileId, StoredFile.class).orElse(null);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | RuntimeException e) {
            this.logger.log(Level.WARNING, "@DefaultContentScanService: failed to re-fetch " + storedFileId + " to persist scan status", e);
            return;
        }
        if (file == null) {
            return;
        }
        persistStatus(storedFileId, file, status);
    }

    /**
     * Stamps {@code status} onto an entity the caller already holds - the form every in-scan
     * verdict uses, so reaching a verdict never costs a second fetch (which, before the content
     * channel was bypassed here, meant a second full download and decrypt of the file).
     *
     * @param storedFileId the file being judged, for logging
     * @param file the entity to stamp - metadata-only or fully resolved, either works
     * @param status the verdict to persist
     */
    private void persistStatus(final String storedFileId, final StoredFile file, final ScanStatus status) {
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
