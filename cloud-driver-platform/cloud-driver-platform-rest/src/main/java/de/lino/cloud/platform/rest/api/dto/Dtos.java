package de.lino.cloud.platform.rest.api.dto;

/**
 * Plain request/response shapes mirrored 1:1 against the server's REST contract - see
 * {@code CloudRestExtension}/{@code DefaultRestFactory} in cloud-driver for the authoritative
 * field names. Deliberately not shared code with the server module: the desktop app only
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

    /**
     * Response of {@code POST /auth/register} ({@code 202 Accepted}) - registration is not
     * complete yet at this point, this only acknowledges that a verification code was e-mailed.
     */
    public record MessageResponse(String message) {
    }

    /**
     * Shape of one entry in the {@code GET /files} response array, and of the object
     * {@code POST /files} returns on success. Mirrors {@code StoredFile}'s Gson-serialized
     * fields; {@code checksum} is left as a raw {@code String} here since the client only
     * needs {@code fileId}/{@code fileName}/{@code contentBase64} to download and save a file.
     * {@code folderId} is not a {@code StoredFile} field at all - the server merges it in from
     * the file's {@code StoredFileOwnership} row (see {@code DefaultRestFactory#toJsonArray}) -
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

}
