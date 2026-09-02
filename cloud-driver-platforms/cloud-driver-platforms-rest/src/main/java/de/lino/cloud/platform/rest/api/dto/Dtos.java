package de.lino.cloud.platform.rest.api.dto;

/**
 * Plain request/response shapes mirrored 1:1 against the server's REST contract - see
 * {@code CloudRestExtension}/{@code DefaultRestFactory} in cloud-driver for the authoritative
 * field names. Deliberately not shared code with the server module: the desktop desktop only
 * ever talks HTTP, so it has no dependency on cloud-driver-api at all.
 */
public final class Dtos {

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

    /** Response of {@code POST /auth/login} and {@code POST /auth/register/confirm} - both return a fresh JWT. */
    public record AuthResponse(String token) {
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
     */
    public record StoredFileSummaryResponse(
            String fileId,
            String fileName,
            String contentType,
            long sizeBytes,
            long createdAtEpochMilli,
            long updatedAtEpochMilli,
            String folderId
    ) {
    }

    /**
     * Shape of one entry in the {@code GET /folders} response array, and of the object
     * {@code POST /folders}/{@code PUT /folders/{id}} return on success. Mirrors {@code
     * Folder}'s Gson-serialized fields; {@code ownerId} is included even though the client
     * never needs to act on it, purely because it's part of the server's actual JSON shape.
     */
    public record FolderResponse(
            String folderId,
            String ownerId,
            String name,
            String parentFolderId,
            long createdAtEpochMillis,
            long modifiedAtEpochMillis
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

    /** Body for {@code PUT /files/{id}/folder} - {@code folderId} {@code null} moves the file back to the root. */
    public record MoveFileRequest(String folderId) {
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
     * currentUploadedBytes} back the Dashboard's storage-quota display - see {@code
     * ICloudUser#getMaxBytesToUpload()}/{@code #getCurrentUploadedBytes()} server-side for what
     * each actually tracks.
     */
    public record CloudUserResponse(String authUserId, long timeStamp, long maxBytesToUpload, long currentUploadedBytes) {
    }

}
