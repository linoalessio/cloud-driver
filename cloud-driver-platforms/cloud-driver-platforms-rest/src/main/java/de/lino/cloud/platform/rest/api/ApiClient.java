package de.lino.cloud.platform.rest.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import de.lino.cloud.platform.rest.api.dto.Dtos.AuditLogEntryResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.AuthRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.AuthResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.AuthUserResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.BeginDownloadUrlResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.BeginUploadUrlRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.BeginUploadUrlResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.ChangeEmailRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.CompleteUploadRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.CloudUserResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.ConfirmChangeEmailRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.ConfirmPasswordResetRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.ConfirmRegistrationRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.CreateFolderRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.EmailExistsResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.ErrorResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.MessageResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.MetricsSnapshotResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.MoveFileRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.Page;
import de.lino.cloud.platform.rest.api.dto.Dtos.RefreshRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.RequestPasswordResetRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.SharedByMeCountResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.SharedFileSummaryResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.SharedFolderContentsResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.SharedFolderSummaryResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.ShareRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.TrashedFileSummaryResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.TrashedFolderSummaryResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.MeResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.UpdateFolderRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.UpdateThemeRequest;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Talks to a running cloud-driver instance purely over its REST API - no JDBC driver, no
 * database credentials anywhere on this machine, matching the "desktop client must never see the
 * database" requirement.
 *
 * <p><b>Performance shape, read this before adding a new call.</b> Every network operation has a
 * true non-blocking async form (an {@code *Async} method returning {@link CompletableFuture},
 * built directly on {@link HttpClient#sendAsync}) and, where useful, a blocking convenience form
 * built directly on {@link HttpClient#send} - the blocking forms are <b>not</b> implemented as
 * {@code asyncMethod(...).join()}, since {@link CompletableFuture#join()} cannot be interrupted
 * (a thread blocked in {@code join()} ignores {@link Thread#interrupt()} until the future
 * actually completes), whereas the blocking {@link HttpClient#send} overload responds to
 * interruption immediately - important since callers in practice run these from a cancellable
 * virtual thread (see {@code FileListController#runAuthenticated} in {@code
 * cloud-driver-platform-desktop}), not the calling thread itself. Both forms share one {@link
 * #parseResponse} for response handling, so behavior never drifts between them.
 *
 * <p>Response bodies are read via {@link BodyHandlers#ofInputStream()}, never {@code ofString()}.
 * {@code GET /files} itself returns lightweight {@link StoredFileSummaryResponse} entries with no
 * content at all (see {@code CloudUserService#listFileSummaries} server-side) - {@link
 * #listFiles()}/{@link #listFilesAsync()}/{@link #listFilesStream()} never touch a single file's
 * content just to list it; fetch a specific file's full base64 content afterwards via {@link
 * #downloadFile(String)}. {@link #listFilesStream()} still avoids materializing the whole
 * response array at once - see its own Javadoc.
 *
 * <p>{@link #uploadFile(Path)}/{@link #uploadFileAsync(Path)} stream the request body straight
 * from disk via {@link BodyPublishers#ofFile}, rather than requiring the caller to first read the
 * whole file into a Java {@code byte[]} (the only option before this class was extracted into its
 * own module - see {@link #uploadFile(String, byte[])} for that still-supported form, kept for
 * callers whose content isn't already a file on disk). This avoids doubling peak memory for a
 * large upload and lets the JDK/OS use a more efficient transfer path than an application-level
 * byte copy.
 *
 * <p>The underlying {@link HttpClient} is built once, requests {@link HttpClient.Version#HTTP_2}
 * (the JDK default already, made explicit here) and reuses one connection pool for every call
 * this instance makes - concurrent calls against the same host are transparently multiplexed over
 * one TCP connection when the server negotiates HTTP/2 (true in this deployment: Caddy, fronting
 * the real Javalin/Jetty server, terminates TLS and speaks HTTP/2 to the client - see {@code
 * cloud-driver-platform-desktop}'s Caddyfile bullet), falling back to HTTP/1.1 automatically via ALPN
 * otherwise. {@link #uploadFilesAsync}/{@link #deleteFilesAsync} rely on exactly this to run many
 * transfers genuinely concurrently rather than one at a time.
 *
 * <p>Every async dependent stage in this class runs on {@link #executor()} - a dedicated {@link
 * Executors#newVirtualThreadPerTaskExecutor() virtual-thread-per-task executor} passed to {@link
 * HttpClient.Builder#executor}, so response parsing (and anything a caller chains via {@link
 * #executor()}, e.g. {@link SessionManager}'s own async methods) never runs on an internal JDK
 * I/O thread. Virtual threads make this cheap even for the blocking OS calls {@link
 * de.lino.cloud.platform.rest.api.session.TokenStore} implementations make. Call {@link #close()}
 * (this class is {@link AutoCloseable}) when the client is no longer needed, to shut that executor
 * down.
 *
 * <p>Two base URLs on purpose, but they resolve to the <b>same</b> single Javalin server
 * ({@code cloud-driver-extensions-rest}'s {@code CloudRestExtension}, one {@code rest-server-port})
 * behind two different hostnames/reverse-proxy vhosts - {@code authPanelBaseUrl} for
 * {@code POST /auth/login}, {@code apiBaseUrl} for {@code /files}/{@code /cloudUsers}. There is no
 * separate "auth-panel" server today (an earlier {@code AuthPanelServer}/{@code cloud-driver-extensions-web}
 * module that used to host {@code /api/register}/{@code /api/login} on its own port has since been
 * removed) - the split is kept here purely so a deployment is free to front auth/files under
 * different subdomains if it wants to; both constructor arguments can just as well point at the
 * same host.
 *
 * <p>Registration is a two-step, e-mail-verified flow (see {@code cloud-driver}'s {@code
 * IAuthService}/{@code DefaultRestFactory} Javadoc for the full picture): {@link #register}
 * calls {@code POST /auth/register}, which only e-mails a 6-digit verification code (valid 10
 * minutes) and does <b>not</b> create the account or return a token; {@link
 * #confirmRegistration} then calls {@code POST /auth/register/confirm} with that code, which is
 * what actually creates the account - only that second call returns a JWT the same way {@link
 * #login} does.
 *
 * <p>Not thread-safe by design choice, only by accident of {@link HttpClient} itself being
 * thread-safe - the mutable {@link #token} is a single logged-in session, matching a desktop
 * client's single-user-at-a-time nature. Wrap in your own synchronization if that ever changes.
 */
public final class ApiClient implements AutoCloseable {

    /** Default cap on simultaneously in-flight transfers for {@link #uploadFilesAsync(Map)}. */
    public static final int DEFAULT_MAX_CONCURRENT_TRANSFERS = 8;

    /**
     * Timeout for the whole request-response exchange of every call in this class except an
     * upload/download of file content, which instead uses the longer {@link #TRANSFER_TIMEOUT}.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Timeout for requests that transfer file content ({@code POST /files}, {@code GET
     * /files/{id}}) rather than a small JSON payload. {@link #REQUEST_TIMEOUT} covers the whole
     * request-response exchange, including the time spent writing/reading the body - 30 seconds
     * is enough for a login or a file listing, but a multi-megabyte upload/download on a slower
     * connection can easily exceed it (e.g. a 3.9 MB file needs only ~1 Mbit/s effective
     * throughput to blow past 30s), surfacing as a spurious {@code HttpTimeoutException} even
     * though nothing is actually stuck.
     */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);

    /** Path of {@link #refresh}'s route, checked by {@link #canRetryWithRefresh} to avoid ever attempting to auto-refresh the refresh call itself. */
    private static final String REFRESH_PATH = "/auth/refresh";

    /** Path of {@link #revokeRefreshToken}'s route. */
    private static final String LOGOUT_PATH = "/auth/logout";

    /** Shared Gson instance used for every request/response (de)serialization in this class. */
    private static final Gson GSON = new Gson();

    /** Backs every async call and every {@link de.lino.cloud.platform.rest.api.session.TokenStore} call chained onto it; see {@link #executor()}. */
    private final ExecutorService executor;

    /** The single {@link HttpClient} instance every request in this class is sent through. */
    private final HttpClient httpClient;

    /** Base URL of the host serving the auth routes ({@code /auth/login}, {@code /auth/register}, {@code /auth/reset-password}). */
    private final URI authPanelBaseUrl;

    /** Base URL of the host serving the main REST API ({@code /files}, {@code /folders}, {@code /cloudUsers}, {@code /auth/change-email}). */
    private final URI apiBaseUrl;

    /** The current session's access JWT, once {@link #login} has succeeded; {@code null} until then. */
    private final AtomicReference<String> token = new AtomicReference<>();

    /**
     * The current session's refresh token, once {@link #login} has succeeded; {@code null} until
     * then. Rotated on every successful {@link #refresh}/{@link #refreshAsync} call - see {@link
     * AuthResponse}'s own Javadoc. Used automatically by {@link #send(HttpRequest, Type)}/{@link
     * #sendAsync(HttpRequest, Type)} to transparently retry an authenticated call once after a
     * {@code 401} (see {@link #canRetryWithRefresh}), so a caller doesn't need to react to an
     * expired access token itself as long as the refresh token is still valid.
     */
    private final AtomicReference<String> refreshToken = new AtomicReference<>();

    /**
     * Builds a client backed by a fresh {@link HttpClient} and virtual-thread executor; call
     * {@link #close()} once done with it.
     *
     * @param authPanelBaseUrl base URL of the auth-panel server, e.g. {@code https://auth.example.com}
     * @param apiBaseUrl       base URL of the main REST API, e.g. {@code https://api.example.com}
     */
    public ApiClient(final String authPanelBaseUrl, final String apiBaseUrl) {
        this.authPanelBaseUrl = URI.create(Objects.requireNonNull(authPanelBaseUrl, "authPanelBaseUrl cannot be null"));
        this.apiBaseUrl = URI.create(Objects.requireNonNull(apiBaseUrl, "apiBaseUrl cannot be null"));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_2)
                .executor(this.executor)
                .build();
    }

    /**
     * The executor every async call in this class completes on - exposed so a caller building
     * further async chains on top of one of the {@code *Async} methods here (e.g. {@link
     * SessionManager}, or application code) can dispatch its own blocking follow-up work
     * ({@code .thenApplyAsync(fn, apiClient.executor())}) here too, instead of accidentally
     * running it on an internal JDK HTTP-client thread.
     */
    public Executor executor() {
        return this.executor;
    }

    /**
     * The main REST API's base URL - the same host {@code /files}/{@code /folders}/{@code
     * /cloudUsers} calls go to. Exposed for {@link de.lino.cloud.platform.rest.api.push.LiveUpdateClient}
     * (item 10, live push via WebSocket - see {@code architecture/SERVICES.md}), which derives
     * its {@code wss://}/{@code ws://} URL from it rather than duplicating this client's own
     * base-URL configuration.
     */
    public URI apiBaseUrl() {
        return this.apiBaseUrl;
    }

    /**
     * The underlying {@link HttpClient}, exposed so {@link
     * de.lino.cloud.platform.rest.api.push.LiveUpdateClient} can open its WebSocket connection
     * through the exact same client (connection pool, {@link HttpClient.Version#HTTP_2}
     * negotiation, {@link #executor()}) this instance already uses for every HTTP call, rather
     * than standing up a second, independently-configured one.
     */
    public HttpClient httpClient() {
        return this.httpClient;
    }

    /** @return {@code true} once {@link #login} has produced a token still held in memory. */
    public boolean isAuthenticated() {
        return this.token.get() != null;
    }

    /**
     * @return the raw access JWT currently held in memory (set by {@link #login}/{@link
     * #register}'s confirm step/{@link #restoreSession(String, String)}/{@link #refresh}), or
     * empty if not authenticated. Added for {@link SessionManager}-based session-restore callers
     * that need the token itself after a successful {@link SessionManager#tryRestoreSession()}/
     * {@code tryRestoreSessionAsync()} (e.g. to decode its {@code sub} claim client-side) rather
     * than just the boolean "did it work" answer that method returns.
     */
    public Optional<String> currentToken() {
        return Optional.ofNullable(this.token.get());
    }

    /**
     * @return the raw refresh token currently held in memory, or empty if none has ever been
     * issued to this client. Added so a caller persisting the session (e.g. {@link
     * SessionManager}) can save both tokens together - see {@link #restoreSession(String, String)}
     * for the counterpart that restores both.
     */
    public Optional<String> currentRefreshToken() {
        return Optional.ofNullable(this.refreshToken.get());
    }

    /**
     * Restores a previously persisted access/refresh token pair (e.g. loaded from the OS
     * keychain) without a fresh login - the counterpart to {@link #currentToken()}/{@link
     * #currentRefreshToken()}.
     *
     * @param previouslyIssuedAccessToken the access JWT to restore
     * @param previouslyIssuedRefreshToken the refresh token to restore alongside it - required
     *     (not {@code null}) so every restored session can transparently auto-refresh the same
     *     way a freshly logged-in one does; a caller with only a bare access token on hand (no
     *     refresh token ever persisted, e.g. from a session predating this feature) has no
     *     session worth restoring and should fall back to a fresh login instead
     */
    public void restoreSession(final String previouslyIssuedAccessToken, final String previouslyIssuedRefreshToken) {
        this.token.set(Objects.requireNonNull(previouslyIssuedAccessToken, "previouslyIssuedAccessToken cannot be null"));
        this.refreshToken.set(Objects.requireNonNull(previouslyIssuedRefreshToken, "previouslyIssuedRefreshToken cannot be null"));
    }

    /**
     * Discards the in-memory access and refresh tokens; the caller is responsible for also
     * clearing any persisted copy (see {@link #revokeRefreshToken()} to additionally invalidate
     * the refresh token server-side first).
     */
    public void logout() {
        this.token.set(null);
        this.refreshToken.set(null);
    }

    // --- auth ---------------------------------------------------------

    /**
     * {@code POST /auth/login} on the auth-panel host - the only auth route the server exposes.
     * The request body's field is literally named {@code username} (see {@link AuthRequest}),
     * even though {@code emailAddress} is what's actually passed for it.
     *
     * @param emailAddress the account's e-mail address, sent as the request's {@code username} field
     * @param password     the account's plaintext password
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 401} on wrong credentials, or any other transport/HTTP failure
     */
    public String login(final String emailAddress, final String password) throws ApiException {
        final AuthResponse response = this.send(this.loginRequest(emailAddress, password), AuthResponse.class);
        this.applyTokens(response);
        return response.token();
    }

    /** Async form of {@link #login} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<String> loginAsync(final String emailAddress, final String password) {
        return this.sendAsync(this.loginRequest(emailAddress, password), AuthResponse.class)
                .thenApply(response -> {
                    this.applyTokens(response);
                    return response.token();
                });
    }

    /** Builds the {@code POST /auth/login} request against {@link #authPanelBaseUrl}, unauthenticated. */
    private HttpRequest loginRequest(final String emailAddress, final String password) {
        return this.postRequest(this.authPanelBaseUrl.resolve("/auth/login"), new AuthRequest(emailAddress, password), false);
    }

    /**
     * {@code POST /auth/register} on the auth-panel host - step one of registration: validates
     * {@code emailAddress} and e-mails a verification code. Does <b>not</b> create the account or
     * return a token - call {@link #confirmRegistration} with the e-mailed code to actually
     * create it. Same request shape as {@link #login} (see {@link AuthRequest}'s own Javadoc on
     * the {@code username} field naming).
     *
     * @param emailAddress the address to register - sent as the request's {@code username} field
     * @param password     the plaintext password to hash and store once the account is confirmed
     * @return the server's acknowledgement message - purely informational, nothing to act on
     * @throws ApiException {@code 409} if an account already exists for {@code emailAddress},
     *                       {@code 400} if it fails the server's email validity/deliverability
     *                       check, or any other transport/HTTP failure
     */
    public MessageResponse register(final String emailAddress, final String password) throws ApiException {
        return this.send(this.registerRequest(emailAddress, password), MessageResponse.class);
    }

    /** Async form of {@link #register} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<MessageResponse> registerAsync(final String emailAddress, final String password) {
        return this.sendAsync(this.registerRequest(emailAddress, password), MessageResponse.class);
    }

    /** Builds the {@code POST /auth/register} request against {@link #authPanelBaseUrl}, unauthenticated. */
    private HttpRequest registerRequest(final String emailAddress, final String password) {
        return this.postRequest(this.authPanelBaseUrl.resolve("/auth/register"), new AuthRequest(emailAddress, password), false);
    }

    /**
     * {@code POST /auth/register/confirm} on the auth-panel host - step two of registration:
     * submits the verification code {@link #register} caused the server to e-mail, and is what
     * actually creates the account. Same response shape as {@link #login} - a successful
     * confirmation leaves the caller already authenticated.
     *
     * @param emailAddress the address being confirmed - the same one passed to {@link #register}
     * @param code          the 6-digit verification code the server e-mailed
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 400} if the code is missing, expired, or does not match, or
     *                       any other transport/HTTP failure
     */
    public String confirmRegistration(final String emailAddress, final String code) throws ApiException {
        final AuthResponse response = this.send(this.confirmRegistrationRequest(emailAddress, code), AuthResponse.class);
        this.applyTokens(response);
        return response.token();
    }

    /** Async form of {@link #confirmRegistration} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<String> confirmRegistrationAsync(final String emailAddress, final String code) {
        return this.sendAsync(this.confirmRegistrationRequest(emailAddress, code), AuthResponse.class)
                .thenApply(response -> {
                    this.applyTokens(response);
                    return response.token();
                });
    }

    /** Builds the {@code POST /auth/register/confirm} request against {@link #authPanelBaseUrl}, unauthenticated. */
    private HttpRequest confirmRegistrationRequest(final String emailAddress, final String code) {
        return this.postRequest(this.authPanelBaseUrl.resolve("/auth/register/confirm"),
                new ConfirmRegistrationRequest(emailAddress, code), false);
    }

    /**
     * {@code POST /auth/reset-password} on the auth-panel host - starts a password reset: if (and
     * only if) an account exists under {@code emailAddress}, e-mails a 6-digit verification code
     * (valid 10 minutes). Responds identically either way - the server never reveals whether an
     * account exists under {@code emailAddress} through this call. Does <b>not</b> change the
     * password or return a token - call {@link #confirmPasswordReset} with the e-mailed code and
     * a chosen new password to actually complete the reset.
     *
     * @param emailAddress the address to attempt a reset for - never confirmed to exist or not
     * @return the server's acknowledgement message - purely informational, nothing to act on
     * @throws ApiException any transport/HTTP failure
     */
    public MessageResponse requestPasswordReset(final String emailAddress) throws ApiException {
        return this.send(this.requestPasswordResetRequest(emailAddress), MessageResponse.class);
    }

    /** Async form of {@link #requestPasswordReset} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<MessageResponse> requestPasswordResetAsync(final String emailAddress) {
        return this.sendAsync(this.requestPasswordResetRequest(emailAddress), MessageResponse.class);
    }

    /** Builds the {@code POST /auth/reset-password} request against {@link #authPanelBaseUrl}, unauthenticated. */
    private HttpRequest requestPasswordResetRequest(final String emailAddress) {
        return this.postRequest(this.authPanelBaseUrl.resolve("/auth/reset-password"), new RequestPasswordResetRequest(emailAddress), false);
    }

    /**
     * {@code POST /auth/reset-password/confirm} on the auth-panel host - completes a password
     * reset previously started by {@link #requestPasswordReset}: submits the e-mailed code
     * together with {@code newPassword}, which becomes the account's password on success. Same
     * response shape as {@link #login}/{@link #confirmRegistration} - a successful reset leaves
     * the caller already authenticated under the new password.
     *
     * @param emailAddress the address the reset was requested for
     * @param code          the 6-digit verification code the server e-mailed
     * @param newPassword   the plaintext password to set on success
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 400} if the code is missing, expired, or does not match, or
     *                       any other transport/HTTP failure
     */
    public String confirmPasswordReset(final String emailAddress, final String code, final String newPassword) throws ApiException {
        final AuthResponse response = this.send(this.confirmPasswordResetRequest(emailAddress, code, newPassword), AuthResponse.class);
        this.applyTokens(response);
        return response.token();
    }

    /** Async form of {@link #confirmPasswordReset} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<String> confirmPasswordResetAsync(final String emailAddress, final String code, final String newPassword) {
        return this.sendAsync(this.confirmPasswordResetRequest(emailAddress, code, newPassword), AuthResponse.class)
                .thenApply(response -> {
                    this.applyTokens(response);
                    return response.token();
                });
    }

    /** Builds the {@code POST /auth/reset-password/confirm} request against {@link #authPanelBaseUrl}, unauthenticated. */
    private HttpRequest confirmPasswordResetRequest(final String emailAddress, final String code, final String newPassword) {
        return this.postRequest(this.authPanelBaseUrl.resolve("/auth/reset-password/confirm"),
                new ConfirmPasswordResetRequest(emailAddress, code, newPassword), false);
    }

    /**
     * {@code POST /auth/change-email} on the main REST API - bearer-gated, unlike {@link #login}/
     * {@link #register}/{@link #requestPasswordReset} above: the account being changed is the
     * currently signed-in caller's own, resolved server-side from its bearer token, not from
     * anything in the request body. Starts an e-mail change: e-mails a 6-digit verification code
     * (valid 10 minutes) to {@code newEmailAddress} itself, proving the caller controls it before
     * the account ever moves there. Does <b>not</b> change the address yet - call {@link
     * #confirmEmailChange} with that code to actually apply it.
     *
     * @param newEmailAddress the address to move the signed-in account to, pending confirmation
     * @return the server's acknowledgement message - purely informational, nothing to act on
     * @throws ApiException {@code 409} if another account already exists under {@code
     *                       newEmailAddress}, {@code 400} if it fails the server's email
     *                       validity/deliverability check, {@code 401} if not logged in / token
     *                       expired, or any other transport/HTTP failure
     */
    public MessageResponse requestEmailChange(final String newEmailAddress) throws ApiException {
        return this.send(this.requestEmailChangeRequest(newEmailAddress), MessageResponse.class);
    }

    /** Async form of {@link #requestEmailChange} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<MessageResponse> requestEmailChangeAsync(final String newEmailAddress) {
        return this.sendAsync(this.requestEmailChangeRequest(newEmailAddress), MessageResponse.class);
    }

    /** Builds the bearer-gated {@code POST /auth/change-email} request against {@link #apiBaseUrl}. */
    private HttpRequest requestEmailChangeRequest(final String newEmailAddress) {
        return this.postRequest(this.apiBaseUrl.resolve("/auth/change-email"), new ChangeEmailRequest(newEmailAddress), true);
    }

    /**
     * {@code POST /auth/change-email/confirm} on the main REST API - completes an e-mail change
     * previously started by {@link #requestEmailChange}: submits the e-mailed code, which (on
     * success) actually changes the signed-in caller's account e-mail address server-side. Does
     * <b>not</b> return/store a fresh token - a JWT's subject is the account's id, never its
     * e-mail address, so the session already held by this {@code ApiClient} remains valid across
     * the change.
     *
     * @param code the 6-digit verification code e-mailed to the new address by {@link #requestEmailChange}
     * @return the server's acknowledgement message - purely informational, nothing to act on
     * @throws ApiException {@code 400} if the code is missing, expired, or does not match, {@code
     *                       401} if not logged in / token expired, or any other transport/HTTP failure
     */
    public MessageResponse confirmEmailChange(final String code) throws ApiException {
        return this.send(this.confirmEmailChangeRequest(code), MessageResponse.class);
    }

    /** Async form of {@link #confirmEmailChange} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<MessageResponse> confirmEmailChangeAsync(final String code) {
        return this.sendAsync(this.confirmEmailChangeRequest(code), MessageResponse.class);
    }

    /** Builds the bearer-gated {@code POST /auth/change-email/confirm} request against {@link #apiBaseUrl}. */
    private HttpRequest confirmEmailChangeRequest(final String code) {
        return this.postRequest(this.apiBaseUrl.resolve("/auth/change-email/confirm"), new ConfirmChangeEmailRequest(code), true);
    }

    /**
     * {@code POST /auth/refresh} on the auth-panel host - exchanges the currently held refresh
     * token for a fresh access/refresh pair, without requiring a fresh password login. Called
     * automatically by every other authenticated method here on a {@code 401} (see {@link
     * #canRetryWithRefresh}) - a caller does not normally need to invoke this directly, though
     * it's exposed for a caller (e.g. {@link SessionManager}) that wants to proactively refresh
     * ahead of an access token's known expiry.
     *
     * <p>Rotates the held refresh token: the value returned here always differs from the one this
     * call was made with, and the old one is invalidated as part of the same server-side call - see
     * {@code IAuthService#refresh}'s own Javadoc server-side.
     *
     * @return the freshly issued access token, already stored (alongside the rotated refresh
     *         token) for subsequent calls
     * @throws ApiException {@code 401} if the held refresh token is missing, expired, or already
     *                       used/revoked, or any other transport/HTTP failure
     * @throws IllegalStateException if no refresh token is currently held (never logged in, or
     *                                never restored via {@link #restoreSession(String, String)})
     */
    public String refresh() throws ApiException {
        final AuthResponse response = this.send(this.refreshRequest(this.requireCurrentRefreshToken()), AuthResponse.class);
        this.applyTokens(response);
        return response.token();
    }

    /** Async form of {@link #refresh} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<String> refreshAsync() {
        return this.sendAsync(this.refreshRequest(this.requireCurrentRefreshToken()), AuthResponse.class)
                .thenApply(response -> {
                    this.applyTokens(response);
                    return response.token();
                });
    }

    private HttpRequest refreshRequest(final String currentRefreshToken) {
        return this.postRequest(this.authPanelBaseUrl.resolve(REFRESH_PATH), new RefreshRequest(currentRefreshToken), false);
    }

    private String requireCurrentRefreshToken() {
        final String currentRefreshToken = this.refreshToken.get();
        if (currentRefreshToken == null) {
            throw new IllegalStateException("@ApiClient: no refresh token held - call login()/restoreSession(access, refresh) first");
        }
        return currentRefreshToken;
    }

    private void applyTokens(final AuthResponse response) {
        this.token.set(response.token());
        this.refreshToken.set(response.refreshToken());
    }

    /**
     * {@code POST /auth/logout} on the auth-panel host - best-effort, idempotent server-side
     * revocation of the currently held refresh token (see {@code IAuthService#revokeRefreshToken}
     * server-side), so a stolen refresh token can't be used after this client has explicitly
     * logged out. A no-op if no refresh token is currently held (nothing to revoke) - unlike
     * {@link #refresh}, this deliberately does <b>not</b> throw {@link IllegalStateException} in
     * that case, since a caller logging out an already-logged-out (or restore-failed) session is a
     * normal occurrence, not a bug. Does <b>not</b> clear the in-memory session itself - call
     * {@link #logout()} (or let {@link SessionManager#logout}/{@code logoutAsync} do both) for that.
     *
     * @throws ApiException any transport/HTTP failure - a caller that wants a best-effort logout
     *                       (e.g. {@link SessionManager}) should catch and ignore this rather than
     *                       let a network error block clearing the local session
     */
    public void revokeRefreshToken() throws ApiException {
        final String currentRefreshToken = this.refreshToken.get();
        if (currentRefreshToken == null) {
            return;
        }
        this.send(this.logoutRequest(currentRefreshToken), Void.class);
    }

    /** Async form of {@link #revokeRefreshToken} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> revokeRefreshTokenAsync() {
        final String currentRefreshToken = this.refreshToken.get();
        if (currentRefreshToken == null) {
            return CompletableFuture.completedFuture(null);
        }
        return this.sendAsync(this.logoutRequest(currentRefreshToken), Void.class);
    }

    private HttpRequest logoutRequest(final String currentRefreshToken) {
        return this.postRequest(this.authPanelBaseUrl.resolve(LOGOUT_PATH), new RefreshRequest(currentRefreshToken), false);
    }

    // --- files: upload --------------------------------------------------

    /**
     * {@code POST /files?fileName=<url-encoded name>} on the main REST API - uploads {@code
     * content} as a raw binary body ({@code application/octet-stream}), not base64-encoded
     * JSON: base64 would inflate the transferred size by ~37% and force the server to parse one
     * huge JSON string field, both pure overhead that matters once files get into the tens/
     * hundreds of MB. {@code fileName} travels as a URL-encoded query parameter instead of a
     * JSON body field for the same reason - see {@code DefaultRestFactory#handleUploadFile}'s
     * own Javadoc for the server side of this.
     *
     * <p>Prefer {@link #uploadFile(Path)} when {@code content} is already a file on disk - it
     * streams straight from the filesystem instead of requiring the whole file in memory first.
     *
     * <p>Returns a {@link StoredFileSummaryResponse} - the same content-free shape {@link
     * #listFiles()} returns - not the uploaded content echoed back: the caller already has the
     * bytes it just sent, so round-tripping them again (base64-encoded, in one JSON document) was
     * pure waste, and the actual ceiling on how large a file this client could handle before it
     * was fixed. Fetch full content afterward via {@link #downloadFile(String)} if it's ever
     * actually needed again.
     *
     * @param fileName the name to store the file under
     * @param content  the file's raw bytes, sent as the request body verbatim
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public StoredFileSummaryResponse uploadFile(final String fileName, final byte[] content) throws ApiException {
        return this.uploadFile(fileName, content, null);
    }

    /** Async form of {@link #uploadFile(String, byte[])} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileSummaryResponse> uploadFileAsync(final String fileName, final byte[] content) {
        return this.uploadFileAsync(fileName, content, null);
    }

    /**
     * Same as {@link #uploadFile(String, byte[])}, placing the new file directly into {@code
     * folderId} instead of the root.
     *
     * @param fileName the name to store the file under
     * @param content  the file's raw bytes, sent as the request body verbatim
     * @param folderId the destination folder's id, or {@code null} for the root
     * @throws ApiException {@code 401} if not logged in / token expired, {@code 404} if {@code
     *                       folderId} doesn't exist or isn't owned by the caller, or any other failure
     */
    public StoredFileSummaryResponse uploadFile(final String fileName, final byte[] content, final String folderId) throws ApiException {
        return this.send(this.uploadFileRequest(fileName, content, folderId), StoredFileSummaryResponse.class);
    }

    /** Async form of {@link #uploadFile(String, byte[], String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileSummaryResponse> uploadFileAsync(final String fileName, final byte[] content, final String folderId) {
        return this.sendAsync(this.uploadFileRequest(fileName, content, folderId), StoredFileSummaryResponse.class);
    }

    /** Builds the {@code POST /files} request against {@link #apiBaseUrl}, with {@code content} as a raw byte-array body. */
    private HttpRequest uploadFileRequest(final String fileName, final byte[] content, final String folderId) {
        return this.uploadRequestBuilder(fileName, folderId)
                .POST(BodyPublishers.ofByteArray(content))
                .build();
    }

    /**
     * Same route as {@link #uploadFile(String, byte[])}, but streams the request body directly
     * from {@code filePath} via {@link BodyPublishers#ofFile} instead of loading the file into a
     * {@code byte[]} first - the file's own name ({@link Path#getFileName()}) is used as the
     * uploaded {@code fileName}. Avoids doubling peak memory usage for a large file (the content
     * would otherwise exist both as this process's file-system page cache read and as a
     * duplicate on-heap {@code byte[]}) and lets the JDK compute an exact {@code Content-Length}
     * up front rather than buffering to find it. Returns the same content-free {@link
     * StoredFileSummaryResponse} {@link #uploadFile(String, byte[])} does - see that method's
     * own Javadoc for why.
     *
     * @param filePath the local file to stream as the upload body; its own file name is used as the uploaded {@code fileName}
     * @throws ApiException {@code 401} if not logged in / token expired, if {@code filePath}
     *                       doesn't exist or can't be read, or any other failure
     */
    public StoredFileSummaryResponse uploadFile(final Path filePath) throws ApiException {
        return this.uploadFile(filePath.getFileName().toString(), filePath);
    }

    /**
     * Same as {@link #uploadFile(Path)}, with an explicit {@code fileName} instead of the path's own file name.
     *
     * @param fileName the name to store the file under
     * @param filePath the local file to stream as the upload body
     * @throws ApiException {@code 401} if not logged in / token expired, if {@code filePath}
     *                       doesn't exist or can't be read, or any other failure
     */
    public StoredFileSummaryResponse uploadFile(final String fileName, final Path filePath) throws ApiException {
        return this.uploadFile(fileName, filePath, null);
    }

    /**
     * Same as {@link #uploadFile(String, Path)}, placing the new file directly into {@code folderId} instead of the root.
     *
     * @param fileName the name to store the file under
     * @param filePath the local file to stream as the upload body
     * @param folderId the destination folder's id, or {@code null} for the root
     * @throws ApiException {@code 401} if not logged in / token expired, {@code 404} if {@code
     *                       folderId} doesn't exist or isn't owned by the caller, if {@code
     *                       filePath} doesn't exist or can't be read, or any other failure
     */
    public StoredFileSummaryResponse uploadFile(final String fileName, final Path filePath, final String folderId) throws ApiException {
        return this.uploadFile(fileName, filePath, folderId, bytesTransferred -> { });
    }

    /**
     * Same as {@link #uploadFile(String, Path, String)}, additionally invoking {@code
     * onBytesTransferred} with the cumulative number of bytes handed off to the underlying {@link
     * HttpClient} so far, each time a new chunk is read off {@code filePath} - a caller (e.g. a
     * desktop UI's progress bar) can divide this by {@link Files#size(Path)} itself to derive a
     * percentage; this method doesn't compute one, since it has no simpler way to report "not yet
     * known" than the caller already has by just not calling {@link Files#size} first. Invoked on
     * whatever thread is driving the request body (an internal {@link HttpClient} I/O thread, not
     * {@link #executor()}) - keep the callback itself cheap and non-blocking, the same expectation
     * any {@link Flow.Subscriber} callback carries.
     *
     * @param fileName           the name to store the file under
     * @param filePath           the local file to stream as the upload body
     * @param folderId           the destination folder's id, or {@code null} for the root
     * @param onBytesTransferred invoked with the cumulative bytes handed to the {@link HttpClient} so far
     * @throws ApiException {@code 401} if not logged in / token expired, {@code 404} if {@code
     *                       folderId} doesn't exist or isn't owned by the caller, or any other failure
     */
    public StoredFileSummaryResponse uploadFile(final String fileName, final Path filePath, final String folderId,
                                                 final LongConsumer onBytesTransferred) throws ApiException {
        final HttpRequest request;
        try {
            request = this.uploadFileRequest(fileName, filePath, folderId, onBytesTransferred);
        } catch (final FileNotFoundException e) {
            throw new ApiException(0, "file not found or unreadable: " + filePath, e);
        }
        return this.send(request, StoredFileSummaryResponse.class);
    }

    /** Async form of {@link #uploadFile(Path)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileSummaryResponse> uploadFileAsync(final Path filePath) {
        return this.uploadFileAsync(filePath.getFileName().toString(), filePath);
    }

    /** Async form of {@link #uploadFile(String, Path)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileSummaryResponse> uploadFileAsync(final String fileName, final Path filePath) {
        return this.uploadFileAsync(fileName, filePath, null);
    }

    /** Async form of {@link #uploadFile(String, Path, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileSummaryResponse> uploadFileAsync(final String fileName, final Path filePath, final String folderId) {
        return this.uploadFileAsync(fileName, filePath, folderId, bytesTransferred -> { });
    }

    /** Async form of {@link #uploadFile(String, Path, String, LongConsumer)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileSummaryResponse> uploadFileAsync(final String fileName, final Path filePath, final String folderId,
                                                                         final LongConsumer onBytesTransferred) {
        final HttpRequest request;
        try {
            request = this.uploadFileRequest(fileName, filePath, folderId, onBytesTransferred);
        } catch (final FileNotFoundException e) {
            return CompletableFuture.failedFuture(new ApiException(0, "file not found or unreadable: " + filePath, e));
        }
        return this.sendAsync(request, StoredFileSummaryResponse.class);
    }

    /** Builds the {@code POST /files} request against {@link #apiBaseUrl}, streaming {@code filePath} as the body. */
    private HttpRequest uploadFileRequest(final String fileName, final Path filePath, final String folderId,
                                           final LongConsumer onBytesTransferred) throws FileNotFoundException {
        return this.uploadRequestBuilder(fileName, folderId)
                .POST(progressTrackingFilePublisher(filePath, onBytesTransferred))
                .build();
    }

    /**
     * Wraps {@link BodyPublishers#ofFile}'s publisher so every {@link ByteBuffer} it hands to the
     * underlying {@link HttpClient} is also counted before being forwarded - the request body sent
     * over the wire is identical either way, this only observes it in transit. {@code
     * onBytesTransferred} receives the cumulative count, not a per-chunk delta, matching {@link
     * #uploadFile(String, Path, String, LongConsumer)}'s own contract.
     *
     * @param filePath           the file {@link BodyPublishers#ofFile} will stream from
     * @param onBytesTransferred invoked with the cumulative bytes published so far
     * @return a {@link BodyPublisher} with the same content/length as {@link BodyPublishers#ofFile(Path)}, instrumented for progress
     * @throws FileNotFoundException if {@code filePath} doesn't exist or can't be read, per {@link BodyPublishers#ofFile(Path)}
     */
    private static BodyPublisher progressTrackingFilePublisher(final Path filePath, final LongConsumer onBytesTransferred)
            throws FileNotFoundException {
        final BodyPublisher delegate = BodyPublishers.ofFile(filePath);
        return new BodyPublisher() {
            @Override
            public long contentLength() {
                return delegate.contentLength();
            }

            @Override
            public void subscribe(final Flow.Subscriber<? super ByteBuffer> subscriber) {
                delegate.subscribe(new ProgressTrackingSubscriber(subscriber, onBytesTransferred));
            }
        };
    }

    /**
     * Reports cumulative bytes as each {@link ByteBuffer} passes through, before forwarding it
     * downstream unchanged - safe to read {@link ByteBuffer#remaining()} before the downstream
     * subscriber gets a chance to consume/advance the buffer's position, since Reactive Streams
     * guarantees {@code onNext} calls on one subscription are always sequential, never concurrent.
     */
    private static final class ProgressTrackingSubscriber implements Flow.Subscriber<ByteBuffer> {

        /** The real subscriber (owned by {@link BodyPublishers#ofFile}'s publisher) every event is forwarded to unchanged. */
        private final Flow.Subscriber<? super ByteBuffer> downstream;

        /** Invoked with the cumulative byte count on every {@link #onNext}. */
        private final LongConsumer onBytesTransferred;

        /** Running total of bytes observed across every {@link #onNext} call so far. */
        private long transferred = 0;

        /**
         * @param downstream          the real subscriber to forward every event to, unchanged
         * @param onBytesTransferred  invoked with the cumulative bytes observed so far
         */
        private ProgressTrackingSubscriber(final Flow.Subscriber<? super ByteBuffer> downstream, final LongConsumer onBytesTransferred) {
            this.downstream = downstream;
            this.onBytesTransferred = onBytesTransferred;
        }

        /** {@inheritDoc} Forwarded to {@link #downstream} unchanged. */
        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.downstream.onSubscribe(subscription);
        }

        /** {@inheritDoc} Counts {@code item}'s remaining bytes, reports the running total, then forwards {@code item} to {@link #downstream} unchanged. */
        @Override
        public void onNext(final ByteBuffer item) {
            this.transferred += item.remaining();
            this.onBytesTransferred.accept(this.transferred);
            this.downstream.onNext(item);
        }

        /** {@inheritDoc} Forwarded to {@link #downstream} unchanged. */
        @Override
        public void onError(final Throwable throwable) {
            this.downstream.onError(throwable);
        }

        /** {@inheritDoc} Forwarded to {@link #downstream} unchanged. */
        @Override
        public void onComplete() {
            this.downstream.onComplete();
        }

    }

    /** {@code folderId} {@code null} places the upload at the root, omitting the query parameter entirely. */
    private HttpRequest.Builder uploadRequestBuilder(final String fileName, final String folderId) {
        final String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
        final String query = "/files?fileName=" + encodedFileName
                + (folderId == null ? "" : "&folderId=" + URLEncoder.encode(folderId, StandardCharsets.UTF_8));
        return this.requestBuilder(this.apiBaseUrl.resolve(query), true)
                .header("Content-Type", "application/octet-stream")
                .timeout(TRANSFER_TIMEOUT);
    }

    /**
     * Uploads every entry in {@code filesByName} (key = upload file name, value = local path)
     * concurrently, up to {@link #DEFAULT_MAX_CONCURRENT_TRANSFERS} at once - see {@link
     * #uploadFilesAsync(Map, int)} to change that bound.
     *
     * @param filesByName map from the name each file should be stored under to its local {@link Path}
     * @return a future completing with every {@link StoredFileSummaryResponse}, in no particular
     * order, once <em>all</em> uploads have finished (successfully or not); if any failed, the
     * returned future completes exceptionally with the first failure encountered - matching this
     * codebase's own batch-operation convention (see {@code EntityDatabaseClient}'s Javadoc:
     * "throws the first failure encountered once every item has been attempted")
     */
    public CompletableFuture<List<StoredFileSummaryResponse>> uploadFilesAsync(final Map<String, Path> filesByName) {
        return this.uploadFilesAsync(filesByName, DEFAULT_MAX_CONCURRENT_TRANSFERS);
    }

    /**
     * Same as {@link #uploadFilesAsync(Map)}, with an explicit concurrency cap instead of {@link
     * #DEFAULT_MAX_CONCURRENT_TRANSFERS}. The cap exists because concurrent uploads are otherwise
     * unbounded - HTTP/2 multiplexing (see the class Javadoc) makes many concurrent requests over
     * one connection cheap on the wire, but each open upload still holds its own file handle
     * ({@link BodyPublishers#ofFile}) and in-flight request state, so uploading e.g. a thousand
     * files at once with no cap risks exhausting file descriptors and overwhelming the server.
     *
     * @param filesByName             map from the name each file should be stored under to its local {@link Path}
     * @param maxConcurrentTransfers  the maximum number of uploads to have in flight at once (clamped to at least 1)
     * @return a future completing the same way {@link #uploadFilesAsync(Map)} does - see its own Javadoc
     */
    public CompletableFuture<List<StoredFileSummaryResponse>> uploadFilesAsync(final Map<String, Path> filesByName,
                                                                          final int maxConcurrentTransfers) {
        final Semaphore permits = new Semaphore(Math.max(1, maxConcurrentTransfers));
        final List<CompletableFuture<StoredFileSummaryResponse>> uploads = filesByName.entrySet().stream()
                .map(entry -> this.withPermit(permits, () -> this.uploadFileAsync(entry.getKey(), entry.getValue())))
                .toList();
        return awaitAll(uploads);
    }

    /**
     * Acquires a permit (asynchronously, on {@link #executor}) before running {@code action}, and always releases it once that action's future completes.
     *
     * @param permits the semaphore to acquire/release a permit from
     * @param action  the action to run once a permit is held
     * @return a future completing the same way {@code action}'s own future does
     */
    private <T> CompletableFuture<T> withPermit(final Semaphore permits, final Supplier<CompletableFuture<T>> action) {
        return CompletableFuture
                .runAsync(permits::acquireUninterruptibly, this.executor)
                .thenCompose(ignored -> action.get())
                .whenComplete((result, error) -> permits.release());
    }

    // --- files: presigned direct-to-client transfer ------------------------

    /**
     * Uploads {@code filePath} directly to the object store, bypassing this app's own server for
     * the data path entirely (see {@code architecture/AWS_S3_IMPL.md}) - orchestrates all three
     * steps: {@link #beginUploadUrl}, a raw {@code PUT} to the returned URL, then {@link
     * #completeUpload}. Computes the SHA-256 checksum {@link #completeUpload} needs via a
     * dedicated pre-pass reading {@code filePath} once before the upload itself reads it a second
     * time - a deliberate, accepted trade-off over a custom digesting {@link BodyPublisher}
     * wrapper for this first pass.
     *
     * @param fileName           the name to store the file under
     * @param filePath           the local file to upload
     * @param folderId           the destination folder's id, or {@code null} for the root
     * @param onBytesTransferred invoked with the cumulative bytes {@code PUT} to the object store so far
     * @return the newly created file's summary, same shape {@link #uploadFile(String, Path, String)} returns
     * @throws ApiException {@code 503} if this deployment has no presigned transfer configured -
     *                       callers should fall back to {@link #uploadFile(String, Path, String)}
     *                       on exactly this status; {@code 401} if not logged in / token expired;
     *                       {@code 413} if the file would exceed the account's quota; or any other failure
     */
    public StoredFileSummaryResponse uploadFileViaPresignedUrl(final String fileName, final Path filePath, final String folderId,
                                                                 final LongConsumer onBytesTransferred) throws ApiException {
        final long sizeBytes;
        final String checksumSha256;
        try {
            sizeBytes = Files.size(filePath);
            checksumSha256 = sha256Hex(filePath);
        } catch (final IOException e) {
            throw new ApiException(0, "failed to read file for presigned upload: " + filePath, e);
        }

        final BeginUploadUrlResponse begin = this.beginUploadUrl(fileName, sizeBytes, folderId);
        this.putToPresignedUrl(begin.uploadUrl(), begin.requiredHeaders(), filePath, onBytesTransferred);
        return this.completeUpload(begin.fileId(), fileName, checksumSha256, folderId);
    }

    /**
     * Async form of {@link #uploadFileViaPresignedUrl} - runs the whole three-step flow on {@link
     * #executor()} (never a JDK-internal thread), matching this codebase's own {@code *Async}
     * convention of wrapping a checked failure from the sync primitive in a {@link
     * CompletionException}.
     */
    public CompletableFuture<StoredFileSummaryResponse> uploadFileViaPresignedUrlAsync(final String fileName, final Path filePath, final String folderId,
                                                                                         final LongConsumer onBytesTransferred) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.uploadFileViaPresignedUrl(fileName, filePath, folderId, onBytesTransferred);
            } catch (final ApiException e) {
                throw new CompletionException(e);
            }
        }, this.executor);
    }

    /** {@code POST /files/upload-url}: begins a presigned upload - see {@link #uploadFileViaPresignedUrl} for the full three-step flow. */
    public BeginUploadUrlResponse beginUploadUrl(final String fileName, final long sizeBytes, final String folderId) throws ApiException {
        return this.send(this.beginUploadUrlRequest(fileName, sizeBytes, folderId), BeginUploadUrlResponse.class);
    }

    /** Async form of {@link #beginUploadUrl} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<BeginUploadUrlResponse> beginUploadUrlAsync(final String fileName, final long sizeBytes, final String folderId) {
        return this.sendAsync(this.beginUploadUrlRequest(fileName, sizeBytes, folderId), BeginUploadUrlResponse.class);
    }

    /** Builds the {@code POST /files/upload-url} request against {@link #apiBaseUrl}. */
    private HttpRequest beginUploadUrlRequest(final String fileName, final long sizeBytes, final String folderId) {
        return this.postRequest(this.apiBaseUrl.resolve("/files/upload-url"), new BeginUploadUrlRequest(fileName, sizeBytes, folderId), true);
    }

    /** {@code POST /files/{id}/complete-upload}: confirms a presigned upload - see {@link #uploadFileViaPresignedUrl} for the full three-step flow. */
    public StoredFileSummaryResponse completeUpload(final String fileId, final String fileName, final String checksumSha256, final String folderId) throws ApiException {
        return this.send(this.completeUploadRequest(fileId, fileName, checksumSha256, folderId), StoredFileSummaryResponse.class);
    }

    /** Async form of {@link #completeUpload} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileSummaryResponse> completeUploadAsync(final String fileId, final String fileName, final String checksumSha256, final String folderId) {
        return this.sendAsync(this.completeUploadRequest(fileId, fileName, checksumSha256, folderId), StoredFileSummaryResponse.class);
    }

    /** Builds the {@code POST /files/{id}/complete-upload} request against {@link #apiBaseUrl}. */
    private HttpRequest completeUploadRequest(final String fileId, final String fileName, final String checksumSha256, final String folderId) {
        return this.postRequest(this.apiBaseUrl.resolve("/files/" + fileId + "/complete-upload"),
                new CompleteUploadRequest(fileName, checksumSha256, folderId), true);
    }

    /**
     * {@code PUT}s {@code filePath}'s bytes directly to {@code url} (a presigned upload URL, not
     * this app's own server) - unauthenticated (no {@code Authorization} header; nothing needs
     * one against the object store), replaying every one of {@code requiredHeaders} exactly, or
     * the object store rejects the request's signature.
     */
    private void putToPresignedUrl(final String url, final Map<String, String> requiredHeaders, final Path filePath,
                                    final LongConsumer onBytesTransferred) throws ApiException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TRANSFER_TIMEOUT);
        requiredHeaders.forEach(builder::header);

        final HttpRequest request;
        try {
            request = builder.PUT(progressTrackingFilePublisher(filePath, onBytesTransferred)).build();
        } catch (final FileNotFoundException e) {
            throw new ApiException(0, "file not found or unreadable: " + filePath, e);
        }

        final HttpResponse<String> response;
        try {
            response = this.httpClient.send(request, BodyHandlers.ofString());
        } catch (final IOException e) {
            throw new ApiException(0, "network error uploading to presigned URL", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted uploading to presigned URL", e);
        }

        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // The object store's own error body (XML, not this app's JSON ErrorResponse shape) -
            // included as-is rather than run through extractErrorMessage/extractErrorMessageFromFile,
            // which are both built around this app's own JSON error convention.
            throw new ApiException(status, "presigned upload failed: " + response.body(), null);
        }
    }

    /**
     * Downloads a file directly from the object store, bypassing this app's own server for the
     * data path entirely - orchestrates {@link #beginDownloadUrl} then a raw {@code GET} to the
     * returned URL, streamed straight to {@code destination} (same {@link BodyHandlers#ofFile}-based
     * mechanism {@link #downloadFileToPath} uses server-side).
     *
     * @param fileId             the file to fetch
     * @param destination        the local path to write the file to; must not already exist
     * @param onBytesTransferred invoked with the cumulative bytes written to {@code destination} so far
     * @return {@code destination}, unchanged, once the file has been fully written
     * @throws ApiException {@code 503} if this deployment has no presigned transfer configured, or
     *                       {@code fileId} isn't eligible for it - callers should fall back to
     *                       {@link #downloadFileToPath(String, Path)} on exactly this status;
     *                       {@code 404} if {@code fileId} doesn't exist or isn't owned by the
     *                       caller; {@code 401} if not logged in / token expired; or any other failure
     */
    public Path downloadFileViaPresignedUrl(final String fileId, final Path destination, final LongConsumer onBytesTransferred) throws ApiException {
        final BeginDownloadUrlResponse begin = this.beginDownloadUrl(fileId);
        final HttpRequest request = HttpRequest.newBuilder(URI.create(begin.downloadUrl())).timeout(TRANSFER_TIMEOUT).GET().build();

        final HttpResponse<Path> response;
        try {
            response = this.httpClient.send(request, progressTrackingFileHandler(destination, onBytesTransferred));
        } catch (final IOException e) {
            throw new ApiException(0, "network error downloading from presigned URL", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted downloading from presigned URL", e);
        }
        return requireSuccessfulFileDownload(response);
    }

    /**
     * Async form of {@link #downloadFileViaPresignedUrl} - runs the whole two-step flow on {@link
     * #executor()} (never a JDK-internal thread), matching this codebase's own {@code *Async}
     * convention of wrapping a checked failure from the sync primitive in a {@link
     * CompletionException}.
     */
    public CompletableFuture<Path> downloadFileViaPresignedUrlAsync(final String fileId, final Path destination, final LongConsumer onBytesTransferred) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.downloadFileViaPresignedUrl(fileId, destination, onBytesTransferred);
            } catch (final ApiException e) {
                throw new CompletionException(e);
            }
        }, this.executor);
    }

    /** {@code GET /files/{id}/download-url}: begins a presigned download - see {@link #downloadFileViaPresignedUrl} for the full flow. */
    public BeginDownloadUrlResponse beginDownloadUrl(final String fileId) throws ApiException {
        return this.send(this.beginDownloadUrlRequest(fileId), BeginDownloadUrlResponse.class);
    }

    /** Async form of {@link #beginDownloadUrl} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<BeginDownloadUrlResponse> beginDownloadUrlAsync(final String fileId) {
        return this.sendAsync(this.beginDownloadUrlRequest(fileId), BeginDownloadUrlResponse.class);
    }

    /** Builds the {@code GET /files/{id}/download-url} request against {@link #apiBaseUrl}. */
    private HttpRequest beginDownloadUrlRequest(final String fileId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId + "/download-url"), true).GET().build();
    }

    /**
     * Computes {@code filePath}'s SHA-256 checksum as a lowercase hex string - the shape {@code
     * FileChecksum#hexDigest()} carries server-side, so the server can persist it verbatim without
     * this client needing to know anything about that type.
     */
    private static String sha256Hex(final Path filePath) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@ApiClient.sha256Hex: JVM does not provide SHA-256", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(filePath), digest)) {
            final byte[] buffer = new byte[8192];
            while (in.read(buffer) != -1) {
                // DigestInputStream updates the digest as a side effect of read() - nothing else to do per chunk.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // --- files: list ------------------------------------------------------

    /**
     * {@code GET /files} on the main REST API - every file tracked as owned by the authenticated
     * caller, as lightweight {@link StoredFileSummaryResponse} entries (no content) fully
     * materialized as a {@link List} once the whole response has arrived. Prefer {@link
     * #listFilesStream()} if the caller only needs to process entries one at a time and would
     * rather not wait for the entire response at once. Fetch a specific file's full content via
     * {@link #downloadFile(String)} once actually needed.
     *
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public List<StoredFileSummaryResponse> listFiles() throws ApiException {
        final StoredFileSummaryResponse[] files = this.send(this.listFilesRequest(), StoredFileSummaryResponse[].class);
        return List.of(files);
    }

    /** Async form of {@link #listFiles()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<StoredFileSummaryResponse>> listFilesAsync() {
        return this.sendAsync(this.listFilesRequest(), StoredFileSummaryResponse[].class).thenApply(List::of);
    }

    /**
     * Same route as {@link #listFiles()}, scoped to just one folder's direct contents instead of
     * every file the caller owns - {@code folderId} {@code null} lists the root's own files.
     * Every {@link StoredFileSummaryResponse#folderId()} in the result equals {@code folderId}
     * (or is {@code null}, for a root listing).
     *
     * @param folderId the folder to scope the listing to, or {@code null} for the root
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public List<StoredFileSummaryResponse> listFiles(final String folderId) throws ApiException {
        final StoredFileSummaryResponse[] files = this.send(this.listFilesRequest(folderId), StoredFileSummaryResponse[].class);
        return List.of(files);
    }

    /** Async form of {@link #listFiles(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<StoredFileSummaryResponse>> listFilesAsync(final String folderId) {
        return this.sendAsync(this.listFilesRequest(folderId), StoredFileSummaryResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /files?folderId=...} request against {@link #apiBaseUrl}, scoped to one folder (or the root). */
    private HttpRequest listFilesRequest(final String folderId) {
        final String encodedFolderId = URLEncoder.encode(folderId == null ? "root" : folderId, StandardCharsets.UTF_8);
        return this.requestBuilder(this.apiBaseUrl.resolve("/files?folderId=" + encodedFolderId), true).GET().build();
    }

    /** {@link com.google.gson.reflect.TypeToken}-backed {@link Type} for a {@code Page<StoredFileSummaryResponse>} response body - see {@link #parseResponse(HttpResponse, Type)}. */
    private static final Type STORED_FILE_SUMMARY_PAGE_TYPE = new TypeToken<Page<StoredFileSummaryResponse>>() {
    }.getType();

    /**
     * Cursor-paginated counterpart to {@link #listFiles(String)} - opts the server into the
     * {@code {"items", "nextCursor"}} envelope response by sending {@code ?limit=}, instead of
     * every owned/scoped file in one unpaginated array. Pass the previous call's {@link
     * Page#nextCursor()} back as {@code cursor} to fetch the next page, or {@code null} for the
     * first page.
     *
     * @param folderId the folder to scope the listing to, or {@code null} for the root
     * @param cursor   the previous page's {@link Page#nextCursor()}, or {@code null} for the first page
     * @param limit    the maximum number of entries to return; must be positive
     * @throws ApiException {@code 401} if not logged in / token expired, {@code 400} if {@code limit} isn't positive, or any other failure
     */
    public Page<StoredFileSummaryResponse> listFilesPage(final String folderId, final String cursor, final int limit) throws ApiException {
        return this.send(this.listFilesPageRequest(folderId, cursor, limit), STORED_FILE_SUMMARY_PAGE_TYPE);
    }

    /** Async form of {@link #listFilesPage(String, String, int)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Page<StoredFileSummaryResponse>> listFilesPageAsync(final String folderId, final String cursor, final int limit) {
        return this.sendAsync(this.listFilesPageRequest(folderId, cursor, limit), STORED_FILE_SUMMARY_PAGE_TYPE);
    }

    private HttpRequest listFilesPageRequest(final String folderId, final String cursor, final int limit) {
        final StringBuilder query = new StringBuilder("/files?limit=").append(limit);
        if (folderId != null) {
            query.append("&folderId=").append(URLEncoder.encode(folderId, StandardCharsets.UTF_8));
        }
        if (cursor != null) {
            query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return this.requestBuilder(this.apiBaseUrl.resolve(query.toString()), true).GET().build();
    }

    /**
     * Same route as {@link #listFiles()}, but never materializes the full response as one JSON
     * array or {@code byte[]}/{@code String}: the returned {@link Stream} is backed by a {@link
     * JsonReader} incrementally parsing directly off the still-open HTTP response body, one
     * {@link StoredFileSummaryResponse} at a time, as bytes arrive on the wire. Since each entry
     * already carries no content, this mainly bounds peak memory against a very large file count
     * rather than large file sizes, and lets a caller start acting on the first entry before the
     * rest have even arrived.
     *
     * <p><b>The caller must close the returned {@link Stream}</b> (a try-with-resources block, or
     * an explicit {@code close()}/{@link Stream#onClose}) once done consuming it, even if not
     * every element is read - closing releases the underlying HTTP connection and {@link
     * JsonReader}; failing to would leak both, the same way an unclosed {@link
     * java.nio.file.Files#lines} would.
     *
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     *                       reaching/parsing the start of the response; a failure encountered
     *                       <em>while</em> the stream is being consumed instead surfaces as an
     *                       unchecked {@link UncheckedIOException} from that terminal operation,
     *                       since {@link Iterator#next()} cannot declare a checked exception
     */
    public Stream<StoredFileSummaryResponse> listFilesStream() throws ApiException {
        final HttpRequest request = this.listFilesRequest();
        final HttpResponse<InputStream> response;
        try {
            response = this.httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (final IOException e) {
            throw new ApiException(0, "network error calling " + request.uri(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted calling " + request.uri(), e);
        }

        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            try (InputStream errorBody = response.body()) {
                throw new ApiException(status, extractErrorMessage(errorBody), null);
            } catch (final IOException e) {
                throw new ApiException(status, "request failed and the error body could not be read", e);
            }
        }

        return streamJsonArray(response.body());
    }

    /**
     * Wraps {@code body} in a {@link JsonReader} positioned just inside its top-level array, then
     * exposes it as a lazily-consumed {@link Stream} via {@link JsonArrayIterator}.
     *
     * @param body the still-open, successful response body to parse
     * @return a stream the caller must close to release {@code body}/the underlying connection
     * @throws ApiException if {@code body} isn't a JSON array
     */
    private static Stream<StoredFileSummaryResponse> streamJsonArray(final InputStream body) throws ApiException {
        final JsonReader jsonReader = new JsonReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        try {
            jsonReader.beginArray();
        } catch (final IOException e) {
            closeQuietly(jsonReader);
            throw new ApiException(0, "malformed response body", e);
        }

        final Spliterator<StoredFileSummaryResponse> spliterator = Spliterators.spliteratorUnknownSize(
                new JsonArrayIterator(jsonReader), Spliterator.ORDERED | Spliterator.NONNULL
        );
        return StreamSupport.stream(spliterator, false).onClose(() -> closeQuietly(jsonReader));
    }

    /** Builds the unscoped {@code GET /files} request against {@link #apiBaseUrl} - every file the caller owns, regardless of folder. */
    private HttpRequest listFilesRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files"), true).GET().build();
    }

    /** Lazily pulls one {@link StoredFileSummaryResponse} at a time off an open {@link JsonReader} positioned inside a JSON array. */
    private static final class JsonArrayIterator implements Iterator<StoredFileSummaryResponse> {

        /** The reader this iterator pulls elements from; positioned just inside the array's opening bracket. */
        private final JsonReader jsonReader;

        /** Cached result of the last {@link JsonReader#hasNext()} probe, cleared once that element is consumed by {@link #next()}; {@code null} means not yet probed. */
        private Boolean hasNextCache;

        /** @param jsonReader a reader already positioned just inside the array to iterate */
        private JsonArrayIterator(final JsonReader jsonReader) {
            this.jsonReader = jsonReader;
        }

        /**
         * {@inheritDoc} Probes (and caches) whether another array element remains, ending the
         * array on the reader once it doesn't.
         *
         * @throws UncheckedIOException if the underlying {@link JsonReader} fails to read
         */
        @Override
        public boolean hasNext() {
            if (this.hasNextCache == null) {
                try {
                    this.hasNextCache = this.jsonReader.hasNext();
                    if (!this.hasNextCache) {
                        this.jsonReader.endArray();
                    }
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return this.hasNextCache;
        }

        /**
         * {@inheritDoc}
         *
         * @throws NoSuchElementException if no further element remains, per {@link #hasNext()}
         */
        @Override
        public StoredFileSummaryResponse next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            final StoredFileSummaryResponse value = GSON.fromJson(this.jsonReader, StoredFileSummaryResponse.class);
            this.hasNextCache = null;
            return value;
        }

    }

    // --- files: download ------------------------------------------------

    /**
     * {@code GET /files/{id}} on the main REST API - fetches one file's full content (unlike
     * {@link #listFiles()}, which never carries content). Call this once a specific file from a
     * {@link #listFiles()}/{@link #listFilesStream()} entry is actually needed (e.g. the user
     * opened or saved it).
     *
     * @param fileId the file to fetch
     * @throws ApiException {@code 404} if {@code fileId} doesn't exist or isn't owned by the
     *                       caller, {@code 401} if not logged in / token expired
     */
    public StoredFileResponse downloadFile(final String fileId) throws ApiException {
        return this.send(this.downloadFileRequest(fileId), StoredFileResponse.class);
    }

    /** Async form of {@link #downloadFile(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<StoredFileResponse> downloadFileAsync(final String fileId) {
        return this.sendAsync(this.downloadFileRequest(fileId), StoredFileResponse.class);
    }

    /** Builds the {@code GET /files/{id}} request against {@link #apiBaseUrl}. */
    private HttpRequest downloadFileRequest(final String fileId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId), true)
                .timeout(TRANSFER_TIMEOUT)
                .GET()
                .build();
    }

    /**
     * {@code GET /files/{id}/content} on the main REST API - streams a file's content directly
     * to {@code destination} on disk via {@link BodyHandlers#ofFile}, the download-side mirror of
     * {@link #uploadFile(Path)}'s {@link BodyPublishers#ofFile}: no {@code ByteArrayOutputStream},
     * no base64 decode, content never fully materializes as a Java object in this process at all.
     * Prefer this over {@link #downloadFile(String)} whenever the content is headed straight to
     * disk anyway - reach for {@link #downloadFile(String)} only when the bytes are actually
     * needed in memory.
     *
     * <p>{@code destination} must not already exist - same contract as {@link
     * BodyHandlers#ofFile(Path)} itself (fails with an {@link ApiException} wrapping a {@link
     * java.nio.file.FileAlreadyExistsException} otherwise). The caller is responsible for
     * resolving a fresh, non-colliding path first, the same way {@code StoredFile#downloadToDevice}
     * does server-side.
     *
     * @param fileId      the file to fetch
     * @param destination the local path to write the file to; must not already exist
     * @return {@code destination}, unchanged, once the file has been fully written
     * @throws ApiException {@code 404} if {@code fileId} doesn't exist or isn't owned by the
     *                       caller, {@code 401} if not logged in / token expired, or any other failure
     */
    public Path downloadFileToPath(final String fileId, final Path destination) throws ApiException {
        return this.downloadFileToPath(fileId, destination, bytesTransferred -> { });
    }

    /**
     * Same as {@link #downloadFileToPath(String, Path)}, additionally invoking {@code
     * onBytesTransferred} with the cumulative number of bytes written to {@code destination} so
     * far, as each chunk arrives off the wire - a caller wanting a percentage needs the file's
     * total size from elsewhere (e.g. a prior {@link #listFiles()}/{@link #listFiles(String)}
     * entry's own size field), since this response carries no {@code Content-Length} guarantee
     * this method relies on. Invoked on whatever thread is driving the response body (an internal
     * {@link HttpClient} I/O thread, not {@link #executor()}) - keep the callback cheap and
     * non-blocking, the same expectation any {@link Flow.Subscriber} callback carries.
     *
     * @param fileId             the file to fetch
     * @param destination        the local path to write the file to; must not already exist
     * @param onBytesTransferred invoked with the cumulative bytes written to {@code destination} so far
     * @return {@code destination}, unchanged, once the file has been fully written
     * @throws ApiException {@code 404} if {@code fileId} doesn't exist or isn't owned by the
     *                       caller, {@code 401} if not logged in / token expired, or any other failure
     */
    public Path downloadFileToPath(final String fileId, final Path destination, final LongConsumer onBytesTransferred) throws ApiException {
        final HttpRequest request = this.downloadFileContentRequest(fileId);
        final HttpResponse<Path> response;
        try {
            response = this.httpClient.send(request, progressTrackingFileHandler(destination, onBytesTransferred));
        } catch (final IOException e) {
            throw new ApiException(0, "network error calling " + request.uri(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted calling " + request.uri(), e);
        }
        return requireSuccessfulFileDownload(response);
    }

    /** Async form of {@link #downloadFileToPath(String, Path)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Path> downloadFileToPathAsync(final String fileId, final Path destination) {
        return this.downloadFileToPathAsync(fileId, destination, bytesTransferred -> { });
    }

    /** Async form of {@link #downloadFileToPath(String, Path, LongConsumer)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Path> downloadFileToPathAsync(final String fileId, final Path destination, final LongConsumer onBytesTransferred) {
        final HttpRequest request = this.downloadFileContentRequest(fileId);
        return this.httpClient.sendAsync(request, progressTrackingFileHandler(destination, onBytesTransferred))
                .thenApply(response -> {
                    try {
                        return requireSuccessfulFileDownload(response);
                    } catch (final ApiException e) {
                        // Matches this codebase's own *Async convention - see #sendAsync's own comment.
                        throw new CompletionException(e);
                    }
                });
    }

    /**
     * Wraps {@link BodySubscribers#ofFile} so every chunk written to disk is also counted first - see {@link ProgressTrackingBodySubscriber}.
     *
     * @param destination        the local path {@link BodySubscribers#ofFile} will write to
     * @param onBytesTransferred invoked with the cumulative bytes written so far
     * @return a {@link BodyHandler} producing a {@link ProgressTrackingBodySubscriber} for each response
     */
    private static BodyHandler<Path> progressTrackingFileHandler(final Path destination, final LongConsumer onBytesTransferred) {
        return responseInfo -> new ProgressTrackingBodySubscriber<>(BodySubscribers.ofFile(destination), onBytesTransferred);
    }

    /**
     * Reports cumulative bytes as each chunk (a {@code List<ByteBuffer>}, per {@link
     * BodySubscriber}'s own shape) passes through, before forwarding it downstream unchanged - the
     * download-side mirror of {@link ProgressTrackingSubscriber}, wrapping a {@link BodySubscriber}
     * (used for a response body) rather than a {@link Flow.Subscriber} (used for a request body).
     *
     * @param <T> the type the wrapped {@link BodySubscriber} ultimately produces
     */
    private static final class ProgressTrackingBodySubscriber<T> implements BodySubscriber<T> {

        /** The real subscriber (e.g. one from {@link BodySubscribers#ofFile}) every chunk is forwarded to unchanged. */
        private final BodySubscriber<T> delegate;

        /** Invoked with the cumulative byte count on every {@link #onNext}. */
        private final LongConsumer onBytesTransferred;

        /** Running total of bytes observed across every {@link #onNext} call so far. */
        private long transferred = 0;

        /**
         * @param delegate            the real subscriber to forward every chunk to, unchanged
         * @param onBytesTransferred  invoked with the cumulative bytes observed so far
         */
        private ProgressTrackingBodySubscriber(final BodySubscriber<T> delegate, final LongConsumer onBytesTransferred) {
            this.delegate = delegate;
            this.onBytesTransferred = onBytesTransferred;
        }

        /** {@inheritDoc} Delegated to {@link #delegate}. */
        @Override
        public CompletionStage<T> getBody() {
            return this.delegate.getBody();
        }

        /** {@inheritDoc} Forwarded to {@link #delegate} unchanged. */
        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.delegate.onSubscribe(subscription);
        }

        /** {@inheritDoc} Sums {@code item}'s remaining bytes, reports the running total, then forwards {@code item} to {@link #delegate} unchanged. */
        @Override
        public void onNext(final List<ByteBuffer> item) {
            long chunkBytes = 0;
            for (final ByteBuffer buffer : item) {
                chunkBytes += buffer.remaining();
            }
            this.transferred += chunkBytes;
            this.onBytesTransferred.accept(this.transferred);
            this.delegate.onNext(item);
        }

        /** {@inheritDoc} Forwarded to {@link #delegate} unchanged. */
        @Override
        public void onError(final Throwable throwable) {
            this.delegate.onError(throwable);
        }

        /** {@inheritDoc} Forwarded to {@link #delegate} unchanged. */
        @Override
        public void onComplete() {
            this.delegate.onComplete();
        }

    }

    /** Builds the {@code GET /files/{id}/content} request against {@link #apiBaseUrl}. */
    private HttpRequest downloadFileContentRequest(final String fileId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId + "/content"), true)
                .timeout(TRANSFER_TIMEOUT)
                .GET()
                .build();
    }

    /**
     * Checks {@code response}'s status: {@code 2xx} returns its already-written {@link Path}
     * unchanged; anything else reads back the (small, JSON) error body {@link BodyHandlers#ofFile}
     * already wrote to that same path - it has no way to know a response failed before writing its
     * body - deletes that stray file (it isn't real file content), and throws {@link ApiException}
     * carrying the extracted message.
     *
     * @param response the completed download response, whose body is the path it was written to
     * @return {@code response}'s body path, unchanged, on a {@code 2xx} status
     * @throws ApiException on any non-{@code 2xx} status
     */
    private static Path requireSuccessfulFileDownload(final HttpResponse<Path> response) throws ApiException {
        final int status = response.statusCode();
        final Path destination = response.body();
        if (status >= 200 && status < 300) {
            return destination;
        }
        throw new ApiException(status, extractErrorMessageFromFile(destination), null);
    }

    /**
     * {@link #extractErrorMessage(InputStream)}, reading from a file on disk instead of an open response stream, then deleting it.
     *
     * @param destination the file a failed response's body was written to; deleted before returning
     * @return the best available human-readable error message
     */
    private static String extractErrorMessageFromFile(final Path destination) {
        final String text;
        try {
            text = Files.readString(destination, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            return "request failed and the error body could not be read";
        } finally {
            try {
                Files.deleteIfExists(destination);
            } catch (final IOException ignored) {
                // Best-effort cleanup only - the caller already has an error to report either way.
            }
        }
        if (text.isBlank()) {
            return "request failed with no response body";
        }
        try {
            final ErrorResponse error = GSON.fromJson(text, ErrorResponse.class);
            return error != null && error.title() != null ? error.title() : text;
        } catch (final RuntimeException malformedJson) {
            return text;
        }
    }

    // --- files: delete ------------------------------------------------

    /**
     * {@code DELETE /files/{id}} on the main REST API.
     *
     * @param fileId the file to delete
     * @throws ApiException {@code 404} if {@code fileId} doesn't exist or isn't owned by the
     *                       caller, {@code 401} if not logged in / token expired
     */
    public void deleteFile(final String fileId) throws ApiException {
        this.send(this.deleteFileRequest(fileId), Void.class);
    }

    /** Async form of {@link #deleteFile(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> deleteFileAsync(final String fileId) {
        return this.sendAsync(this.deleteFileRequest(fileId), Void.class);
    }

    /** Builds the {@code DELETE /files/{id}} request against {@link #apiBaseUrl}. */
    private HttpRequest deleteFileRequest(final String fileId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId), true).DELETE().build();
    }

    /**
     * Deletes every id in {@code fileIds} concurrently (unbounded - unlike {@link
     * #uploadFilesAsync}, a delete carries no request body/file handle, so there is nothing
     * costly to throttle).
     *
     * @param fileIds the ids to delete
     * @return a future completing once <em>every</em> deletion has been attempted; if any failed,
     * completes exceptionally with the first failure encountered (same convention as {@link
     * #uploadFilesAsync(Map)} - see its Javadoc)
     */
    public CompletableFuture<Void> deleteFilesAsync(final Collection<String> fileIds) {
        final List<CompletableFuture<Void>> deletions = fileIds.stream().map(this::deleteFileAsync).toList();
        return awaitAll(deletions).thenApply(ignored -> null);
    }

    // --- files: move --------------------------------------------------

    /**
     * {@code PUT /files/{id}/folder}: moves {@code fileId} into {@code folderId} - {@code null}
     * moves it back to the root.
     *
     * @param fileId   the file to move
     * @param folderId the destination folder's id, or {@code null} for the root
     * @throws ApiException {@code 404} if {@code fileId}/{@code folderId} doesn't exist or isn't
     *                       owned by the caller, {@code 401} if not logged in / token expired
     */
    public void moveFile(final String fileId, final String folderId) throws ApiException {
        this.send(this.moveFileRequest(fileId, folderId), Void.class);
    }

    /** Async form of {@link #moveFile(String, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> moveFileAsync(final String fileId, final String folderId) {
        return this.sendAsync(this.moveFileRequest(fileId, folderId), Void.class);
    }

    /** Builds the {@code PUT /files/{id}/folder} request against {@link #apiBaseUrl}, with a JSON {@link MoveFileRequest} body. */
    private HttpRequest moveFileRequest(final String fileId, final String folderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId + "/folder"), true)
                .header("Content-Type", "application/json")
                .method("PUT", BodyPublishers.ofString(GSON.toJson(new MoveFileRequest(folderId))))
                .build();
    }

    // --- folders --------------------------------------------------------

    /**
     * {@code POST /folders}: creates a new folder owned by the caller - {@code parentFolderId}
     * {@code null} creates a top-level folder.
     *
     * @param name           the new folder's name
     * @param parentFolderId the parent folder's id, or {@code null} to create a top-level folder
     * @throws ApiException {@code 404} if {@code parentFolderId} is non-null and doesn't exist or
     *                       isn't owned by the caller, {@code 401} if not logged in / token expired
     */
    public FolderResponse createFolder(final String name, final String parentFolderId) throws ApiException {
        return this.send(this.createFolderRequest(name, parentFolderId), FolderResponse.class);
    }

    /** Async form of {@link #createFolder(String, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<FolderResponse> createFolderAsync(final String name, final String parentFolderId) {
        return this.sendAsync(this.createFolderRequest(name, parentFolderId), FolderResponse.class);
    }

    /** Builds the {@code POST /folders} request against {@link #apiBaseUrl}, with a JSON {@link CreateFolderRequest} body. */
    private HttpRequest createFolderRequest(final String name, final String parentFolderId) {
        return this.postRequest(this.apiBaseUrl.resolve("/folders"), new CreateFolderRequest(name, parentFolderId), true);
    }

    /**
     * {@code GET /folders}: lists the caller's own folders directly inside {@code parentFolderId}
     * - {@code null} lists their top-level folders.
     *
     * @param parentFolderId the parent folder's id, or {@code null} to list top-level folders
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public List<FolderResponse> listFolders(final String parentFolderId) throws ApiException {
        final FolderResponse[] folders = this.send(this.listFoldersRequest(parentFolderId), FolderResponse[].class);
        return List.of(folders);
    }

    /** Async form of {@link #listFolders(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<FolderResponse>> listFoldersAsync(final String parentFolderId) {
        return this.sendAsync(this.listFoldersRequest(parentFolderId), FolderResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /folders?parentFolderId=...} request against {@link #apiBaseUrl}. */
    private HttpRequest listFoldersRequest(final String parentFolderId) {
        final String encodedParentFolderId = URLEncoder.encode(parentFolderId == null ? "root" : parentFolderId, StandardCharsets.UTF_8);
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders?parentFolderId=" + encodedParentFolderId), true).GET().build();
    }

    /** {@link com.google.gson.reflect.TypeToken}-backed {@link Type} for a {@code Page<FolderResponse>} response body - see {@link #parseResponse(HttpResponse, Type)}. */
    private static final Type FOLDER_PAGE_TYPE = new TypeToken<Page<FolderResponse>>() {
    }.getType();

    /**
     * Cursor-paginated counterpart to {@link #listFolders(String)} - same opt-in-via-{@code
     * ?limit=} contract as {@link #listFilesPage}.
     *
     * @param parentFolderId the parent folder to list children of, or {@code null} for the top level
     * @param cursor         the previous page's {@link Page#nextCursor()}, or {@code null} for the first page
     * @param limit          the maximum number of entries to return; must be positive
     * @throws ApiException {@code 401} if not logged in / token expired, {@code 400} if {@code limit} isn't positive, or any other failure
     */
    public Page<FolderResponse> listFoldersPage(final String parentFolderId, final String cursor, final int limit) throws ApiException {
        return this.send(this.listFoldersPageRequest(parentFolderId, cursor, limit), FOLDER_PAGE_TYPE);
    }

    /** Async form of {@link #listFoldersPage(String, String, int)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Page<FolderResponse>> listFoldersPageAsync(final String parentFolderId, final String cursor, final int limit) {
        return this.sendAsync(this.listFoldersPageRequest(parentFolderId, cursor, limit), FOLDER_PAGE_TYPE);
    }

    private HttpRequest listFoldersPageRequest(final String parentFolderId, final String cursor, final int limit) {
        final StringBuilder query = new StringBuilder("/folders?limit=").append(limit);
        if (parentFolderId != null) {
            query.append("&parentFolderId=").append(URLEncoder.encode(parentFolderId, StandardCharsets.UTF_8));
        }
        if (cursor != null) {
            query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return this.requestBuilder(this.apiBaseUrl.resolve(query.toString()), true).GET().build();
    }

    /**
     * {@code PUT /folders/{id}}: renames and/or moves a folder in one step - a full replace of
     * both fields, not a partial patch; {@code newParentFolderId} {@code null} moves it to the
     * top level.
     *
     * @param folderId          the folder to rename/move
     * @param newName           the folder's new name
     * @param newParentFolderId the folder's new parent id, or {@code null} to move it to the top level
     * @throws ApiException {@code 404} if {@code folderId}/{@code newParentFolderId} doesn't
     *                       exist or isn't owned by the caller, {@code 409} if the move would
     *                       create a cycle, {@code 401} if not logged in / token expired
     */
    public FolderResponse updateFolder(final String folderId, final String newName, final String newParentFolderId) throws ApiException {
        return this.send(this.updateFolderRequest(folderId, newName, newParentFolderId), FolderResponse.class);
    }

    /** Async form of {@link #updateFolder(String, String, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<FolderResponse> updateFolderAsync(final String folderId, final String newName, final String newParentFolderId) {
        return this.sendAsync(this.updateFolderRequest(folderId, newName, newParentFolderId), FolderResponse.class);
    }

    /** Builds the {@code PUT /folders/{id}} request against {@link #apiBaseUrl}, with a JSON {@link UpdateFolderRequest} body. */
    private HttpRequest updateFolderRequest(final String folderId, final String newName, final String newParentFolderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId), true)
                .header("Content-Type", "application/json")
                .method("PUT", BodyPublishers.ofString(GSON.toJson(new UpdateFolderRequest(newName, newParentFolderId))))
                .build();
    }

    /**
     * {@code DELETE /folders/{id}}: deletes an empty folder.
     *
     * @param folderId the folder to delete
     * @throws ApiException {@code 404} if {@code folderId} doesn't exist or isn't owned by the
     *                       caller, {@code 409} if it still has child folders or files, {@code
     *                       401} if not logged in / token expired
     */
    public void deleteFolder(final String folderId) throws ApiException {
        this.send(this.deleteFolderRequest(folderId), Void.class);
    }

    /** Async form of {@link #deleteFolder(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> deleteFolderAsync(final String folderId) {
        return this.sendAsync(this.deleteFolderRequest(folderId), Void.class);
    }

    /** Builds the {@code DELETE /folders/{id}} request against {@link #apiBaseUrl}. */
    private HttpRequest deleteFolderRequest(final String folderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId), true).DELETE().build();
    }

    // --- trash ----------------------------------------------------------

    /**
     * {@code GET /files/trash}: lists every file currently in the caller's trash, unpaginated
     * (trash is expected to stay small relative to a live tree - see the server-side route's own
     * Javadoc).
     *
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public List<TrashedFileSummaryResponse> listDeletedFiles() throws ApiException {
        final TrashedFileSummaryResponse[] files = this.send(this.listDeletedFilesRequest(), TrashedFileSummaryResponse[].class);
        return List.of(files);
    }

    /** Async form of {@link #listDeletedFiles()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<TrashedFileSummaryResponse>> listDeletedFilesAsync() {
        return this.sendAsync(this.listDeletedFilesRequest(), TrashedFileSummaryResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /files/trash} request against {@link #apiBaseUrl}. */
    private HttpRequest listDeletedFilesRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/trash"), true).GET().build();
    }

    /**
     * {@code POST /files/{id}/restore}: restores a trashed file back to its previous folder.
     *
     * @param fileId the file to restore
     * @throws ApiException {@code 404} if {@code fileId} doesn't exist or isn't owned by the
     *                       caller, {@code 409} if it isn't currently in the trash, {@code 401}
     *                       if not logged in / token expired
     */
    public void restoreFile(final String fileId) throws ApiException {
        this.send(this.restoreFileRequest(fileId), Void.class);
    }

    /** Async form of {@link #restoreFile(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> restoreFileAsync(final String fileId) {
        return this.sendAsync(this.restoreFileRequest(fileId), Void.class);
    }

    /** Builds the {@code POST /files/{id}/restore} request against {@link #apiBaseUrl}. */
    private HttpRequest restoreFileRequest(final String fileId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId + "/restore"), true)
                .POST(BodyPublishers.noBody())
                .build();
    }

    /**
     * {@code GET /folders/trash}: lists every folder currently in the caller's trash, unpaginated -
     * same reasoning as {@link #listDeletedFiles()}.
     *
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public List<TrashedFolderSummaryResponse> listDeletedFolders() throws ApiException {
        final TrashedFolderSummaryResponse[] folders = this.send(this.listDeletedFoldersRequest(), TrashedFolderSummaryResponse[].class);
        return List.of(folders);
    }

    /** Async form of {@link #listDeletedFolders()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<TrashedFolderSummaryResponse>> listDeletedFoldersAsync() {
        return this.sendAsync(this.listDeletedFoldersRequest(), TrashedFolderSummaryResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /folders/trash} request against {@link #apiBaseUrl}. */
    private HttpRequest listDeletedFoldersRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/trash"), true).GET().build();
    }

    /**
     * {@code POST /trash/empty}: permanently removes every file and folder currently in the
     * caller's trash - the "Empty trash bin" action, bypassing the server's configured retention
     * window entirely. Idempotent - also succeeds if the trash is already empty. Irreversible.
     *
     * @throws ApiException {@code 401} if not logged in / token expired, or any other failure
     */
    public void emptyTrash() throws ApiException {
        this.send(this.emptyTrashRequest(), Void.class);
    }

    /** Async form of {@link #emptyTrash()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> emptyTrashAsync() {
        return this.sendAsync(this.emptyTrashRequest(), Void.class);
    }

    /** Builds the {@code POST /trash/empty} request against {@link #apiBaseUrl}. */
    private HttpRequest emptyTrashRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/trash/empty"), true).POST(BodyPublishers.noBody()).build();
    }

    /**
     * {@code POST /folders/{id}/restore}: restores a trashed folder back to its previous parent.
     *
     * @param folderId the folder to restore
     * @throws ApiException {@code 404} if {@code folderId} doesn't exist or isn't owned by the
     *                       caller, {@code 409} if it isn't currently in the trash, {@code 401}
     *                       if not logged in / token expired
     */
    public void restoreFolder(final String folderId) throws ApiException {
        this.send(this.restoreFolderRequest(folderId), Void.class);
    }

    /** Async form of {@link #restoreFolder(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> restoreFolderAsync(final String folderId) {
        return this.sendAsync(this.restoreFolderRequest(folderId), Void.class);
    }

    /** Builds the {@code POST /folders/{id}/restore} request against {@link #apiBaseUrl}. */
    private HttpRequest restoreFolderRequest(final String folderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId + "/restore"), true)
                .POST(BodyPublishers.noBody())
                .build();
    }

    // --- cloud users ------------------------------------------------------

    /**
     * {@code GET /cloudUsers/{id}} on the main REST API - the caller's own {@link
     * CloudUserResponse}, whose {@code timeStamp} is set once at account-confirmation time and
     * therefore doubles as the account's creation timestamp (see {@link CloudUserResponse}'s own
     * Javadoc). The server 404s for any id other than the authenticated caller's own.
     *
     * @param authUserId the account id to fetch - must equal the caller's own id
     * @throws ApiException {@code 404} if {@code authUserId} doesn't match the caller's own id
     *                       (or has no {@code CloudUser} record yet), {@code 401} if not logged
     *                       in / token expired
     */
    public CloudUserResponse getCloudUser(final String authUserId) throws ApiException {
        return this.send(this.getCloudUserRequest(authUserId), CloudUserResponse.class);
    }

    /** Async form of {@link #getCloudUser(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<CloudUserResponse> getCloudUserAsync(final String authUserId) {
        return this.sendAsync(this.getCloudUserRequest(authUserId), CloudUserResponse.class);
    }

    /** Builds the {@code GET /cloudUsers/{id}} request against {@link #apiBaseUrl}. */
    private HttpRequest getCloudUserRequest(final String authUserId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/cloudUsers/" + authUserId), true).GET().build();
    }

    /**
     * {@code PUT /cloudUsers/theme} (added 2026-09-04): syncs the caller's light/dark theme
     * choice to their account, so it follows them to every other device signed into the same
     * account instead of staying a local, per-device setting.
     *
     * @param themeMode the new theme preference (e.g. {@code "LIGHT"}/{@code "DARK"}), or {@code
     *                   null} to clear the stored preference
     * @throws ApiException {@code 401} if not logged in / token expired
     */
    public void updateThemePreference(final String themeMode) throws ApiException {
        this.send(this.updateThemePreferenceRequest(themeMode), Void.class);
    }

    /** Async form of {@link #updateThemePreference(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> updateThemePreferenceAsync(final String themeMode) {
        return this.sendAsync(this.updateThemePreferenceRequest(themeMode), Void.class);
    }

    /** Builds the {@code PUT /cloudUsers/theme} request against {@link #apiBaseUrl}, with a JSON {@link UpdateThemeRequest} body. */
    private HttpRequest updateThemePreferenceRequest(final String themeMode) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/cloudUsers/theme"), true)
                .header("Content-Type", "application/json")
                .method("PUT", BodyPublishers.ofString(GSON.toJson(new UpdateThemeRequest(themeMode))))
                .build();
    }

    // --- me / admin --------------------------------------------------------

    /**
     * {@code GET /auth/me} on the main REST API - the signed-in caller's own account id, email
     * address, and admin flag. Used to decide whether to show admin-only UI, since there is no
     * other way for a client to learn this without probing an admin-gated route.
     *
     * @throws ApiException {@code 401} if not logged in / token expired
     */
    public MeResponse getMe() throws ApiException {
        return this.send(this.getMeRequest(), MeResponse.class);
    }

    /** Async form of {@link #getMe()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<MeResponse> getMeAsync() {
        return this.sendAsync(this.getMeRequest(), MeResponse.class);
    }

    /** Builds the {@code GET /auth/me} request against {@link #apiBaseUrl}. */
    private HttpRequest getMeRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/auth/me"), true).GET().build();
    }

    /**
     * {@code GET /admin/authUsers} on the main REST API - admin-gated, lists every registered
     * account.
     *
     * @throws ApiException {@code 403} if the caller's own account isn't flagged admin, {@code
     *                       401} if not logged in / token expired
     */
    public List<AuthUserResponse> listAdminAuthUsers() throws ApiException {
        final AuthUserResponse[] users = this.send(this.listAdminAuthUsersRequest(), AuthUserResponse[].class);
        return List.of(users);
    }

    /** Async form of {@link #listAdminAuthUsers()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<AuthUserResponse>> listAdminAuthUsersAsync() {
        return this.sendAsync(this.listAdminAuthUsersRequest(), AuthUserResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /admin/authUsers} request against {@link #apiBaseUrl}. */
    private HttpRequest listAdminAuthUsersRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/admin/authUsers"), true).GET().build();
    }

    /**
     * {@code GET /admin/audit-log} on the main REST API - admin-gated. {@code all} {@code true}
     * returns every entry instead of just the most recent 20; {@code emailFilter}, if non-null and
     * non-blank, scopes the listing to just that account's own actions - both can be combined.
     *
     * @throws ApiException {@code 403} if the caller's own account isn't flagged admin, {@code
     *                       401} if not logged in / token expired
     */
    public List<AuditLogEntryResponse> listAdminAuditLog(final boolean all, final String emailFilter) throws ApiException {
        final AuditLogEntryResponse[] entries = this.send(this.listAdminAuditLogRequest(all, emailFilter), AuditLogEntryResponse[].class);
        return List.of(entries);
    }

    /** Async form of {@link #listAdminAuditLog(boolean, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<AuditLogEntryResponse>> listAdminAuditLogAsync(final boolean all, final String emailFilter) {
        return this.sendAsync(this.listAdminAuditLogRequest(all, emailFilter), AuditLogEntryResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /admin/audit-log?all=...&email=...} request against {@link #apiBaseUrl}. */
    private HttpRequest listAdminAuditLogRequest(final boolean all, final String emailFilter) {
        final StringBuilder query = new StringBuilder("/admin/audit-log?all=").append(all);
        if (emailFilter != null && !emailFilter.isBlank()) {
            query.append("&email=").append(URLEncoder.encode(emailFilter, StandardCharsets.UTF_8));
        }
        return this.requestBuilder(this.apiBaseUrl.resolve(query.toString()), true).GET().build();
    }

    /**
     * {@code GET /admin/metrics} on the main REST API - admin-gated. Reads item 13's
     * counters/gauges (upload outcomes, quota rejections, pending-upload queue depth, extensions
     * by status) straight from the server's in-process Prometheus registry.
     *
     * @throws ApiException {@code 403} if the caller's own account isn't flagged admin, {@code
     *                       401} if not logged in / token expired, {@code 503} if {@code
     *                       cloud-driver-extensions-metrics} isn't running on this deployment
     */
    public MetricsSnapshotResponse getAdminMetrics() throws ApiException {
        return this.send(this.getAdminMetricsRequest(), MetricsSnapshotResponse.class);
    }

    /** Async form of {@link #getAdminMetrics()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<MetricsSnapshotResponse> getAdminMetricsAsync() {
        return this.sendAsync(this.getAdminMetricsRequest(), MetricsSnapshotResponse.class);
    }

    /** Builds the {@code GET /admin/metrics} request against {@link #apiBaseUrl}. */
    private HttpRequest getAdminMetricsRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/admin/metrics"), true).GET().build();
    }

    // --- sharing (item 9) --------------------------------------------------

    /**
     * {@code POST /files/{id}/share}: grants {@code granteeEmail}'s account read-only access to
     * {@code fileId}. Idempotent - sharing with the same grantee again just refreshes the grant.
     *
     * @throws ApiException {@code 404} if {@code fileId} isn't owned by the caller, is currently
     *                       trashed, or {@code granteeEmail} has no registered account, {@code
     *                       401} if not logged in / token expired
     */
    public void shareFile(final String fileId, final String granteeEmail) throws ApiException {
        this.send(this.shareFileRequest(fileId, granteeEmail), Void.class);
    }

    /** Async form of {@link #shareFile(String, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> shareFileAsync(final String fileId, final String granteeEmail) {
        return this.sendAsync(this.shareFileRequest(fileId, granteeEmail), Void.class);
    }

    /** Builds the {@code POST /files/{id}/share} request against {@link #apiBaseUrl}, with a JSON {@link ShareRequest} body. */
    private HttpRequest shareFileRequest(final String fileId, final String granteeEmail) {
        return this.postRequest(this.apiBaseUrl.resolve("/files/" + fileId + "/share"), new ShareRequest(granteeEmail), true);
    }

    /**
     * {@code DELETE /files/{id}/share/{email}}: revokes a previously-granted share. Idempotent -
     * also succeeds if no such grant existed.
     *
     * @throws ApiException {@code 404} if {@code fileId} isn't owned by the caller or {@code
     *                       granteeEmail} has no registered account, {@code 401} if not logged in
     *                       / token expired
     */
    public void revokeFileShare(final String fileId, final String granteeEmail) throws ApiException {
        this.send(this.revokeFileShareRequest(fileId, granteeEmail), Void.class);
    }

    /** Async form of {@link #revokeFileShare(String, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> revokeFileShareAsync(final String fileId, final String granteeEmail) {
        return this.sendAsync(this.revokeFileShareRequest(fileId, granteeEmail), Void.class);
    }

    /** Builds the {@code DELETE /files/{id}/share/{email}} request against {@link #apiBaseUrl}. */
    private HttpRequest revokeFileShareRequest(final String fileId, final String granteeEmail) {
        final String encodedEmail = URLEncoder.encode(granteeEmail, StandardCharsets.UTF_8);
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId + "/share/" + encodedEmail), true).DELETE().build();
    }

    /**
     * {@code GET /files/{id}/share}: lists the email addresses of every account {@code fileId} is
     * currently shared with - owner-only, backs a "who can see this file"/revoke UI.
     *
     * @throws ApiException {@code 404} if {@code fileId} isn't owned by the caller, {@code 401} if
     *                       not logged in / token expired
     */
    public List<String> listFileShares(final String fileId) throws ApiException {
        final String[] emails = this.send(this.listFileSharesRequest(fileId), String[].class);
        return List.of(emails);
    }

    /** Async form of {@link #listFileShares(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<String>> listFileSharesAsync(final String fileId) {
        return this.sendAsync(this.listFileSharesRequest(fileId), String[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /files/{id}/share} request against {@link #apiBaseUrl}. */
    private HttpRequest listFileSharesRequest(final String fileId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId + "/share"), true).GET().build();
    }

    /**
     * {@code GET /files/shared-with-me}: lists every file directly shared with the caller, each
     * paired with the sharing account's email address ({@link SharedFileSummaryResponse#ownerEmail()}
     * - added 2026-09-02, so a recipient can see who shared a file, not just that it was shared).
     * Does not include a file only reachable through a folder-level share.
     *
     * @throws ApiException {@code 401} if not logged in / token expired
     */
    public List<SharedFileSummaryResponse> listSharedWithMe() throws ApiException {
        final SharedFileSummaryResponse[] files = this.send(this.listSharedWithMeRequest(), SharedFileSummaryResponse[].class);
        return List.of(files);
    }

    /** Async form of {@link #listSharedWithMe()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<SharedFileSummaryResponse>> listSharedWithMeAsync() {
        return this.sendAsync(this.listSharedWithMeRequest(), SharedFileSummaryResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /files/shared-with-me} request against {@link #apiBaseUrl}. */
    private HttpRequest listSharedWithMeRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/shared-with-me"), true).GET().build();
    }

    /**
     * {@code GET /files/shared-by-me/count}: counts the caller's own distinct files that currently
     * have at least one active share - the owner-side counterpart to {@link #listSharedWithMe()}'s
     * grantee-side listing. Backs the desktop app's Dashboard "Shared files" stat card.
     *
     * @throws ApiException {@code 401} if not logged in / token expired
     */
    public int countFilesSharedByMe() throws ApiException {
        return this.send(this.countFilesSharedByMeRequest(), SharedByMeCountResponse.class).count();
    }

    /** Async form of {@link #countFilesSharedByMe()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Integer> countFilesSharedByMeAsync() {
        return this.sendAsync(this.countFilesSharedByMeRequest(), SharedByMeCountResponse.class).thenApply(SharedByMeCountResponse::count);
    }

    /** Builds the {@code GET /files/shared-by-me/count} request against {@link #apiBaseUrl}. */
    private HttpRequest countFilesSharedByMeRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/shared-by-me/count"), true).GET().build();
    }

    /**
     * {@code POST /folders/{id}/share}: grants {@code granteeEmail}'s account read-only access to
     * {@code folderId} and everything nested inside it. Same status mapping as {@link
     * #shareFile(String, String)}.
     */
    public void shareFolder(final String folderId, final String granteeEmail) throws ApiException {
        this.send(this.shareFolderRequest(folderId, granteeEmail), Void.class);
    }

    /** Async form of {@link #shareFolder(String, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> shareFolderAsync(final String folderId, final String granteeEmail) {
        return this.sendAsync(this.shareFolderRequest(folderId, granteeEmail), Void.class);
    }

    /** Builds the {@code POST /folders/{id}/share} request against {@link #apiBaseUrl}, with a JSON {@link ShareRequest} body. */
    private HttpRequest shareFolderRequest(final String folderId, final String granteeEmail) {
        return this.postRequest(this.apiBaseUrl.resolve("/folders/" + folderId + "/share"), new ShareRequest(granteeEmail), true);
    }

    /**
     * {@code DELETE /folders/{id}/share/{email}}: revokes a previously-granted share. Same status
     * mapping as {@link #revokeFileShare(String, String)}. Does not affect a direct file-level
     * share on a file nested inside {@code folderId}.
     */
    public void revokeFolderShare(final String folderId, final String granteeEmail) throws ApiException {
        this.send(this.revokeFolderShareRequest(folderId, granteeEmail), Void.class);
    }

    /** Async form of {@link #revokeFolderShare(String, String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> revokeFolderShareAsync(final String folderId, final String granteeEmail) {
        return this.sendAsync(this.revokeFolderShareRequest(folderId, granteeEmail), Void.class);
    }

    /** Builds the {@code DELETE /folders/{id}/share/{email}} request against {@link #apiBaseUrl}. */
    private HttpRequest revokeFolderShareRequest(final String folderId, final String granteeEmail) {
        final String encodedEmail = URLEncoder.encode(granteeEmail, StandardCharsets.UTF_8);
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId + "/share/" + encodedEmail), true).DELETE().build();
    }

    /**
     * {@code GET /folders/{id}/share}: lists the email addresses of every account {@code
     * folderId} is currently shared with - same shape as {@link #listFileShares(String)}.
     */
    public List<String> listFolderShares(final String folderId) throws ApiException {
        final String[] emails = this.send(this.listFolderSharesRequest(folderId), String[].class);
        return List.of(emails);
    }

    /** Async form of {@link #listFolderShares(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<String>> listFolderSharesAsync(final String folderId) {
        return this.sendAsync(this.listFolderSharesRequest(folderId), String[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /folders/{id}/share} request against {@link #apiBaseUrl}. */
    private HttpRequest listFolderSharesRequest(final String folderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId + "/share"), true).GET().build();
    }

    /**
     * {@code GET /folders/{id}/shared-contents}: lists the non-trashed files/subfolders directly
     * inside {@code folderId} - reachable by its owner or by anyone it's shared with (directly or
     * via an ancestor folder). Backs "browse into a shared folder"/"download this shared folder".
     *
     * @throws ApiException {@code 404} if {@code folderId} doesn't exist, is trashed, or isn't
     *                       owned by/shared with the caller, {@code 401} if not logged in / token expired
     */
    public SharedFolderContentsResponse listSharedFolderContents(final String folderId) throws ApiException {
        return this.send(this.listSharedFolderContentsRequest(folderId), SharedFolderContentsResponse.class);
    }

    /** Async form of {@link #listSharedFolderContents(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<SharedFolderContentsResponse> listSharedFolderContentsAsync(final String folderId) {
        return this.sendAsync(this.listSharedFolderContentsRequest(folderId), SharedFolderContentsResponse.class);
    }

    /** Builds the {@code GET /folders/{id}/shared-contents} request against {@link #apiBaseUrl}. */
    private HttpRequest listSharedFolderContentsRequest(final String folderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId + "/shared-contents"), true).GET().build();
    }

    /**
     * {@code GET /folders/shared-with-me}: lists every folder directly shared with the caller, each
     * paired with the sharing account's email address - same "who shared this" addition as {@link
     * #listSharedWithMe()}.
     *
     * @throws ApiException {@code 401} if not logged in / token expired
     */
    public List<SharedFolderSummaryResponse> listSharedFoldersWithMe() throws ApiException {
        final SharedFolderSummaryResponse[] folders = this.send(this.listSharedFoldersWithMeRequest(), SharedFolderSummaryResponse[].class);
        return List.of(folders);
    }

    /** Async form of {@link #listSharedFoldersWithMe()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<List<SharedFolderSummaryResponse>> listSharedFoldersWithMeAsync() {
        return this.sendAsync(this.listSharedFoldersWithMeRequest(), SharedFolderSummaryResponse[].class).thenApply(List::of);
    }

    /** Builds the {@code GET /folders/shared-with-me} request against {@link #apiBaseUrl}. */
    private HttpRequest listSharedFoldersWithMeRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/shared-with-me"), true).GET().build();
    }

    /**
     * {@code GET /cloudUsers/exists?email=<address>}: whether any account is registered under
     * {@code email} - not scoped to the caller's own account. Backs a live check of a typed
     * grantee address before submitting a share.
     *
     * @throws ApiException {@code 401} if not logged in / token expired
     */
    public boolean checkCloudUserExists(final String email) throws ApiException {
        return this.send(this.checkCloudUserExistsRequest(email), EmailExistsResponse.class).exists();
    }

    /** Async form of {@link #checkCloudUserExists(String)} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Boolean> checkCloudUserExistsAsync(final String email) {
        return this.sendAsync(this.checkCloudUserExistsRequest(email), EmailExistsResponse.class).thenApply(EmailExistsResponse::exists);
    }

    /** Builds the {@code GET /cloudUsers/exists?email=...} request against {@link #apiBaseUrl}. */
    private HttpRequest checkCloudUserExistsRequest(final String email) {
        final String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return this.requestBuilder(this.apiBaseUrl.resolve("/cloudUsers/exists?email=" + encodedEmail), true).GET().build();
    }

    // --- internals -------------------------------------------------------

    /**
     * Builds a {@code POST} request with {@code body} serialized to JSON, via {@link #requestBuilder}.
     *
     * @param uri           the target URI
     * @param body          the request body, serialized with {@link #GSON}
     * @param authenticated whether to attach the current session's bearer token
     * @return the built request
     */
    private HttpRequest postRequest(final URI uri, final Object body, final boolean authenticated) {
        return this.requestBuilder(uri, authenticated)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(GSON.toJson(body)))
                .build();
    }

    /**
     * Starts a request builder for {@code uri} with {@link #REQUEST_TIMEOUT} applied, optionally
     * attaching the current session's bearer token.
     *
     * @param uri           the target URI
     * @param authenticated whether to attach an {@code Authorization: Bearer} header
     * @return a builder ready for the caller to set its HTTP method/body on
     * @throws IllegalStateException if {@code authenticated} is {@code true} but no session is active
     */
    private HttpRequest.Builder requestBuilder(final URI uri, final boolean authenticated) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        if (authenticated) {
            final String currentToken = this.token.get();
            if (currentToken == null) {
                throw new IllegalStateException("@ApiClient: no active session - call login() first");
            }
            builder.header("Authorization", "Bearer " + currentToken);
        }
        return builder;
    }

    /**
     * Blocking send, via {@link HttpClient#send} directly - see the class Javadoc for why this isn't {@code sendAsync(...).join()}.
     *
     * @param request      the request to send
     * @param responseType the type to deserialize a successful JSON body into ({@link Void} for no body)
     * @return the deserialized response body, or {@code null} for {@link Void}
     * @throws ApiException on any non-{@code 2xx} response or transport/I/O failure
     */
    private <T> T send(final HttpRequest request, final Class<T> responseType) throws ApiException {
        return this.send(request, (Type) responseType);
    }

    /** True async send, via {@link HttpClient#sendAsync} - completes on {@link #executor()}, never a JDK-internal thread. */
    private <T> CompletableFuture<T> sendAsync(final HttpRequest request, final Class<T> responseType) {
        return this.sendAsync(request, (Type) responseType);
    }

    /**
     * {@link Type}-based counterpart to {@link #send(HttpRequest, Class)}, for a generic response
     * shape - see {@link #parseResponse(HttpResponse, Type)}. Also the single place a {@code 401}
     * on an authenticated request is transparently retried once, after a token refresh - see
     * {@link #canRetryWithRefresh}. The retry failing (refresh token also invalid, or the retried
     * request itself failing) surfaces as the <em>original</em> {@code 401}, chaining the retry
     * failure as its cause - a caller's existing {@code isUnauthorized()}-based handling (e.g.
     * {@link SessionManager#handleFailure}) keeps working unchanged either way.
     */
    private <T> T send(final HttpRequest request, final Type responseType) throws ApiException {
        final HttpResponse<InputStream> response = this.sendRaw(request);
        if (this.canRetryWithRefresh(request, response)) {
            final String fallbackMessage = extractErrorMessage(response.body());
            try {
                this.refresh();
                return parseResponse(this.sendRaw(this.rebuildWithCurrentToken(request)), responseType);
            } catch (final ApiException refreshOrRetryFailure) {
                throw new ApiException(401, fallbackMessage, refreshOrRetryFailure);
            }
        }
        return parseResponse(response, responseType);
    }

    /** {@link HttpClient#send}, translating its checked failure modes into {@link ApiException} - shared by every {@code send} overload. */
    private HttpResponse<InputStream> sendRaw(final HttpRequest request) throws ApiException {
        try {
            return this.httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (final IOException e) {
            throw new ApiException(0, "network error calling " + request.uri(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted calling " + request.uri(), e);
        }
    }

    /**
     * {@link Type}-based counterpart to {@link #sendAsync(HttpRequest, Class)}, for a generic
     * response shape - see {@link #parseResponse(HttpResponse, Type)}. Mirrors {@link
     * #send(HttpRequest, Type)}'s own transparent-retry-once-on-401 behavior - see that method's
     * Javadoc and {@link #canRetryWithRefresh} for the full contract.
     */
    private <T> CompletableFuture<T> sendAsync(final HttpRequest request, final Type responseType) {
        return this.httpClient.sendAsync(request, BodyHandlers.ofInputStream())
                .thenCompose(response -> {
                    if (this.canRetryWithRefresh(request, response)) {
                        final String fallbackMessage = extractErrorMessage(response.body());
                        return this.refreshAsync()
                                .thenCompose(ignored -> this.httpClient.sendAsync(this.rebuildWithCurrentToken(request), BodyHandlers.ofInputStream()))
                                .handle((retried, refreshOrRetryFailure) -> {
                                    if (refreshOrRetryFailure != null) {
                                        final Throwable cause = refreshOrRetryFailure instanceof CompletionException completionException
                                                ? completionException.getCause() : refreshOrRetryFailure;
                                        throw new CompletionException(new ApiException(401, fallbackMessage, cause));
                                    }
                                    try {
                                        return ApiClient.<T>parseResponse(retried, responseType);
                                    } catch (final ApiException e) {
                                        throw new CompletionException(e);
                                    }
                                });
                    }
                    try {
                        return CompletableFuture.completedFuture(ApiClient.<T>parseResponse(response, responseType));
                    } catch (final ApiException e) {
                        // Matches this codebase's own *Async convention (see DataFactory/EventFactory/etc.
                        // in cloud-driver-plugin): a checked failure from the sync primitive is surfaced
                        // from the async form wrapped in a CompletionException.
                        return CompletableFuture.<T>failedFuture(new CompletionException(e));
                    }
                });
    }

    /**
     * Whether {@code request}/{@code response} qualifies for the transparent refresh-and-retry
     * behavior {@link #send(HttpRequest, Type)}/{@link #sendAsync(HttpRequest, Type)} implement:
     * the response is a {@code 401}, this client currently holds a refresh token to retry with,
     * the failed request actually carried an {@code Authorization} header (an unauthenticated
     * call - {@link #login}, {@link #register}, {@link #refresh} itself, etc. - was never going
     * to succeed after a refresh, and refreshing in response to one would risk looping), and the
     * request wasn't {@link #refresh}'s own call (the same guard, made explicit rather than relied
     * on implicitly, since a bug elsewhere accidentally attaching a bearer header to the refresh
     * request would otherwise recurse).
     */
    private boolean canRetryWithRefresh(final HttpRequest request, final HttpResponse<InputStream> response) {
        return response.statusCode() == 401
                && this.refreshToken.get() != null
                && request.headers().firstValue("Authorization").isPresent()
                && !REFRESH_PATH.equals(request.uri().getPath());
    }

    /**
     * Rebuilds {@code original} with a fresh {@code Authorization} header (the current, just-
     * refreshed {@link #token}), preserving every other header, the timeout, the method, and the
     * body publisher unchanged - {@link HttpRequest} has no public "copy with one header changed"
     * builder, so this reconstructs one from {@code original}'s own getters instead.
     *
     * <p>Reusing {@code original}'s {@link HttpRequest#bodyPublisher()} is safe for the small
     * JSON/no-body requests every authenticated call in this class makes today (every {@code
     * Flow.Publisher} the JDK hands out for those is re-subscribable) - a large file transfer
     * ({@link #uploadFile(Path)}'s {@link BodyPublishers#ofFile}-backed publisher, or the
     * progress-tracking wrappers around it/{@link #downloadFileToPath}) could in principle also be
     * retried this way, but a 12h-access-token expiry landing mid-transfer is vanishingly rare in
     * practice, and a retried transfer's progress callback would restart from zero rather than
     * continue - an acceptable, deliberately undocumented-to-callers cosmetic quirk, not a
     * correctness concern, so no special-casing was added to exclude those calls from this path.
     */
    private HttpRequest rebuildWithCurrentToken(final HttpRequest original) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(original.uri());
        original.timeout().ifPresent(builder::timeout);
        original.headers().map().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Authorization")) {
                for (final String value : values) {
                    builder.header(name, value);
                }
            }
        });
        final String freshToken = this.token.get();
        if (freshToken != null) {
            builder.header("Authorization", "Bearer " + freshToken);
        }
        builder.method(original.method(), original.bodyPublisher().orElse(BodyPublishers.noBody()));
        return builder.build();
    }

    /**
     * Shared response-handling logic for both {@link #send} and {@link #sendAsync}: on a {@code
     * 2xx} status, deserializes the body as JSON into {@code responseType} (or discards it and
     * returns {@code null} if {@code responseType} is {@link Void}); otherwise closes the body and
     * throws {@link ApiException} carrying the best available error message.
     *
     * @param response     the completed response, whose body is consumed and closed by this method
     * @param responseType the type to deserialize a successful JSON body into ({@link Void} for no body)
     * @return the deserialized response body, or {@code null} for {@link Void}
     * @throws ApiException on any non-{@code 2xx} status or I/O failure reading the body
     */
    private static <T> T parseResponse(final HttpResponse<InputStream> response, final Class<T> responseType) throws ApiException {
        return parseResponse(response, (Type) responseType);
    }

    /**
     * {@link Type}-based counterpart to {@link #parseResponse(HttpResponse, Class)}, needed
     * whenever the response shape itself is generic - e.g. {@code Page<StoredFileSummaryResponse>}
     * - since a plain {@link Class} literal cannot carry a type argument and Gson would otherwise
     * deserialize {@code items} as raw {@link java.util.LinkedHashMap}s instead of the real
     * response record. Build the {@link Type} via {@link TypeToken} at the call site (see {@link
     * #listFilesPage}/{@link #listFoldersPage}).
     */
    private static <T> T parseResponse(final HttpResponse<InputStream> response, final Type responseType) throws ApiException {
        final int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status >= 200 && status < 300) {
                if (responseType == Void.class) {
                    body.readAllBytes();
                    return null;
                }
                try (Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8)) {
                    return GSON.fromJson(reader, responseType);
                }
            }
            throw new ApiException(status, extractErrorMessage(body), null);
        } catch (final IOException e) {
            throw new ApiException(0, "I/O error reading response from " + response.uri(), e);
        }
    }

    /**
     * Best-effort extraction of a human-readable message from a failed response body: parses it
     * as an {@link ErrorResponse} and returns its {@code title}, falling back to the raw body text
     * if it isn't valid JSON, or a generic message if the body is empty or unreadable.
     *
     * @param body the failed response's still-open body; consumed but not closed by this method
     * @return the best available error message
     */
    private static String extractErrorMessage(final InputStream body) {
        final String text;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            return "request failed and the error body could not be read";
        }
        if (text.isBlank()) {
            return "request failed with no response body";
        }
        try {
            final ErrorResponse error = GSON.fromJson(text, ErrorResponse.class);
            return error != null && error.title() != null ? error.title() : text;
        } catch (final RuntimeException malformedJson) {
            return text;
        }
    }

    /**
     * Waits for every future in {@code futures} to reach completion - success or failure - before
     * completing itself, unlike a bare {@link CompletableFuture#allOf} (which completes as soon
     * as any one constituent fails, leaving the rest to finish unobserved in the background).
     * Matches this codebase's own batch-operation convention (see {@code EntityDatabaseClient}'s
     * Javadoc: "throws the first failure encountered once every item has been attempted").
     *
     * @param futures the futures to await
     * @return a future completing with every successful result (in {@code futures}' own order,
     * successes only), or exceptionally with the first failure encountered
     */
    private static <T> CompletableFuture<List<T>> awaitAll(final List<CompletableFuture<T>> futures) {
        final CompletableFuture<?>[] settled = futures.stream()
                .map(future -> future.handle((value, error) -> null))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(settled).thenApply(ignored -> {
            Throwable firstFailure = null;
            final List<T> results = new ArrayList<>(futures.size());
            for (final CompletableFuture<T> future : futures) {
                if (future.isCompletedExceptionally()) {
                    if (firstFailure == null) {
                        try {
                            future.join();
                        } catch (final CompletionException e) {
                            firstFailure = e.getCause();
                        } catch (final CancellationException e) {
                            firstFailure = e;
                        }
                    }
                } else {
                    results.add(future.join());
                }
            }
            if (firstFailure != null) {
                throw firstFailure instanceof CompletionException completionException
                        ? completionException : new CompletionException(firstFailure);
            }
            return results;
        });
    }

    /**
     * Closes {@code closeable}, swallowing any {@link IOException} - used only on a best-effort
     * cleanup path where nothing more useful can be done with a close failure.
     *
     * @param closeable the resource to close
     */
    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
            // Best-effort cleanup only - the caller already has what it needs (or is on an
            // already-failed path), and there is nothing more useful to do with a close failure.
        }
    }

    /** Shuts down the virtual-thread executor backing every async call this client makes. Idempotent. */
    @Override
    public void close() {
        this.executor.shutdown();
    }

    /** Thrown for any non-2xx response or transport failure; {@link #statusCode} is {@code 0} for the latter. */
    public static final class ApiException extends Exception {

        /** The HTTP status code, or {@code 0} for a transport-level failure with no response received. */
        private final int statusCode;

        /**
         * @param statusCode the HTTP status code, or {@code 0} for a transport-level failure
         * @param message    a human-readable description of the failure
         * @param cause      the underlying exception, or {@code null} if there isn't one
         */
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
