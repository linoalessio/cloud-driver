package de.lino.cloud.api.icloud;

/**
 * Orchestrates one on-demand "Sync from iCloud" import - the {@code cloud-driver-plugin} route
 * handlers' entry point into walking a real Apple iCloud Drive account and mirroring it into this
 * account's own storage via {@code CloudUserService#createFolder}/{@code #uploadFile}. Same
 * "{@code I}-prefixed interface, concrete class implements it" shape {@link
 * de.lino.cloud.api.jwt.auth.IAuthService}/{@link de.lino.cloud.api.user.ICloudUserService} already
 * use, needed here so {@link de.lino.cloud.api.factory.service.IServiceContainer} can reference this
 * contract without depending on {@code cloud-driver-plugin} (the only module the one real
 * implementation, backed by {@link IcloudBridge}, lives in).
 *
 * <p><b>Deliberately not a persistent link/sync.</b> Nothing about the Apple account (credentials,
 * session, or a mapping of which iCloud item became which local file/folder) survives past one job.
 * Every {@link #startImport} call is a fresh login and a fresh full walk of the whole Drive tree;
 * re-running an import does not deduplicate against a previous run's files - it only reuses the same
 * top-level destination folder by name, so a second import of the same account uploads every file
 * again as a fresh copy. This is a deliberate trade-off (see {@code CLAUDE.md}), not an oversight -
 * avoiding any persisted credential/session storage was the whole point of scoping this as an
 * on-demand import rather than a maintained sync service.
 *
 * <p>Job state itself ({@link IcloudImportHandle}) is tracked in memory only, not through {@code
 * DataFactory} - there is nothing here worth surviving a restart, and in-memory tracking avoids
 * inventing a persisted entity for transient progress data (the same "not for massive scale, fine
 * for this app" trade-off {@code AuthRateLimitBucket}/{@code InMemoryPendingUploadCache} already
 * make elsewhere in this codebase).
 */
public interface IcloudImportService {

    /**
     * Starts a new import job: attempts to log in to {@code appleId} and, once authenticated
     * (immediately, or after {@link #confirmTwoFactor} if Apple demands a two-factor code), walks
     * the account's entire iCloud Drive tree and uploads every file into this {@code authUserId}'s
     * own storage. Returns immediately with the job's initial state - the actual work (including the
     * login itself) runs asynchronously; poll {@link #getStatus} for progress.
     *
     * @param authUserId the requesting user's own account to import into
     * @param appleId the Apple ID (email address) to import from
     * @param password the Apple ID's plaintext password - never persisted beyond the initial login call
     * @return the newly created job's initial state ({@link IcloudImportStatus#AWAITING_TWO_FACTOR} or {@link IcloudImportStatus#RUNNING})
     */
    IcloudImportHandle startImport(String authUserId, String appleId, char[] password);

    /**
     * Completes a job that {@link #startImport} left waiting on Apple's two-factor challenge,
     * then proceeds into the same tree-walk-and-upload phase {@link #startImport} would have
     * entered directly had two-factor authentication not been required.
     *
     * @param authUserId the requesting user - must match the account {@link #startImport} was called with for this job
     * @param jobId the job id returned by {@link #startImport}
     * @param code the six-digit code from the Apple ID's trusted device/authenticator
     * @return the job's state immediately after this call ({@link IcloudImportStatus#RUNNING})
     * @throws IllegalArgumentException if {@code jobId} is unknown, doesn't belong to {@code authUserId}, or isn't currently {@link IcloudImportStatus#AWAITING_TWO_FACTOR}
     */
    IcloudImportHandle confirmTwoFactor(String authUserId, String jobId, String code);

    /**
     * Returns a job's current state, for polling.
     *
     * @param authUserId the requesting user - must match the account {@link #startImport} was called with for this job
     * @param jobId the job id returned by {@link #startImport}
     * @return the job's current state
     * @throws IllegalArgumentException if {@code jobId} is unknown or doesn't belong to {@code authUserId}
     */
    IcloudImportHandle getStatus(String authUserId, String jobId);

}
