package de.lino.cloud.api.scan;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

/**
 * Malware-scans a {@link StoredFile}'s content after upload - section 9 of {@code
 * architecture/MICRO.md}, reached via {@link IServiceContainer#getContentScanService()} - {@code
 * null} until {@code cloud-driver-extensions-scan}'s {@code CloudScanExtension} has published one
 * (not started, or this deployment doesn't run that extension at all).
 *
 * <p><b>Deployment dependency, confirmed with Lino before implementation</b>: the one shipped
 * implementation (see {@code cloud-driver-extensions-scan}'s own module Javadoc) talks to a
 * separately-run {@code clamd} (ClamAV) daemon over a socket - this is a genuinely new operational
 * dependency, not merely a new Maven dependency, and this deployment's {@code clamd} instance
 * needs its own virus-definition database kept current (via {@code freshclam}) for scanning to be
 * meaningful at all.
 *
 * <p><b>Triggered via {@code FileChangeListener} (section 1), unlike sections 5/8</b> - deliberately:
 * unlike search indexing or webhook dispatch, a scan only ever needs to react to a brand-new
 * {@code StoredFile} row appearing (an {@code INSERT}), which is exactly what that mechanism
 * already watches; it never needs to react to a soft-delete/share (different tables entirely),
 * so the gaps that ruled it out for sections 5/8 don't apply here.
 */
public interface ContentScanService {

    /**
     * Triggers an asynchronous scan of {@code storedFileId}'s content - called from a {@code
     * FileChangeListener} reacting to a fresh upload, never synchronously from the upload request
     * itself. Must return quickly and never throw; the actual scan (fetching content, talking to
     * the scan engine, and persisting the result via {@link StoredFile#withScanStatus}) always
     * happens on this service's own background worker.
     *
     * @param storedFileId the file to scan
     */
    void scanAsync(@NotNull String storedFileId);

}
