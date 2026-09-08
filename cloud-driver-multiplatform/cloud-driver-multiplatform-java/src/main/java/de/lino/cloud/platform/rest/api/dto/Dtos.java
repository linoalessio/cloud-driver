package de.lino.cloud.platform.rest.api.dto;

import java.util.Map;

/**
 * Plain request/response shapes mirrored 1:1 against the server's REST contract - see
 * {@code CloudRestExtension}/{@code DefaultRestFactory} in cloud-driver for the authoritative
 * field names. Deliberately not shared code with the server module: the desktop client only
 * ever talks HTTP, so it has no dependency on cloud-driver-api at all.
 */
public final class Dtos {

    /** Not instantiable - a pure namespace for the nested record types below. */
    private Dtos() {
    }

    /**
     * Body for {@code POST /auth/login} and {@code POST /auth/register} - both read the same
     * {@code {"username", "password"}} shape server-side. The field is named {@code username}
     * to match the server's {@code AuthService} contract exactly, even though the value passed
     * in is always an email address.
     */
    public record AuthRequest(String username, String password) {
    }

    /**
     * Body for {@code POST /auth/register/confirm} - step two of registration, submitting the
     * 6-digit code {@code POST /auth/register} e-mailed. {@code username} is the same email
     * address passed to {@link AuthRequest}, named the same way for the same reason.
     */
    public record ConfirmRegistrationRequest(String username, String code) {
    }

    /**
     * Response of {@code POST /auth/login}, {@code POST /auth/register/confirm}, {@code
     * POST /auth/reset-password/confirm}, and {@code POST /auth/refresh} - every one of the
     * server's token-issuing routes returns this same shape. {@code refreshToken} is a
     * longer-lived, opaque, single-use token (rotated on every {@code POST /auth/refresh} call) a
     * client can exchange for a fresh pair once {@code token} expires, without asking the user to
     * log in again - see {@link ApiClient#refresh}.
     */
    public record AuthResponse(String token, String refreshToken) {
    }

    /**
     * Body for {@code POST /auth/refresh} and {@code POST /auth/logout} - both take a bare refresh
     * token, previously returned in an {@link AuthResponse}.
     */
    public record RefreshRequest(String refreshToken) {
    }

    /** Body for {@code POST /auth/reset-password} - starts a password reset for the account under {@code username}. */
    public record RequestPasswordResetRequest(String username) {
    }

    /**
     * Body for {@code POST /auth/reset-password/confirm} - submits the 6-digit code {@code
     * POST /auth/reset-password} e-mailed, plus the caller's chosen {@code newPassword}.
     */
    public record ConfirmPasswordResetRequest(String username, String code, String newPassword) {
    }

    /**
     * Body for {@code POST /auth/change-email} - bearer-gated (unlike every other request record
     * in this class): the account being changed is the caller's own, resolved server-side from
     * its bearer token, not from anything in this body. Starts an e-mail change for the
     * already-authenticated caller; {@code newEmail} is not live yet - only {@link
     * ApiClient#confirmEmailChange} actually applies it.
     */
    public record ChangeEmailRequest(String newEmail) {
    }

    /**
     * Body for {@code POST /auth/change-email/confirm} - submits the 6-digit code {@code
     * POST /auth/change-email} e-mailed to the pending new address.
     */
    public record ConfirmChangeEmailRequest(String code) {
    }

    /**
     * Response of {@code POST /auth/register} ({@code 202 Accepted}) - registration is not
     * complete yet at this point, this only acknowledges that a verification code was e-mailed.
     */
    public record MessageResponse(String message) {
    }

