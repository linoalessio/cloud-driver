package de.lino.cloud.platform.rest.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import de.lino.cloud.platform.rest.api.dto.Dtos.AuthRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.AuthResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.ChangeEmailRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.CloudUserResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.ConfirmChangeEmailRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.ConfirmPasswordResetRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.ConfirmRegistrationRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.CreateFolderRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.ErrorResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.FolderResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.MessageResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.MoveFileRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.Page;
import de.lino.cloud.platform.rest.api.dto.Dtos.RequestPasswordResetRequest;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.StoredFileSummaryResponse;
import de.lino.cloud.platform.rest.api.dto.Dtos.UpdateFolderRequest;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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
 * database credentials anywhere on this machine, matching the "desktop desktop must never see the
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
 * desktop's single-user-at-a-time nature. Wrap in your own synchronization if that ever changes.
 */
public final class ApiClient implements AutoCloseable {

    /** Default cap on simultaneously in-flight transfers for {@link #uploadFilesAsync(Map)}. */
    public static final int DEFAULT_MAX_CONCURRENT_TRANSFERS = 8;

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

    private static final Gson GSON = new Gson();

    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final URI authPanelBaseUrl;
    private final URI apiBaseUrl;

    /** The current session's JWT, once {@link #login} has succeeded; {@code null} until then. */
    private final AtomicReference<String> token = new AtomicReference<>();

    /**
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

    /** @return {@code true} once {@link #login} has produced a token still held in memory. */
    public boolean isAuthenticated() {
        return this.token.get() != null;
    }

    /**
     * @return the raw JWT currently held in memory (set by {@link #login}/{@link #register}'s
     * confirm step/{@link #restoreSession}), or empty if not authenticated. Added for {@link
     * SessionManager}-based session-restore callers that need the token itself after a successful
     * {@link SessionManager#tryRestoreSession()}/{@code tryRestoreSessionAsync()} (e.g. to decode
     * its {@code sub} claim client-side) rather than just the boolean "did it work" answer that
     * method returns.
     */
    public Optional<String> currentToken() {
        return Optional.ofNullable(this.token.get());
    }

    /** Restores a previously persisted token (e.g. loaded from the OS keychain) without a fresh login. */
    public void restoreSession(final String previouslyIssuedToken) {
        this.token.set(Objects.requireNonNull(previouslyIssuedToken, "previouslyIssuedToken cannot be null"));
    }

    /** Discards the in-memory token; the caller is responsible for also clearing any persisted copy. */
    public void logout() {
        this.token.set(null);
    }

    // --- auth ---------------------------------------------------------

    /**
     * {@code POST /auth/login} on the auth-panel host - the only auth route the server exposes.
     * The request body's field is literally named {@code username} (see {@link AuthRequest}),
     * even though {@code emailAddress} is what's actually passed for it.
     *
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 401} on wrong credentials, or any other transport/HTTP failure
     */
    public String login(final String emailAddress, final String password) throws ApiException {
        final AuthResponse response = this.send(this.loginRequest(emailAddress, password), AuthResponse.class);
        this.token.set(response.token());
        return response.token();
    }

    /** Async form of {@link #login} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<String> loginAsync(final String emailAddress, final String password) {
        return this.sendAsync(this.loginRequest(emailAddress, password), AuthResponse.class)
                .thenApply(response -> {
                    this.token.set(response.token());
                    return response.token();
                });
    }

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

    private HttpRequest registerRequest(final String emailAddress, final String password) {
        return this.postRequest(this.authPanelBaseUrl.resolve("/auth/register"), new AuthRequest(emailAddress, password), false);
    }

    /**
     * {@code POST /auth/register/confirm} on the auth-panel host - step two of registration:
     * submits the verification code {@link #register} caused the server to e-mail, and is what
     * actually creates the account. Same response shape as {@link #login} - a successful
     * confirmation leaves the caller already authenticated.
     *
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 400} if the code is missing, expired, or does not match, or
     *                       any other transport/HTTP failure
     */
    public String confirmRegistration(final String emailAddress, final String code) throws ApiException {
        final AuthResponse response = this.send(this.confirmRegistrationRequest(emailAddress, code), AuthResponse.class);
        this.token.set(response.token());
        return response.token();
    }

    /** Async form of {@link #confirmRegistration} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<String> confirmRegistrationAsync(final String emailAddress, final String code) {
        return this.sendAsync(this.confirmRegistrationRequest(emailAddress, code), AuthResponse.class)
                .thenApply(response -> {
                    this.token.set(response.token());
                    return response.token();
                });
    }

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
     * @return the freshly issued JWT, already stored for subsequent calls
     * @throws ApiException {@code 400} if the code is missing, expired, or does not match, or
     *                       any other transport/HTTP failure
     */
    public String confirmPasswordReset(final String emailAddress, final String code, final String newPassword) throws ApiException {
        final AuthResponse response = this.send(this.confirmPasswordResetRequest(emailAddress, code, newPassword), AuthResponse.class);
        this.token.set(response.token());
        return response.token();
    }

