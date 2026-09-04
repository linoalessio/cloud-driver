package de.lino.cloud.api.icloud;

/**
 * A snapshot of one on-demand iCloud import job's current state, returned by every {@link
 * IcloudImportService} method.
 *
 * @param jobId opaque id identifying this import job, to be passed back to {@link
 *              IcloudImportService#confirmTwoFactor}/{@link IcloudImportService#getStatus}
 * @param status the job's current lifecycle state
 * @param filesImported how many files have been uploaded so far
 * @param totalFiles the total number of files the job will import, once known (after the tree walk
 *                    completes) - {@code 0} while still {@link IcloudImportStatus#AWAITING_TWO_FACTOR}
 *                    or before the tree has been walked
 * @param errorMessage a human-readable failure reason if {@code status} is {@link
 *                      IcloudImportStatus#FAILED}, {@code null} otherwise
 */
public record IcloudImportHandle(String jobId, IcloudImportStatus status, int filesImported, int totalFiles, String errorMessage) {
}