    /**
     * Shape of the object {@code POST /files} and {@code GET /files/{id}} return on success -
     * <b>not</b> {@code GET /files}'s own listing, which returns {@link StoredFileSummaryResponse}
     * instead (see that record's Javadoc). Mirrors {@code StoredFile}'s Gson-serialized fields;
     * {@code checksum} is left out entirely here since the client only needs {@code
     * fileId}/{@code fileName}/{@code contentBase64} to download and save a file. {@code
     * folderId} is not a {@code StoredFile} field at all - the server merges it in from the
     * file's {@code StoredFileOwnership} row (see {@code DefaultRestFactory#toJsonObject}) -
     * {@code null} for a file at the root.
     */
    public record StoredFileResponse(
            String fileId,
            String fileName,
            String contentType,
            String contentBase64,
            boolean contentCompressed,
            long createdAtEpochMilli,
            long updatedAtEpochMilli,
            String folderId
    ) {
    }

    /**
     * Shape of one entry in the {@code GET /files} response array - a file's descriptive fields
     * plus its folder placement, deliberately without content (mirrors the server's {@code
     * StoredFileSummary}). Fetch a specific file's full content afterwards via {@code
     * GET /files/{id}} ({@link ApiClient#downloadFile}), which returns a {@link
     * StoredFileResponse} instead.
     *
     * <p>{@code scanStatus} mirrors the server's content-scan verdict for this file - one of
     * {@code "CLEAN"}/{@code "PENDING"}/{@code "FLAGGED"}, treated here as an opaque string. A
     * file uploaded on a deployment with no content-scanning extension running never carries
     * anything but {@code "CLEAN"}.
     */
    public record StoredFileSummaryResponse(
            String fileId,
            String fileName,
            String contentType,
            long sizeBytes,
            long createdAtEpochMilli,
            long updatedAtEpochMilli,
            String folderId,
            String scanStatus
    ) {
    }

    /**
     * Shape of one entry in the {@code GET /folders} response array, and of the object
     * {@code POST /folders}/{@code PUT /folders/{id}} return on success. Mirrors {@code
     * Folder}'s Gson-serialized fields; {@code ownerId} is included even though the client
     * never needs to act on it, purely because it's part of the server's actual JSON shape.
     * {@code color} (added for per-folder display color) mirrors {@code Folder#getColor()} -
     * an opaque, client-defined string (e.g. {@code "BLUE"}), {@code null} if never explicitly
     * set (a client falls back to its own default).
     */
    public record FolderResponse(
            String folderId,
            String ownerId,
            String name,
            String parentFolderId,
            long createdAtEpochMillis,
            long modifiedAtEpochMillis,
            String color
    ) {
    }

    /** Body for {@code POST /folders} - {@code parentFolderId} {@code null} creates a top-level folder. */
    public record CreateFolderRequest(String name, String parentFolderId) {
    }

    /**
     * Body for {@code PUT /folders/{id}} - a full replace of both fields (matching {@code PUT}'s
     * whole-resource-replace semantics), not a partial patch; {@code parentFolderId} {@code null}
     * moves the folder to the top level.
     */
    public record UpdateFolderRequest(String name, String parentFolderId) {
    }

    /** Body for {@code PUT /folders/{id}/color} - {@code color} {@code null} clears it back to "unset". */
    public record UpdateFolderColorRequest(String color) {
    }

    /** Body for {@code PUT /files/{id}/folder} - {@code folderId} {@code null} moves the file back to the root. */
    public record MoveFileRequest(String folderId) {
    }

    /** Body for {@code PUT /files/{id}/rename} - {@code fileName} is the file's new display name. */
    public record RenameFileRequest(String fileName) {
    }

    /**
     * Body for {@code PUT /cloudUsers/theme} (added 2026-09-04) - syncs the caller's light/dark
     * theme choice to their account so it follows them across devices, replacing what used to be
     * a per-device local setting. {@code themeMode} is treated as an opaque string server-side
     * (e.g. {@code "LIGHT"}/{@code "DARK"} - the actual enum lives client-side); {@code null}
     * clears the stored preference back to "unset".
     */
    public record UpdateThemeRequest(String themeMode) {
    }

    /**
     * Body for {@code POST /files/upload-url} - the first step of a presigned, direct-to-client
     * upload. {@code sizeBytes} is checked against quota now and again (against the real uploaded
     * size) at {@link CompleteUploadRequest}.
     */
    public record BeginUploadUrlRequest(String fileName, long sizeBytes, String folderId) {
    }

