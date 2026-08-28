package de.lino.cloud.platform.app.api.dto;

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
     * Body for {@code POST /auth/login}. The field is named {@code username} to match the
     * server's {@code AuthService.login} contract exactly, even though the value passed in is
     * always an email address.
     */
    public record AuthRequest(String username, String password) {
    }

    /** Response of {@code POST /auth/login}. */
    public record AuthResponse(String token) {
    }

    /** Body for {@code POST /files} (main REST port). */
    public record UploadFileRequest(String fileName, String contentBase64) {
    }

    /**
     * Shape of one entry in the {@code GET /files} response array, and of the object
     * {@code POST /files} returns on success. Mirrors {@code StoredFile}'s Gson-serialized
     * fields; {@code checksum} is left as a raw {@code String} here since the client only
     * needs {@code fileId}/{@code fileName}/{@code contentBase64} to download and save a file.
     */
    public record StoredFileResponse(
            String fileId,
            String fileName,
            String contentType,
            String contentBase64,
            boolean contentCompressed,
            long createdAtEpochMilli,
            long updatedAtEpochMilli
    ) {
    }

    /** Body Javalin's default error responses use ({@code BadRequestResponse} etc. all share this shape). */
    public record ErrorResponse(String title) {
    }

}