    /** Async form of {@link #confirmPasswordReset} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<String> confirmPasswordResetAsync(final String emailAddress, final String code, final String newPassword) {
        return this.sendAsync(this.confirmPasswordResetRequest(emailAddress, code, newPassword), AuthResponse.class)
                .thenApply(response -> {
                    this.token.set(response.token());
                    return response.token();
                });
    }

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

    private HttpRequest confirmEmailChangeRequest(final String code) {
        return this.postRequest(this.apiBaseUrl.resolve("/auth/change-email/confirm"), new ConfirmChangeEmailRequest(code), true);
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
     * @throws ApiException {@code 401} if not logged in / token expired, if {@code filePath}
     *                       doesn't exist or can't be read, or any other failure
     */
    public StoredFileSummaryResponse uploadFile(final Path filePath) throws ApiException {
        return this.uploadFile(filePath.getFileName().toString(), filePath);
    }

    /** Same as {@link #uploadFile(Path)}, with an explicit {@code fileName} instead of the path's own file name. */
    public StoredFileSummaryResponse uploadFile(final String fileName, final Path filePath) throws ApiException {
        return this.uploadFile(fileName, filePath, null);
    }

    /** Same as {@link #uploadFile(String, Path)}, placing the new file directly into {@code folderId} instead of the root. */
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

        private final Flow.Subscriber<? super ByteBuffer> downstream;
        private final LongConsumer onBytesTransferred;
        private long transferred = 0;

        private ProgressTrackingSubscriber(final Flow.Subscriber<? super ByteBuffer> downstream, final LongConsumer onBytesTransferred) {
            this.downstream = downstream;
            this.onBytesTransferred = onBytesTransferred;
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.downstream.onSubscribe(subscription);
        }

        @Override
        public void onNext(final ByteBuffer item) {
            this.transferred += item.remaining();
            this.onBytesTransferred.accept(this.transferred);
            this.downstream.onNext(item);
        }

        @Override
        public void onError(final Throwable throwable) {
            this.downstream.onError(throwable);
        }

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
     */
    public CompletableFuture<List<StoredFileSummaryResponse>> uploadFilesAsync(final Map<String, Path> filesByName,
                                                                          final int maxConcurrentTransfers) {
        final Semaphore permits = new Semaphore(Math.max(1, maxConcurrentTransfers));
        final List<CompletableFuture<StoredFileSummaryResponse>> uploads = filesByName.entrySet().stream()
                .map(entry -> this.withPermit(permits, () -> this.uploadFileAsync(entry.getKey(), entry.getValue())))
                .toList();
        return awaitAll(uploads);
    }