    /**
     * Response from {@code POST /files/upload-url} - {@code requiredHeaders} must be replayed
     * exactly on the client's own {@code PUT} to {@code uploadUrl}, or the object store rejects
     * the request's signature.
     */
    public record BeginUploadUrlResponse(String fileId, String uploadUrl, Map<String, String> requiredHeaders, long expiresAtEpochMillis) {
    }

    /**
     * Body for {@code POST /files/{id}/complete-upload} - the second step of a presigned upload.
     * No {@code sizeBytes} field here: the server always re-reads the real size from the object
     * store itself, never trusting the client's declared size a second time.
     */
    public record CompleteUploadRequest(String fileName, String checksumSha256, String folderId) {
    }

    /** Response from {@code GET /files/{id}/download-url} - the client {@code GET}s {@code downloadUrl} directly, bypassing this server. */
    public record BeginDownloadUrlResponse(String downloadUrl, long expiresAtEpochMillis) {
    }

    /** Body Javalin's default error responses use ({@code BadRequestResponse} etc. all share this shape). */
    public record ErrorResponse(String title) {
    }

    /**
     * Mirrors the server's {@code CursorPage<T>} response envelope - what {@code GET /files}/
     * {@code GET /folders} return instead of a bare array once a caller passes {@code ?limit=}
     * (see {@code DefaultRestFactory#toPageEnvelope}). {@code nextCursor} is {@code null} once
     * there is no next page; pass it back as the next call's {@code ?cursor=} otherwise.
     *
     * @param items      up to the requested page size
     * @param nextCursor the cursor to request the next page with, or {@code null} if this was the last page
     * @param <T>        the element type of one page - {@link StoredFileSummaryResponse} or {@link FolderResponse}
     */
    public record Page<T>(java.util.List<T> items, String nextCursor) {
    }

    /**
     * Shape of the object {@code GET /cloudUsers/{id}} returns - mirrors {@code CloudUser}'s
     * Gson-serialized fields. {@code timeStamp} is set once, when the {@code CloudUser} record is
     * first created (account confirmation time), so it doubles as the account's creation
     * timestamp - see {@code CloudUser}'s own Javadoc server-side. {@code maxBytesToUpload}/{@code
     * currentUploadedBytes} back the Dashboard's s3storage-quota display - see {@code
     * ICloudUser#getMaxBytesToUpload()}/{@code #getCurrentUploadedBytes()} server-side for what
     * each actually tracks. {@code themeMode} (added 2026-09-04) is this account's synced
     * light/dark theme preference - {@code null} if never explicitly set, in which case a client
     * falls back to its own default; update it via {@code PUT /cloudUsers/theme} (see {@link
     * de.lino.cloud.platform.rest.api.ApiClient#updateThemePreference}).
     */
    public record CloudUserResponse(String authUserId, long timeStamp, long maxBytesToUpload, long currentUploadedBytes, String themeMode) {
    }

    /**
     * Response of {@code GET /auth/me} (bearer-gated) - the caller's own account id, email
     * address, and admin flag. Used by the desktop client to decide whether to show its Admin
     * sidebar entry at all, since there is otherwise no way for a client to learn this without
     * probing an admin-gated route and interpreting a {@code 403}.
     */
    public record MeResponse(String authUserId, String emailAddress, boolean isAdmin) {
    }

    /**
     * Shape of one entry in the {@code GET /admin/authUsers} response array (admin-gated) -
     * mirrors {@code AuthUser}'s Gson-serialized fields, keeping only what the desktop client's
     * read-only Admin panel actually displays; extra fields present in the server's actual JSON
     * (e.g. {@code passwordHash}) are simply ignored by Gson on deserialization into this smaller
     * shape.
     */
    public record AuthUserResponse(String id, String emailAddress, boolean isAdmin) {
    }

