package de.lino.cloud.api.icloud;

import java.nio.file.Path;
import java.util.List;

/**
 * The vendor-agnostic contract for talking to a real Apple iCloud account's Drive - backing the
 * on-demand "Sync from iCloud" import (not a persistent link/sync; see {@link IcloudImportService}'s
 * own Javadoc). Apple publishes no API for this, so every implementation necessarily goes through
 * the same unofficial, reverse-engineered approach tools like {@code pyicloud}/{@code rclone} use -
 * this interface exists purely so {@link IcloudImportService} (and, transitively, {@code
 * cloud-driver-plugin}'s {@code DefaultRestFactory}) doesn't need to know that the one real
 * implementation shells out to a bundled Python script, the same "contract in {@code
 * cloud-driver-api}, concrete implementation elsewhere" shape as {@link
 * de.lino.cloud.api.mail.EmailSender}/{@code KeyEncryptionService}.
 *
 * <p>{@code sessionDir} on every method is a per-import-job scratch directory (see {@code
 * Constraints#ICLOUD_IMPORT_SCRATCH_PATH}) an implementation may use to persist whatever
 * authentication state (e.g. a session cookie) it needs to carry between calls within the same
 * job - nothing here is ever expected to survive past that job's own lifetime; the caller deletes
 * the whole directory once the job reaches a terminal state.
 */
public interface IcloudBridge {

    /**
     * Attempts to log in to {@code appleId}, persisting whatever session state results into {@code
     * sessionDir} for later calls in the same job to reuse.
     *
     * @param appleId the Apple ID (email address) to authenticate as
     * @param password the account's plaintext password - never persisted beyond this call
     * @param sessionDir a fresh, per-job scratch directory this call may write session state into
     * @return whether Apple additionally requires a two-factor code before this login is complete
     * @throws IcloudAuthenticationException if {@code appleId}/{@code password} was rejected outright
     * @throws IcloudBridgeException if the bridge itself failed (process couldn't run, malformed response, etc.)
     */
    IcloudLoginResult login(String appleId, char[] password, Path sessionDir)
            throws IcloudAuthenticationException, IcloudBridgeException;

    /**
     * Completes a login that {@link #login} reported as requiring two-factor authentication, using
     * the session state {@link #login} left in {@code sessionDir}.
     *
     * @param appleId the Apple ID this session belongs to
     * @param code the six-digit code from the account's trusted device/authenticator
     * @param sessionDir the same scratch directory passed to the {@link #login} call this completes
     * @throws IcloudAuthenticationException if {@code code} was rejected
     * @throws IcloudBridgeException if the bridge itself failed
     */
    void confirmTwoFactorCode(String appleId, String code, Path sessionDir)
            throws IcloudAuthenticationException, IcloudBridgeException;

    /**
     * Walks the entire iCloud Drive tree for the already-authenticated session in {@code
     * sessionDir}, in one call - so a caller knows the total file count/size up front, before
     * downloading anything.
     *
     * @param appleId the Apple ID this session belongs to
     * @param sessionDir the scratch directory holding this session's authenticated state
     * @return every file and directory in the account's iCloud Drive, in no particular order
     * @throws IcloudBridgeException if the bridge itself failed
     */
    List<IcloudTreeEntry> listTree(String appleId, Path sessionDir) throws IcloudBridgeException;

    /**
     * Downloads one file's content, writing it directly to {@code destination} rather than
     * returning it in memory - the bridge process only ever exchanges small JSON over stdout, never
     * raw file bytes.
     *
     * @param appleId the Apple ID this session belongs to
     * @param sessionDir the scratch directory holding this session's authenticated state
     * @param remotePath the file's {@link IcloudTreeEntry#path()}, as returned by {@link #listTree}
     * @param destination where to write the downloaded bytes; must not already exist
     * @throws IcloudBridgeException if the bridge itself failed
     */
    void downloadFile(String appleId, Path sessionDir, String remotePath, Path destination) throws IcloudBridgeException;

}
