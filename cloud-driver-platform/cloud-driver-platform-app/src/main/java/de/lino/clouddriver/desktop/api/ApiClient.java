package de.lino.clouddriver.desktop.api;

import com.google.gson.Gson;
import de.lino.clouddriver.desktop.api.dto.Dtos.AuthRequest;
import de.lino.clouddriver.desktop.api.dto.Dtos.AuthResponse;
import de.lino.clouddriver.desktop.api.dto.Dtos.ErrorResponse;
import de.lino.clouddriver.desktop.api.dto.Dtos.StoredFileResponse;
import de.lino.clouddriver.desktop.api.dto.Dtos.UploadFileRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Talks to a running cloud-driver instance purely over its REST API - no JDBC driver, no
 * database credentials anywhere on this machine, matching the "desktop app must never see the
 * database" requirement.
 *
 * <p>Two base URLs on purpose, because they're two separate Javalin servers in cloud-driver
 * today: {@code authPanelBaseUrl} for {@code POST /api/register}/{@code POST /api/login} (hosted
 * by {@code AuthPanelServer}, its own port), and {@code apiBaseUrl} for {@code /files} (hosted by
 * {@code cloud-driver-extensions-rest}, port 8080 by default). If a deployment fronts both behind
 * one reverse proxy under different paths, both constructor arguments can simply point at the
 * same host.
 *
 * <p>Not thread-safe by design choice, only by accident of {@link HttpClient} itself being
 * thread-safe - the mutable {@link #token} is a single logged-in session, matching a desktop
 * app's single-user-at-a-time nature. Wrap in your own synchronization if that ever changes.
 */
public final class ApiClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final URI authPanelBaseUrl;
    private final URI apiBaseUrl;

    /** The current session's JWT, once {@link #register}/{@link #login} has succeeded; {@code null} until then. */
    private final AtomicReference<String> token = new AtomicReference<>();

    /**
     * @param authPanelBaseUrl base URL of the auth-panel server, e.g. {@code https://auth.example.com}
     * @param apiBaseUrl       base URL of the main REST API, e.g. {@code https://api.example.com}
     */
    public ApiClient(final String authPanelBaseUrl, final String apiBaseUrl) {
        this.authPanelBaseUrl = URI.create(Objects.requireNonNull(authPanelBaseUrl, "authPanelBaseUrl cannot be null"));
        this.apiBaseUrl = URI.create(Objects.requireNonNull(apiBaseUrl, "apiBaseUrl cannot be null"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** @return {@code true} once {@link #register}/{@link #login} has produced a token still held in memory. */
    public boolean isAuthenticated() {
        return this.token.get() != null;
    }

    /** Restores a previously persisted token (e.g. loaded from the OS keychain) without a fresh login. */
    public void restoreSession(final String previouslyIssuedToken) {
        this.token.set(Objects.requireNonNull(previouslyIssuedToken, "previouslyIssuedToken cannot be null"));
    }

    /** Discards the in-memory token; the caller is responsible for also clearing any persisted copy. */
    public void logout() {
        this.token.set(null);
    }

    /**
     * {@code POST /api/register} on the auth-panel server - creates a new account and, on
     * success, logs it in immediately, exactly like {@code AuthPanelServer#registerAndLogin}
     * does server-side.
     *
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 409} if the email is already registered, {@code 400} if the
     *                       email/password fail validation, or any other transport/HTTP failure
     */
    public String register(final String emailAddress, final String password) throws ApiException {
        final AuthResponse response = this.post(
                this.authPanelBaseUrl.resolve("/api/register"), new AuthRequest(emailAddress, password),
                AuthResponse.class, false
        );
        this.token.set(response.token());
        return response.token();
    }

    /**
     * {@code POST /api/login} on the auth-panel server.
     *
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 401} on wrong credentials, or any other transport/HTTP failure
     */
    public String login(final String emailAddress, final String password) throws ApiException {
        final AuthResponse response = this.post(
                this.authPanelBaseUrl.resolve("/api/login"), new AuthRequest(emailAddress, password),
                AuthResponse.class, false
        );
        this.token.set(response.token());
        return response.token();
    }

    /**
     * {@code POST /files} on the main REST API - uploads {@code content} under {@code fileName}.
     * Base64-encodes {@code content} itself, matching {@code DefaultRestFactory}'s expected
     * {@code contentBase64} field.
     *
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public StoredFileResponse uploadFile(final String fileName, final byte[] content) throws ApiException {
        final String encoded = Base64.getEncoder().encodeToString(content);
        return this.post(
                this.apiBaseUrl.resolve("/files"), new UploadFileRequest(fileName, encoded),
                StoredFileResponse.class, true
        );
    }

    /**
     * {@code GET /files} on the main REST API - every file tracked as owned by the
     * authenticated caller.
     *
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public List<StoredFileResponse> listFiles() throws ApiException {
        final HttpRequest request = this.requestBuilder(this.apiBaseUrl.resolve("/files"), true)
                .GET()
                .build();
        final StoredFileResponse[] files = this.send(request, StoredFileResponse[].class);
        return List.of(files);
    }

    /**
     * {@code DELETE /files/{id}} on the main REST API.
     *
     * @throws ApiException {@code 404} if {@code fileId} doesn't exist or isn't owned by the
     *                       caller, {@code 401} if not logged in / token expired
     */
    public void deleteFile(final String fileId) throws ApiException {
        final HttpRequest request = this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId), true)
                .DELETE()
                .build();
        this.send(request, Void.class);
    }

    // --- internals -------------------------------------------------------

    private <T> T post(final URI uri, final Object body, final Class<T> responseType, final boolean authenticated)
            throws ApiException {
        final HttpRequest request = this.requestBuilder(uri, authenticated)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        return this.send(request, responseType);
    }

    private HttpRequest.Builder requestBuilder(final URI uri, final boolean authenticated) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        if (authenticated) {
            final String currentToken = this.token.get();
            if (currentToken == null) {
                throw new IllegalStateException("@ApiClient: no active session - call register()/login() first");
            }
            builder.header("Authorization", "Bearer " + currentToken);
        }
        return builder;
    }

    private <T> T send(final HttpRequest request, final Class<T> responseType) throws ApiException {
        final HttpResponse<String> response;
        try {
            response = this.httpClient.send(request, BodyHandlers.ofString());
        } catch (final IOException e) {
            throw new ApiException(0, "network error calling " + request.uri(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted calling " + request.uri(), e);
        }

        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            if (responseType == Void.class || response.body() == null || response.body().isBlank()) {
                return null;
            }
            return GSON.fromJson(response.body(), responseType);
        }

        final String message = extractErrorMessage(response.body());
        throw new ApiException(status, message, null);
    }

    private static String extractErrorMessage(final String body) {
        if (body == null || body.isBlank()) {
            return "request failed with no response body";
        }
        try {
            final ErrorResponse error = GSON.fromJson(body, ErrorResponse.class);
            return error != null && error.title() != null ? error.title() : body;
        } catch (final RuntimeException malformedJson) {
            return body;
        }
    }

    /** Thrown for any non-2xx response or transport failure; {@link #statusCode} is {@code 0} for the latter. */
    public static final class ApiException extends Exception {

        private final int statusCode;

        public ApiException(final int statusCode, final String message, final Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        /** @return the HTTP status code, or {@code 0} if this was a transport-level failure (no response received) */
        public int statusCode() {
            return this.statusCode;
        }

        /** @return {@code true} if this was a {@code 401 Unauthorized} - the caller should prompt for login again */
        public boolean isUnauthorized() {
            return this.statusCode == 401;
        }

    }

}