    /**
     * Shape of one entry in the {@code GET /admin/audit-log} response array (admin-gated) -
     * mirrors the server's {@code AuditLogEntryResponse}: {@code actorEmail} is already resolved
     * server-side from the underlying actor id, {@code null} for an action with no identified
     * actor (e.g. a failed login against an unknown address).
     */
    public record AuditLogEntryResponse(long timestampEpochMillis, String action, String actorEmail, String targetId) {
    }

    /**
     * Response of {@code GET /files/shared-by-me/count} - how many of the caller's own files
     * currently have at least one active share (the owner-side count, distinct from {@code
     * GET /files/shared-with-me}'s grantee-side listing). Backs the desktop app's Dashboard
     * "Shared files" stat card.
     */
    public record SharedByMeCountResponse(int count) {
    }

    /**
     * Response of {@code GET /admin/metrics} (admin-gated) - mirrors the server's {@code
     * MetricsSnapshot} field-for-field, read in-process off {@code cloud-driver-extensions-metrics}'s
     * Prometheus registry rather than a separate scrape call. {@code 404}/{@code
     * ApiException(503, ...)} if that extension isn't running on this deployment at all - the
     * desktop client should treat that as "metrics unavailable", not a bug.
     */
    public record MetricsSnapshotResponse(long uploadsSucceeded, long uploadsFailed, long uploadsQueued,
                                           long uploadQuotaRejections, long pendingUploadQueueDepth,
                                           Map<String, Long> extensionsByStatus) {
    }

    /**
     * Body for {@code POST /files/{id}/share} and {@code POST /folders/{id}/share} - grants
     * {@code granteeEmail}'s account access. {@code permissionLevel} ({@code "VIEW"}/{@code
     * "EDIT"}, {@code null} defaults to {@code "VIEW"} server-side) and {@code
     * expiresAtEpochMillis} ({@code null} means the grant never expires) are both optional -
     * omitting them (the original two-field shape this record used to be) behaves exactly as
     * before. {@code EDIT} only ever takes effect on a direct file share, never a folder share or
     * a folder-inherited grant.
     */
    public record ShareRequest(String granteeEmail, String permissionLevel, Long expiresAtEpochMillis) {
    }

    /**
     * Response of {@code GET /cloudUsers/exists?email=<address>} - whether <em>any</em> account is
     * registered under that address. Backs the desktop app's Share dialog live-checking a typed
     * grantee address before submitting a share.
     */
    public record EmailExistsResponse(boolean exists) {
    }

    /**
     * Shape of one entry in the {@code GET /files/shared-with-me} response array (added 2026-09-02,
     * replacing a bare {@link StoredFileSummaryResponse} array) - mirrors the server's {@code
     * SharedFileSummary}: the file's own descriptive fields plus {@code ownerEmail}, the email
     * address of the account that shared it.
     */
    public record SharedFileSummaryResponse(StoredFileSummaryResponse file, String ownerEmail) {
    }

    /**
     * Shape of one entry in the {@code GET /folders/shared-with-me} response array (added
     * 2026-09-02, replacing a bare {@link FolderResponse} array) - mirrors the server's {@code
     * SharedFolderSummary}, the same "pair with the sharing account's email" shape as {@link
     * SharedFileSummaryResponse}.
     */
    public record SharedFolderSummaryResponse(FolderResponse folder, String ownerEmail) {
    }

    /**
     * Shape of one entry in the {@code GET /files/trash} response array (added 2026-09-02,
     * replacing a bare {@link StoredFileSummaryResponse} array) - mirrors the server's {@code
     * TrashedFileSummary}: the file's own descriptive fields plus {@code purgeAtEpochMillis}, when
     * it becomes eligible for permanent removal under the server's configured trash retention
     * window.
     */
    public record TrashedFileSummaryResponse(StoredFileSummaryResponse file, long purgeAtEpochMillis) {
    }

    /**
     * Shape of one entry in the {@code GET /folders/trash} response array (added 2026-09-02,
     * replacing a bare {@link FolderResponse} array) - mirrors the server's {@code
     * TrashedFolderSummary}, the same "pair with a purge-eligibility timestamp" shape as {@link
     * TrashedFileSummaryResponse}.
     */
    public record TrashedFolderSummaryResponse(FolderResponse folder, long purgeAtEpochMillis) {
    }

