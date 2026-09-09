package de.lino.cloud.api.scan;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

/**
 * Malware-scans a {@link StoredFile}'s content after upload, reached via {@link IServiceContainer#getContentScanService()} - {@code
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
 * <p><b>Triggered via {@code FileChangeListener}, unlike search indexing/webhook dispatch</b> -
 * deliberately: unlike search indexing or webhook dispatch, a scan only ever needs to react to a
 * brand-new {@code StoredFile} row appearing (an {@code INSERT}), which is exactly what that
 * mechanism already watches; it never needs to react to a soft-delete/share (different tables
 * entirely), so the gaps that rule that mechanism out for search/webhooks don't apply here.
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

    /**
     * Probes whether the backing scan engine is actually reachable right now.
     *
     * <p><b>This is not a formality.</b> Scanning deliberately fails <em>open</em>: a file whose
     * scan cannot be performed is marked {@code CLEAN} and never revisited, so an unreachable
     * engine does not degrade uploads - it silently stops protecting them. That is precisely what
     * happened on this deployment, where {@code clamd} was installed and running but had no TCP
     * listener at all, so every scan attempt was refused, retried, and failed open, with the only
     * evidence buried in console scrollback. A published service is therefore <b>not</b> evidence
     * that scanning works; this method is.
     *
     * <p>Must never throw - an unreachable engine is the answer, not an error.
     *
     * @return {@code true} if the scan engine answered, {@code false} if it is unreachable or an
     * implementation has no way to probe it (the default)
     */
    default boolean isScannerReachable() {
        return false;
    }

}