    /** Acquires a permit (asynchronously, on {@link #executor}) before running {@code action}, and always releases it once that action's future completes. */
    private <T> CompletableFuture<T> withPermit(final Semaphore permits, final Supplier<CompletableFuture<T>> action) {
        return CompletableFuture
                .runAsync(permits::acquireUninterruptibly, this.executor)
                .thenCompose(ignored -> action.get())
                .whenComplete((result, error) -> permits.release());
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

    private HttpRequest listFilesRequest() {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files"), true).GET().build();
    }

    /** Lazily pulls one {@link StoredFileSummaryResponse} at a time off an open {@link JsonReader} positioned inside a JSON array. */
    private static final class JsonArrayIterator implements Iterator<StoredFileSummaryResponse> {

        private final JsonReader jsonReader;
        private Boolean hasNextCache;

        private JsonArrayIterator(final JsonReader jsonReader) {
            this.jsonReader = jsonReader;
        }

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

    /** Wraps {@link BodySubscribers#ofFile} so every chunk written to disk is also counted first - see {@link ProgressTrackingBodySubscriber}. */
    private static BodyHandler<Path> progressTrackingFileHandler(final Path destination, final LongConsumer onBytesTransferred) {
        return responseInfo -> new ProgressTrackingBodySubscriber<>(BodySubscribers.ofFile(destination), onBytesTransferred);
    }

    /**
     * Reports cumulative bytes as each chunk (a {@code List<ByteBuffer>}, per {@link
     * BodySubscriber}'s own shape) passes through, before forwarding it downstream unchanged - the
     * download-side mirror of {@link ProgressTrackingSubscriber}, wrapping a {@link BodySubscriber}
     * (used for a response body) rather than a {@link Flow.Subscriber} (used for a request body).
     */
    private static final class ProgressTrackingBodySubscriber<T> implements BodySubscriber<T> {

        private final BodySubscriber<T> delegate;
        private final LongConsumer onBytesTransferred;
        private long transferred = 0;

        private ProgressTrackingBodySubscriber(final BodySubscriber<T> delegate, final LongConsumer onBytesTransferred) {
            this.delegate = delegate;
            this.onBytesTransferred = onBytesTransferred;
        }

        @Override
        public CompletionStage<T> getBody() {
            return this.delegate.getBody();
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.delegate.onSubscribe(subscription);
        }

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

        @Override
        public void onError(final Throwable throwable) {
            this.delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            this.delegate.onComplete();
        }

    }

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
     */
    private static Path requireSuccessfulFileDownload(final HttpResponse<Path> response) throws ApiException {
        final int status = response.statusCode();
        final Path destination = response.body();
        if (status >= 200 && status < 300) {
            return destination;
        }
        throw new ApiException(status, extractErrorMessageFromFile(destination), null);
    }

    /** {@link #extractErrorMessage(InputStream)}, reading from a file on disk instead of an open response stream, then deleting it. */
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

    private HttpRequest deleteFileRequest(final String fileId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/files/" + fileId), true).DELETE().build();
    }

    /**
     * Deletes every id in {@code fileIds} concurrently (unbounded - unlike {@link
     * #uploadFilesAsync}, a delete carries no request body/file handle, so there is nothing
     * costly to throttle).
     *
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

    private HttpRequest createFolderRequest(final String name, final String parentFolderId) {
        return this.postRequest(this.apiBaseUrl.resolve("/folders"), new CreateFolderRequest(name, parentFolderId), true);
    }

    /**
     * {@code GET /folders}: lists the caller's own folders directly inside {@code parentFolderId}
     * - {@code null} lists their top-level folders.
     *
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

    private HttpRequest updateFolderRequest(final String folderId, final String newName, final String newParentFolderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId), true)
                .header("Content-Type", "application/json")
                .method("PUT", BodyPublishers.ofString(GSON.toJson(new UpdateFolderRequest(newName, newParentFolderId))))
                .build();
    }

    /**
     * {@code DELETE /folders/{id}}: deletes an empty folder.
     *
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

    private HttpRequest deleteFolderRequest(final String folderId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/folders/" + folderId), true).DELETE().build();
    }

    // --- cloud users ------------------------------------------------------

    /**
     * {@code GET /cloudUsers/{id}} on the main REST API - the caller's own {@link
     * CloudUserResponse}, whose {@code timeStamp} is set once at account-confirmation time and
     * therefore doubles as the account's creation timestamp (see {@link CloudUserResponse}'s own
     * Javadoc). The server 404s for any id other than the authenticated caller's own.
     *
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

    private HttpRequest getCloudUserRequest(final String authUserId) {
        return this.requestBuilder(this.apiBaseUrl.resolve("/cloudUsers/" + authUserId), true).GET().build();
    }

    // --- internals -------------------------------------------------------

    private HttpRequest postRequest(final URI uri, final Object body, final boolean authenticated) {
        return this.requestBuilder(uri, authenticated)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(GSON.toJson(body)))
                .build();
    }

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

    /** Blocking send, via {@link HttpClient#send} directly - see the class Javadoc for why this isn't {@code sendAsync(...).join()}. */
    private <T> T send(final HttpRequest request, final Class<T> responseType) throws ApiException {
        final HttpResponse<InputStream> response;
        try {
            response = this.httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (final IOException e) {
            throw new ApiException(0, "network error calling " + request.uri(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted calling " + request.uri(), e);
        }
        return parseResponse(response, responseType);
    }

    /** True async send, via {@link HttpClient#sendAsync} - completes on {@link #executor()}, never a JDK-internal thread. */
    private <T> CompletableFuture<T> sendAsync(final HttpRequest request, final Class<T> responseType) {
        return this.sendAsync(request, (Type) responseType);
    }

    /** {@link Type}-based counterpart to {@link #send(HttpRequest, Class)}, for a generic response shape - see {@link #parseResponse(HttpResponse, Type)}. */
    private <T> T send(final HttpRequest request, final Type responseType) throws ApiException {
        final HttpResponse<InputStream> response;
        try {
            response = this.httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (final IOException e) {
            throw new ApiException(0, "network error calling " + request.uri(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted calling " + request.uri(), e);
        }
        return parseResponse(response, responseType);
    }

    /** {@link Type}-based counterpart to {@link #sendAsync(HttpRequest, Class)}, for a generic response shape - see {@link #parseResponse(HttpResponse, Type)}. */
    private <T> CompletableFuture<T> sendAsync(final HttpRequest request, final Type responseType) {
        return this.httpClient.sendAsync(request, BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    try {
                        return ApiClient.<T>parseResponse(response, responseType);
                    } catch (final ApiException e) {
                        // Matches this codebase's own *Async convention (see DataFactory/EventFactory/etc.
                        // in cloud-driver-plugin): a checked failure from the sync primitive is surfaced
                        // from the async form wrapped in a CompletionException.
                        throw new CompletionException(e);
                    }
                });
    }

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