    /**
     * Response of {@code GET /folders/{id}/shared-contents} (added 2026-09-02) - the non-trashed
     * files/subfolders directly inside a folder reached via ownership or a share, mirroring the
     * server's {@code SharedFolderContents}. Backs the desktop app's "browse into a shared folder"
     * and "download this shared folder" actions.
     */
    public record SharedFolderContentsResponse(java.util.List<StoredFileSummaryResponse> files, java.util.List<FolderResponse> subfolders) {
    }

    /**
     * Shape of one entry in the {@code GET /search} response array - a matched file's id/name plus
     * its folder placement. Content is never included, matching {@link StoredFileSummaryResponse}'s
     * own "listing never carries content" convention.
     */
    public record SearchResultResponse(String storedFileId, String fileName, String folderId) {
    }

    /**
     * Shape of one entry in the {@code GET /files/{id}/public-link} response array, and of the
     * object {@code POST /files/{id}/public-link} returns on success - an unauthenticated,
     * read-only link to one file's content. {@code expiresAtEpochMillis} is {@code null} if the
     * link never expires.
     */
    public record PublicFileLinkSummaryResponse(String token, long createdAtEpochMillis, Long expiresAtEpochMillis) {
    }

    /** Body for {@code POST /files/{id}/public-link} - {@code expiresAtEpochMillis} {@code null} creates a link that never expires. */
    public record CreatePublicFileLinkRequest(Long expiresAtEpochMillis) {
    }

    /**
     * Shape of one entry in the {@code GET /files/{id}/versions} response array - a captured prior
     * version of a file's content, identified by its 1-based {@code versionNumber}. Deliberately
     * carries no {@code fileName}/{@code contentType} - the server's own version-history record has
     * neither, since a version only ever preserves content, not the file's descriptive metadata at
     * the time it was captured.
     */
    public record FileVersionSummaryResponse(int versionNumber, long capturedAtEpochMillis, long sizeBytes) {
    }

    /**
     * Shape of one entry in the {@code GET /activity}/{@code GET /files/{id}/activity}/
     * {@code GET /folders/{id}/activity} response envelopes' {@code items} array - a single
     * audit-log entry. The server's own audit event carries more fields ({@code id}, {@code
     * actorAuthUserId}, {@code metadata}) that this record deliberately omits, since Gson simply
     * ignores any JSON field with no matching record component on deserialization.
     */
    public record ActivityEntryResponse(long timestampEpochMillis, String action, String targetId) {
    }

    /** Body for {@code POST /webhooks} - {@code eventTypes} names which event types (e.g. {@code "FILE_UPLOADED"}) this subscription receives. */
    public record RegisterWebhookRequest(String url, java.util.List<String> eventTypes) {
    }

    /**
     * Shape of one entry in the {@code GET /webhooks} response array - a registered webhook
     * subscription, without its secret (only ever handed back once, at creation - see {@link
     * WebhookSubscriptionCreatedResponse}).
     */
    public record WebhookSubscriptionSummaryResponse(String id, String url, java.util.Set<String> eventTypes, long createdAtEpochMillis) {
    }

    /**
     * Shape of the object {@code POST /webhooks} returns on success - the same fields as {@link
     * WebhookSubscriptionSummaryResponse} plus {@code secret}, the value used to sign every
     * delivery's {@code X-Webhook-Signature} header. Shown only this once; store it immediately,
     * since no later call ever returns it again.
     */
    public record WebhookSubscriptionCreatedResponse(String id, String url, java.util.Set<String> eventTypes, String secret, long createdAtEpochMillis) {
    }

    /**
     * Shape of one entry in the {@code GET /webhooks/deliveries} response array - one attempt to
     * deliver one event to one subscription, successful or not.
     */
    public record WebhookDeliveryAttemptResponse(String webhookId, String eventType, String targetId, long attemptedAtEpochMillis,
                                                  int attemptNumber, boolean succeeded, Integer responseStatusCode) {
    }

}
