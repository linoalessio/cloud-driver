package de.lino.cloud.plugin.factory;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.StoredFileSummary;
import de.lino.cloud.api.file.exception.UploadQuotaExceededException;
import de.lino.cloud.api.icloud.IcloudAuthenticationException;
import de.lino.cloud.api.icloud.IcloudImportHandle;
import de.lino.cloud.api.icloud.IcloudImportService;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.InvalidPasswordFormatException;
import de.lino.cloud.api.jwt.InvalidRefreshTokenException;
import de.lino.cloud.api.jwt.InvalidVerificationCodeException;
import de.lino.cloud.api.jwt.auth.AuthTokens;
import de.lino.cloud.api.jwt.auth.LoginResult;
import de.lino.cloud.api.jwt.auth.TwoFactorSetupStart;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.push.LiveUpdatePublisher;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.rest.ApiKey;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.api.utility.CursorPage;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.database.database.entity.Serialized;
import de.lino.database.json.JsonDocument;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.*;
import io.javalin.util.JavalinLogger;
import io.javalin.websocket.WsCloseStatus;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.event.Level;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RestFactory} backed by <a href="https://javalin.io">Javalin</a>:
 * {@link #register}/{@link #fetch}/{@link #update}/{@link #delete} each wire
 * one HTTP verb for a {@code (path, type)} pair onto a {@link DataFactory},
 * tracked in its own {@link ConcurrentHashMap} registry keyed by path. A
 * path can carry any subset of the four operations. Routes are assembled
 * once {@link #start} is called.
 *
 * <p>Each route hands the actual {@link DataFactory} call to its {@code
 * *Async} counterpart via {@link Context#future}, so a Jetty worker thread
 * is never blocked on the underlying I/O.
 *
 * <p>Optionally gates every route behind a static API key, checked in a
 * Javalin {@code before} filter against the {@code X-API-Key} header - see
 * {@link ApiKey}. The single-argument constructor leaves every route open;
 * only use it for local development.
 *
 * <p>Alternatively, every route can instead be gated behind a per-user
 * {@code Authorization: Bearer <jwt>} header - see {@link
 * #DefaultRestFactory(DataFactory, AuthService)}. When constructed that way,
 * any registered entity type implementing {@link Owned} is additionally
 * scoped to the authenticated caller: {@link #register} stamps the caller's
 * user id onto the entity before persisting it, {@link #fetch} filters
 * {@code GET path} and 404s a {@code GET path/{id}} that belongs to someone
 * else, and {@link #update}/{@link #delete} 404 the same way rather than
 * letting one user mutate another's record.
 */
public final class DefaultRestFactory extends RestFactory implements LiveUpdatePublisher {

    /** HTTP request header {@link #requireValidApiKey} checks against {@link #apiKey}. */
    private static final String API_KEY_HEADER = "X-API-Key";
    /** Path mounted by {@link #start} for {@link #handleLogin}, exempted from {@link #requireValidBearerToken}. */
    private static final String LOGIN_PATH = "/auth/login";
    /** Path mounted by {@link #start} for {@link #handleRegister}, exempted from {@link #requireValidBearerToken}. */
    private static final String REGISTER_PATH = "/auth/register";
    /** Path mounted by {@link #start} for {@link #handleConfirmRegistration}, exempted from {@link #requireValidBearerToken}. */
    private static final String REGISTER_CONFIRM_PATH = "/auth/register/confirm";
    /** Path mounted by {@link #start} for {@link #handleRequestPasswordReset}, exempted from {@link #requireValidBearerToken}. */
    private static final String RESET_PASSWORD_PATH = "/auth/reset-password";
    /** Path mounted by {@link #start} for {@link #handleConfirmPasswordReset}, exempted from {@link #requireValidBearerToken}. */
    private static final String RESET_PASSWORD_CONFIRM_PATH = "/auth/reset-password/confirm";
    /** Path mounted by {@link #start} for {@link #handleRefresh}, exempted from {@link #requireValidBearerToken} - a refresh call has no valid access token yet by definition, that's the whole point. */
    private static final String REFRESH_PATH = "/auth/refresh";
    /** Path mounted by {@link #start} for {@link #handleLogout}, exempted from {@link #requireValidBearerToken} - possession of the refresh token itself is this route's own proof of authority, the same way it is for {@link #REFRESH_PATH}. */
    private static final String LOGOUT_PATH = "/auth/logout";
    /** Path mounted by {@link #start} for {@link #handleRequestEmailChange} - bearer-gated, unlike the paths above: this changes an already-authenticated account's own address. */
    private static final String CHANGE_EMAIL_PATH = "/auth/change-email";
    /** Path mounted by {@link #start} for {@link #handleConfirmEmailChange} - bearer-gated, same reasoning as {@link #CHANGE_EMAIL_PATH}. */
    private static final String CHANGE_EMAIL_CONFIRM_PATH = "/auth/change-email/confirm";
    /** Path mounted by {@link #start} for {@link #handleBeginTwoFactorSetup} (item 12, see {@code architecture/SERVICES.md}) - bearer-gated, acts on the already-authenticated caller's own account. */
    private static final String TWO_FACTOR_SETUP_PATH = "/auth/2fa/setup";
    /** Path mounted by {@link #start} for {@link #handleConfirmTwoFactorSetup} - bearer-gated, same reasoning as {@link #TWO_FACTOR_SETUP_PATH}. */
    private static final String TWO_FACTOR_CONFIRM_PATH = "/auth/2fa/confirm";
    /** Path mounted by {@link #start} for {@link #handleDisableTwoFactor} - bearer-gated, same reasoning as {@link #TWO_FACTOR_SETUP_PATH}. */
    private static final String TWO_FACTOR_DISABLE_PATH = "/auth/2fa/disable";
    /** Path mounted by {@link #start} for {@link #handleTwoFactorLogin}, exempted from {@link #requireValidBearerToken} - the caller has no real access token yet, that's the whole point of this route. */
    private static final String TWO_FACTOR_LOGIN_PATH = "/auth/2fa/login";
    /** Path mounted by {@link #start} for {@link #handleGetMe} - bearer-gated like every other {@code /auth/*} route not explicitly exempted, resolves the caller's own account from its own bearer token. */
    private static final String ME_PATH = "/auth/me";
    /** Path mounted by {@link #start} for {@link #handleUploadFile}/{@link #handleListFiles}/{@link #handleDownloadFile}/{@link #handleDownloadFileContent}/{@link #handleDeleteFile}/{@link #handleMoveFile}. */
    private static final String FILES_PATH = "/files";
    /**
     * Path mounted by {@link #start} for {@link #handleListDeletedFiles} - a static segment that
     * happens to be registered <em>before</em> {@link #FILES_PATH}{@code /{id}} in {@link #start}.
     * <b>Corrected (2026-09-02):</b> this used to claim Javalin's own routing matches a static
     * segment ahead of a {@code {param}} one "regardless of registration order" - false. Javalin
     * 7's {@code io.javalin.router.matcher.PathMatcher#findFirstEntry} does a plain linear scan
     * over registered routes of a given HTTP method, in registration order, and returns the first
     * whose path template matches - confirmed directly against its bytecode after this exact
     * mistake broke {@link #FILES_SHARED_WITH_ME_PATH} (registered after {@code /files/{id}} let
     * that param route silently swallow every request to it - see that constant's own Javadoc for
     * the full incident). This route only ever worked because it happens to be registered before
     * {@code /files/{id}} below - move it after that route's registration and it would break the
     * exact same way.
     */
    private static final String FILES_TRASH_PATH = FILES_PATH + "/trash";
    /** Path mounted by {@link #start} for {@link #handleCreateFolder}/{@link #handleListFolders}/{@link #handleUpdateFolder}/{@link #handleDeleteFolder}. */
    private static final String FOLDERS_PATH = "/folders";
    /** Path mounted by {@link #start} for {@link #handleListDeletedFolders} - registered before {@code PUT}/{@code DELETE /folders/{id}} the same way {@link #FILES_TRASH_PATH} is registered before {@code GET /files/{id}} - though since neither of those two is a {@code GET} route, there is no same-method collision risk here the way {@link #FILES_TRASH_PATH}/{@link #FILES_SHARED_WITH_ME_PATH} have with {@code GET /files/{id}}. */
    private static final String FOLDERS_TRASH_PATH = FOLDERS_PATH + "/trash";
    /**
     * Path mounted by {@link #start} for {@link #handleEmptyTrash} (added 2026-09-02, the "Empty
     * trash bin" action) - a standalone top-level resource rather than nested under {@link
     * #FILES_PATH}/{@link #FOLDERS_PATH}, deliberately: one call empties both files' and folders'
     * trash together via a single {@link CloudUserService#emptyTrash} call, so there is no natural
     * single owner between the two existing resources to nest it under, and a standalone path
     * sidesteps the whole registration-order-vs-{@code {id}} pitfall {@link #FILES_TRASH_PATH}'s
     * own Javadoc documents entirely, rather than needing to reason about it again here.
     */
    private static final String TRASH_EMPTY_PATH = "/trash/empty";
    /**
     * Path mounted by {@link #start} for {@link #handleListFilesSharedWithMe} (item 9, file/folder
     * sharing). <b>Must be registered before {@code GET /files/{id}}</b> - see {@link
     * #FILES_TRASH_PATH}'s own Javadoc for why registration order (not any Javalin routing
     * precedence) is what decides this. <b>Fixed a real, confirmed bug (2026-09-02):</b> this route
     * used to be registered <em>after</em> {@code GET /files/{id}} (added alongside {@code POST
     * /files/{id}/share} further down the route list, with no thought given to its own position
     * relative to the earlier {@code /files/{id}} registration), so every {@code GET
     * /files/shared-with-me} request was silently captured by {@link #handleDownloadFile} treating
     * {@code "shared-with-me"} as a file id, 404ing with {@code "No StoredFile with id
     * shared-with-me"} instead of ever reaching {@link #handleListFilesSharedWithMe}. This broke
     * the read side of sharing for every recipient on every deployment since item 9 first shipped -
     * the write side ({@code POST /files/{id}/share}, a differently-shaped 3-segment path with no
     * collision) worked the whole time, which is what made this bug so easy to miss: a share looked
     * successful (confirmable via {@code GET /files/{id}/share} listing the grant back), but the
     * recipient could never actually see it. Confirmed against the live deployment (zero rows in
     * neither table moved the needle - the grant itself persisted correctly; only the listing broke)
     * before being traced to this exact registration-order mistake.
     */
    private static final String FILES_SHARED_WITH_ME_PATH = FILES_PATH + "/shared-with-me";
    /**
     * Path mounted by {@link #start} for {@link #handleCountFilesSharedByMe} (added 2026-09-03,
     * backing the desktop app's Dashboard "Shared files" stat card - which used to display {@code
     * GET /files/shared-with-me}'s own count, the wrong direction: files shared <em>with</em> the
     * caller, not files the caller has shared <em>with others</em>). A 3-segment path ({@code
     * /files/shared-by-me/count}) with no registration-order collision risk against {@code /files/
     * {id}/content|folder|share|restore} - those only match when their own literal third segment
     * is present, and {@code "count"} isn't any of them - but registered alongside {@link
     * #FILES_SHARED_WITH_ME_PATH} (before {@code GET /files/{id}}) anyway, for the same "static
     * routes first" consistency that constant's own Javadoc documents, not because this one
     * actually needs it.
     */
    private static final String FILES_SHARED_BY_ME_COUNT_PATH = FILES_PATH + "/shared-by-me/count";
    /**
     * Path mounted by {@link #start} for {@link #handleListFoldersSharedWithMe}. Unlike {@link
     * #FILES_SHARED_WITH_ME_PATH}, this one was never actually broken by the registration-order bug
     * described there - there is no {@code GET /folders/{id}} route at all (folder-by-id is only
     * reachable via {@code PUT}/{@code DELETE}), so no {@code GET} route exists that could ever
     * shadow this one regardless of where it's registered. Left in its original position rather
     * than moved for cosmetic consistency with the fix above.
     */
    private static final String FOLDERS_SHARED_WITH_ME_PATH = FOLDERS_PATH + "/shared-with-me";
    /** Path mounted by {@link #start} for {@link #handleStartIcloudImport}. */
    private static final String ICLOUD_IMPORT_PATH = "/icloud/import";
    /**
     * Path mounted by {@link #start} for {@link #handleCheckCloudUserExists} (added 2026-09-02,
     * backing the desktop app's live grantee-email check in its Share dialog) - a static segment on
     * the {@code /cloudUsers} resource. Registered explicitly inside {@link #start}'s {@code
     * cloudUserService != null} block, which runs before the generic {@code GET /cloudUsers/{id}}
     * route gets registered via the {@code fetchResources.forEach(...)} loop at the very end of
     * {@link #start} - that ordering is load-bearing, not cosmetic; see {@link #FILES_TRASH_PATH}'s
     * own Javadoc for why (Javalin has no built-in "prefer a static segment over a path param"
     * precedence - it's a first-match-in-registration-order linear scan).
     */
    private static final String CLOUD_USER_EXISTS_PATH = "/cloudUsers/exists";
    /** Query parameter {@link #handleCheckCloudUserExists} reads the email address to check from. */
    private static final String EMAIL_QUERY_PARAM = "email";
    /**
     * Path prefix every {@code /auth/*} route falls under - checked by {@link
     * #requireWithinAuthRateLimit}, which applies to all nine {@code /auth/*} routes alike
     * (the seven exempted from {@link #requireValidBearerToken} <em>and</em> the two
     * bearer-gated change-email ones), since a leaked/stolen bearer token could otherwise
     * still be used to spam email-change requests with no limit at all.
     */
    private static final String AUTH_PATH_PREFIX = "/auth/";
    /** {@code configuration.json} key {@link #resolveAuthRateLimitMaxRequests} reads the per-window request cap from. */
    private static final String AUTH_RATE_LIMIT_MAX_REQUESTS_CONFIG_KEY = "auth-rate-limit-max-requests";
    /** {@code configuration.json} key {@link #resolveAuthRateLimitWindowSeconds} reads the window length (seconds) from. */
    private static final String AUTH_RATE_LIMIT_WINDOW_SECONDS_CONFIG_KEY = "auth-rate-limit-window-seconds";
    /**
     * Default {@code /auth/*} per-IP request cap per {@link #DEFAULT_AUTH_RATE_LIMIT_WINDOW_SECONDS}-second
     * window, used when {@link #AUTH_RATE_LIMIT_MAX_REQUESTS_CONFIG_KEY} isn't set. Reasoning: a
     * genuine user fat-fingering a password or a verification code a handful of times should never
     * be rate-limited (login/register/reset each only need a couple of attempts in normal use), but
     * an automated credential-stuffing/enumeration attempt needs hundreds-to-thousands of attempts
     * to be worth running at all - 10 requests per 5 minutes per IP sits comfortably above the
     * former and far below the latter, without needing an account-level lockout (which is itself an
     * abuse vector - locking a victim out by deliberately failing their login).
     */
    private static final int DEFAULT_AUTH_RATE_LIMIT_MAX_REQUESTS = 10;
    /** Default {@code /auth/*} rate-limit window, in seconds - see {@link #DEFAULT_AUTH_RATE_LIMIT_MAX_REQUESTS}'s reasoning. */
    private static final long DEFAULT_AUTH_RATE_LIMIT_WINDOW_SECONDS = 300L;
    /** Path prefix every admin-only route is mounted under - checked by {@link #requireAdmin}. */
    private static final String ADMIN_PATH_PREFIX = "/admin/";
    /** Path mounted by {@link #start} for {@link #handleListAuthUsers}/{@link #handleGetAuthUser}. */
    private static final String ADMIN_AUTH_USERS_PATH = "/admin/authUsers";
    /** Path mounted by {@link #start} for {@link #handleListAuditLog} - admin-gated, backs the desktop app's read-only Admin panel. */
    private static final String ADMIN_AUDIT_LOG_PATH = "/admin/audit-log";
    /** Path mounted by {@link #start} for {@link #handleGetAdminMetrics} - admin-gated, backs the desktop app's Admin panel metrics section. */
    private static final String ADMIN_METRICS_PATH = "/admin/metrics";
    /** Query parameter {@link #handleListAuditLog} reads to switch from the default recent-20 listing to every entry - see that method's own Javadoc. */
    private static final String AUDIT_LOG_ALL_QUERY_PARAM = "all";
    /** Query parameter {@link #handleListAuditLog} reads to filter the listing to one account's actions, by email. */
    private static final String AUDIT_LOG_EMAIL_QUERY_PARAM = "email";
    /** How many entries {@link #handleListAuditLog} returns by default (no {@link #AUDIT_LOG_ALL_QUERY_PARAM}), newest first - mirrors {@code AuditLogCommand}'s own terminal default. */
    private static final int DEFAULT_AUDIT_LOG_LIMIT = 20;
    /**
     * Path mounted by {@link #start} for the item-10 (live push via WebSocket, see {@code
     * architecture/SERVICES.md}) WebSocket route, configured by {@link #configureLiveUpdatesWebSocket}.
     * Only mounted when {@link #authService} is set - the same bearer-token identity the HTTP
     * routes use also gates this connection (see that method's own Javadoc for how, since a
     * WebSocket handshake can't carry a custom {@code Authorization} header the way a normal HTTP
     * client request can).
     */
    private static final String LIVE_UPDATES_PATH = "/ws/updates";
    /** HTTP request header carrying the bearer token, checked by {@link #resolveBearerToken}. */
    private static final String AUTHORIZATION_HEADER = "Authorization";
    /** Prefix a valid {@link #AUTHORIZATION_HEADER} value must start with, stripped by {@link #resolveBearerToken}. */
    private static final String BEARER_PREFIX = "Bearer ";
    /** Query parameter {@link #resolveBearerToken} falls back to when no {@link #AUTHORIZATION_HEADER} is present. */
    private static final String TOKEN_QUERY_PARAM = "token";
    /** Javalin request attribute key {@link #requireValidBearerToken} stores the validated user id under. */
    private static final String USER_ID_ATTRIBUTE = "userId";
    /** JSON field name {@link #parseOwnedBody} overwrites with the authenticated caller's user id. */
    private static final String OWNER_ID_FIELD = "ownerId";
    /** Query parameter {@link #handleUploadFile} reads the uploaded file's name from. */
    private static final String FILE_NAME_QUERY_PARAM = "fileName";
    /**
     * Query parameter {@link #handleUploadFile}/{@link #handleListFiles} read a target/filter
     * folder id from, and the JSON field name each entry of {@link #handleListFiles}'s response
     * array carries that folder id under (merged in via  - {@link StoredFile}
     * itself has no such field, since placement lives on {@code StoredFileOwnership} instead).
     */
    private static final String FOLDER_ID_FIELD = "folderId";
    /** Query parameter {@link #handleListFolders} reads the parent folder to list children of from. */
    private static final String PARENT_FOLDER_ID_QUERY_PARAM = "parentFolderId";
    /**
     * Value of {@link #FOLDER_ID_FIELD}/{@link #PARENT_FOLDER_ID_QUERY_PARAM} that explicitly means
     * "the root", used by {@link #resolveFolderIdOrRoot} - a query string cannot carry a literal
     * {@code null}, and for {@link #handleUploadFile}/{@link #handleMoveFile}/{@link
     * #handleListFolders} the parameter being <em>omitted</em> already means the same thing (place
     * at/list the root), so this sentinel only matters for {@link #handleListFiles}, where an
     * omitted {@link #FOLDER_ID_FIELD} instead means "every file, unscoped" (see that method's
     * Javadoc) and this sentinel is the only way to explicitly ask for just the root's files.
     */
    private static final String ROOT_FOLDER_SENTINEL = "root";
    /**
     * Query parameter {@link #handleListFiles}/{@link #handleListFolders} read a page size from -
     * its mere <em>presence</em> is what opts a request into the paginated, envelope-wrapped
     * response shape ({@link #PAGE_ITEMS_FIELD}/{@link #PAGE_NEXT_CURSOR_FIELD}) instead of the
     * original bare-JSON-array response; omitted, both routes keep returning every matching entry
     * as a bare array exactly as before this feature existed, so no existing caller of either
     * route breaks.
     */
    private static final String LIMIT_QUERY_PARAM = "limit";
    /** Query parameter {@link #handleListFiles}/{@link #handleListFolders} read the previous page's {@link CursorPage#nextCursor()} from - absent/blank means "first page". Ignored unless {@link #LIMIT_QUERY_PARAM} is also present. */
    private static final String CURSOR_QUERY_PARAM = "cursor";
    /** JSON field name the paginated response envelope carries its page of entries under. */
    private static final String PAGE_ITEMS_FIELD = "items";
    /** JSON field name the paginated response envelope carries {@link CursorPage#nextCursor()} under - a JSON {@code null} once there is no next page. */
    private static final String PAGE_NEXT_CURSOR_FIELD = "nextCursor";

    /**
     * The upload size ceiling, enforced two ways: it overrides Javalin's own {@link
     * JavalinConfig#http}{@code .maxRequestSize} default of 1,000,000 bytes (1 MB, Jetty's own
     * backstop check), and it's the running-total cap {@link #receiveUploadToScratchFile} checks
     * incrementally while streaming a {@code POST /files} body to disk, rejecting early rather
     * than only after the whole body has been received.
     *
     * <p><b>Stale reasoning, corrected:</b> this constant's value (256 MB) was originally sized
     * around base64-encoded JSON bodies read whole via {@link Context#body()}/{@link
     * Context#bodyAsBytes()} (~1.37x the raw file size) - the client has sent a raw {@code
     * application/octet-stream} body instead for some time, and as of Phase 3 of {@code
     * architecture/OPTIMIZE_UPLOAD.md}, {@link #handleUploadFile} streams that body straight to a
     * scratch file rather than buffering it in heap at all. The constraint that originally sized
     * this number - JVM heap - no longer applies to the receive step; what this number should be
     * today is a deliberate disk/quota decision (available scratch-disk space, how large a single
     * file this deployment ever expects to serve), not a leftover memory-safety number. Left at
     * 256 MB pending that decision - raise it (or wire it to {@code configuration.json}, the same
     * way {@code CloudUser#getMaxBytesToUpload()}'s own per-account quota is configured) once a
     * deliberate ceiling is chosen.
     */
    private static final long MAX_REQUEST_SIZE_BYTES = 26_8435_456;

    /**
     * Chunk size {@link #receiveUploadToScratchFile} reads/writes at a time while streaming an
     * upload to disk - the only per-upload heap cost that scales with buffer size, not file size,
     * regardless of how large the upload is. Matches {@code StoredFile}'s own DEFLATE/inflate
     * buffer size, for the same "a plain, unremarkable streaming chunk size" reasoning.
     */
    private static final int UPLOAD_STREAM_BUFFER_SIZE = 8192;

    /** The {@link DataFactory} every registered {@code (path, type)} resource is backed by. */
    private final DataFactory dataFactory;
    /** Checked by {@link #requireValidApiKey}, or {@code null} if this instance isn't API-key-gated. */
    private final ApiKey apiKey;
    /** Shared Gson instance used to (de)serialize request/response bodies. */
    private final Gson gson = new Gson();

    /** Verifies login/registration and issued JWTs, or {@code null} if this instance isn't JWT-gated. */
    @Getter
    private final AuthService authService;

    /** Backs the {@code /files} routes, or {@code null} if they aren't mounted. */
    @Getter
    private final CloudUserService cloudUserService;

    /** Backs the {@code /icloud/import} routes, or {@code null} if they aren't mounted (e.g. {@code python3}/{@code pyicloud} isn't available on this host). */
    @Getter
    private final IcloudImportService icloudImportService;

    /** Paths with a {@code POST} handler registered via {@link #register}. */
    private final Map<String, Class<? extends Serialized>> registerResources = Maps.newHashMap();
    /** Paths with a {@code GET} handler registered via {@link #fetch}. */
    private final Map<String, Class<? extends Serialized>> fetchResources = Maps.newHashMap();
    /** Paths with a {@code PUT} handler registered via {@link #update}. */
    private final Map<String, Class<? extends Serialized>> updateResources = Maps.newHashMap();
    /** Paths with a {@code DELETE} handler registered via {@link #delete}. */
    private final Map<String, Class<? extends Serialized>> deleteResources = Maps.newHashMap();

    /** The running Javalin desktop, or {@code null} before {@link #start} / after {@link #stop}. */
    private volatile Javalin app;

    /**
     * Per-client-IP fixed-window request counters backing {@link #requireWithinAuthRateLimit} -
     * a plain in-process {@link ConcurrentHashMap}, matching this codebase's existing "not for
     * massive scale, sufficient for a single-process deployment" trade-off elsewhere ({@code
     * InMemoryPendingUploadCache}, the desktop app's {@code ThumbnailCache}). No eviction: a long
     * enough uptime accumulates one entry per distinct IP that has ever hit {@code /auth/*} - each
     * entry is a handful of bytes, so this isn't a practical memory concern at this deployment's
     * scale, but a future multi-tenant/high-traffic deployment would want to evict stale entries.
     */
    private final ConcurrentHashMap<String, AuthRateLimitBucket> authRateLimitBuckets = new ConcurrentHashMap<>();

    /**
     * Every currently-connected {@link #LIVE_UPDATES_PATH} session, grouped by the {@code
     * authUserId} it authenticated as (see {@link #configureLiveUpdatesWebSocket}) - an account
     * can have more than one connected client (e.g. the desktop app open on two machines), so
     * this is a set per key, not a single session. Read by {@link #publish} to know which
     * sessions, if any, to forward a {@link de.lino.cloud.api.event.database.DatabaseWatchEvent}
     * notification to; an entry is removed entirely once its last session disconnects, so a
     * long-running server never accumulates empty sets for accounts that have since gone offline.
     */
    private final ConcurrentHashMap<String, Set<WsContext>> liveUpdateSessions = new ConcurrentHashMap<>();

    /**
     * One client IP's current fixed-window {@code /auth/*} request count. {@code windowStartEpochMillis}
     * and {@code count} are only ever read/mutated while synchronized on the instance (see {@link
     * #requireWithinAuthRateLimit}) - a fixed window (not a sliding one/token bucket) was chosen
     * deliberately for simplicity, at the cost of allowing a burst of up to {@code 2x} the
     * configured limit right at a window boundary; acceptable for this use case (slowing down
     * brute-forcing/spam, not a hard security perimeter).
     */
    private static final class AuthRateLimitBucket {
        private long windowStartEpochMillis = System.currentTimeMillis();
        private int count;
    }

    /**
     * Every route is left open - no API-key check at all. Only appropriate
     * for local development; use {@link #DefaultRestFactory(DataFactory, ApiKey)}
     * or {@link #DefaultRestFactory(DataFactory, AuthService)} for anything
     * that leaves {@code localhost}.
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory) {
        this(dataFactory, (ApiKey) null);
    }

    /**
     * Every route requires a valid {@code X-API-Key} header, checked against
     * {@code apiKey}. Use this for server-to-server access; for end-user
     * clients (iOS/rest/macOS) authenticating with a username/password, use
     * {@link #DefaultRestFactory(DataFactory, AuthService)} instead - the two
     * mechanisms are mutually exclusive on one instance.
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     * @param apiKey checks the {@code X-API-Key} header on every request, or {@code null} to leave every route open
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory, @Nullable final ApiKey apiKey) {
        this.dataFactory = dataFactory;
        this.apiKey = apiKey;
        this.authService = null;
        this.cloudUserService = null;
        this.icloudImportService = null;
    }

    /**
     * Every route requires a valid {@code Authorization: Bearer <jwt>}
     * header, checked via {@code authService}, except {@code POST /auth/login}/{@code POST
     * /auth/register}/{@code POST /auth/register/confirm}/{@code POST /auth/reset-password}/
     * {@code POST /auth/reset-password/confirm} themselves - all five mounted automatically by
     * this constructor. {@code /auth/login} issues the JWT this filter checks for in the first
     * place; {@code /auth/register}/{@code /auth/register/confirm} together are this
     * deployment's chosen, open, e-mail-verified self-registration flow (see {@link
     * de.lino.cloud.api.jwt.auth.IAuthService}'s own Javadoc) - {@code /auth/register} only
     * starts it (via {@link AuthService#register}, which e-mails a verification code rather
     * than creating the account outright), and {@code /auth/register/confirm} (via {@link
     * AuthService#confirmRegistration}) is what actually creates the account and returns a JWT
     * the same shape {@code /auth/login} does, once the caller supplies that code back.
     * {@code /auth/reset-password}/{@code /auth/reset-password/confirm} mirror that same
     * two-step, e-mail-verified shape for recovering a forgotten password (via {@link
     * AuthService#requestPasswordReset}/{@link AuthService#confirmPasswordReset}) rather than
     * creating a new account. Use this constructor (instead of the {@link ApiKey} one) when the
     * clients calling this API are end users authenticating with a username/password, not
     * another service holding a static key. Any registered entity type implementing {@link
     * Owned} is additionally scoped to the authenticated caller - see this class's own Javadoc.
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     * @param authService verifies login and issued JWTs, and backs {@code /auth/register}; must not be {@code null}
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory, @NonNull final AuthService authService) {
        this(dataFactory, authService, null);
    }

    /**
     * Same as {@link #DefaultRestFactory(DataFactory, AuthService)}, additionally mounting
     * {@code POST /files}/{@code GET /files}/{@code GET /files/{id}}/{@code GET
     * /files/{id}/content}/{@code DELETE /files/{id}}/{@code PUT /files/{id}/folder} and {@code
     * POST /folders}/{@code GET /folders}/{@code PUT /folders/{id}}/{@code DELETE /folders/{id}} -
     * each user's own {@link StoredFile} uploads
     * and {@link Folder} organization, backed by {@code cloudUserService}. Unlike {@link
     * #register}/{@link #fetch}/{@link #update}/{@link #delete}, these routes are not generic
     * over a {@code (path, type)} pair registered separately - they're fixed, mounted directly
     * by this constructor, since uploading/listing/deleting a user's own files and folders is
     * business logic ({@link CloudUserService} - move/rename validate ownership and, for
     * folders, guard against cycles and non-empty deletes), not a plain {@code DataFactory}
     * CRUD pass-through the way every other registered resource is.
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     * @param authService verifies login and issued JWTs; must not be {@code null}
     * @param cloudUserService backs the {@code /files} routes, or {@code null} to leave them unmounted
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory, @NonNull final AuthService authService,
                               @Nullable final CloudUserService cloudUserService) {
        this(dataFactory, authService, cloudUserService, null);
    }

    /**
     * Same as {@link #DefaultRestFactory(DataFactory, AuthService, CloudUserService)}, additionally
     * mounting {@code POST /icloud/import}/{@code POST /icloud/import/{jobId}/confirm}/{@code GET
     * /icloud/import/{jobId}/status} - the on-demand "Sync from iCloud" import (see {@link
     * IcloudImportService}'s own Javadoc for why this is a one-shot import, not a persistent sync).
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     * @param authService verifies login and issued JWTs; must not be {@code null}
     * @param cloudUserService backs the {@code /files} routes, or {@code null} to leave them unmounted
     * @param icloudImportService backs the {@code /icloud/import} routes, or {@code null} to leave them unmounted (e.g. no {@code python3}/{@code pyicloud} on this host - see {@code PythonIcloudBridge})
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory, @NonNull final AuthService authService,
                               @Nullable final CloudUserService cloudUserService, @Nullable final IcloudImportService icloudImportService) {
        this.dataFactory = dataFactory;
        this.apiKey = null;
        this.authService = Objects.requireNonNull(authService, "@DefaultRestFactory.init: authService cannot be null");
        this.cloudUserService = cloudUserService;
        this.icloudImportService = icloudImportService;
    }

    /** Registers a {@code POST} handler for {@code path}, via {@link #registerOperation}. */
    @Override
    public <T extends Serialized> void register(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.registerResources, path, type, "register");
    }

    /** Registers a {@code GET} handler for {@code path}, via {@link #registerOperation}. */
    @Override
    public <T extends Serialized> void fetch(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.fetchResources, path, type, "fetch");
    }

    /** Registers a {@code PUT} handler for {@code path}, via {@link #registerOperation}. */
    @Override
    public <T extends Serialized> void update(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.updateResources, path, type, "update");
    }

    /** Registers a {@code DELETE} handler for {@code path}, via {@link #registerOperation}. */
    @Override
    public <T extends Serialized> void delete(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.deleteResources, path, type, "delete");
    }

    /**
     * Shared registration primitive backing {@link #register}/{@link #fetch}/{@link
     * #update}/{@link #delete}; {@link Map#putIfAbsent} makes the duplicate-path
     * check atomic.
     *
     * @param operationResources the registry ({@link #registerResources}/{@link #fetchResources}/{@link
     * #updateResources}/{@link #deleteResources}) to record {@code path} in
     * @param path the route path being registered
     * @param type the entity type {@code path} is registered for
     * @param operationName the calling operation's name, used in error messages
     * @throws IllegalStateException if called after {@link #start}, or if {@code path} already has a handler for this operation
     */
    private <T extends Serialized> void registerOperation(final Map<String, Class<? extends Serialized>> operationResources,
                                                            final String path, final Class<T> type, final String operationName) {
        if (this.app != null) {
            throw new IllegalStateException("@DefaultRestFactory." + operationName + ": cannot register '" + path + "' after start()");
        }
        if (operationResources.putIfAbsent(path, type) != null) {
            throw new IllegalStateException(
                    "@DefaultRestFactory." + operationName + ": '" + path + "' already has a " + operationName + " handler registered");
        }
    }

    /** Checks all four operation registries for {@code path}. */
    @Override
    @NotNull
    public Optional<Class<? extends Serialized>> findByPath(@NonNull final String path) {
        for (final Map<String, Class<? extends Serialized>> operationResources
                : List.of(this.registerResources, this.fetchResources, this.updateResources, this.deleteResources)) {
            final Class<? extends Serialized> type = operationResources.get(path);
            if (type != null) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Union of every path registered under any of the four operations. */
    @Override
    @NotNull
    public Collection<String> getRegisteredPaths() {
        final Set<String> paths = new LinkedHashSet<>();
        paths.addAll(this.registerResources.keySet());
        paths.addAll(this.fetchResources.keySet());
        paths.addAll(this.updateResources.keySet());
        paths.addAll(this.deleteResources.keySet());
        return Collections.unmodifiableSet(paths);
    }

    /**
     * Builds the Javalin desktop from every route registered so far and starts listening on
     * {@code host}:{@code port} - see {@link RestFactory#start(String, int)}'s own Javadoc for
     * why {@code host} matters (plain HTTP, no TLS). Silences Javalin's/Jetty's own logging
     * entirely before doing so - see {@link #silenceJavalinLogging}.
     */
    @Override
    public void start(@NonNull final String host, final int port) {
        if (this.app != null) {
            throw new IllegalStateException("@DefaultRestFactory.start: already started");
        }

        silenceJavalinLogging();

        this.app = Javalin.create(config -> {

            config.http.maxRequestSize = MAX_REQUEST_SIZE_BYTES;

            if (this.apiKey != null) {
                config.routes.before(this::requireValidApiKey);
            }

            if (this.authService != null) {
                config.routes.before(this::requireWithinAuthRateLimit);
                config.routes.post(LOGIN_PATH, this::handleLogin);
                config.routes.post(REGISTER_PATH, this::handleRegister);
                config.routes.post(REGISTER_CONFIRM_PATH, this::handleConfirmRegistration);
                config.routes.post(RESET_PASSWORD_PATH, this::handleRequestPasswordReset);
                config.routes.post(RESET_PASSWORD_CONFIRM_PATH, this::handleConfirmPasswordReset);
                config.routes.post(CHANGE_EMAIL_PATH, this::handleRequestEmailChange);
                config.routes.post(CHANGE_EMAIL_CONFIRM_PATH, this::handleConfirmEmailChange);
                config.routes.post(REFRESH_PATH, this::handleRefresh);
                config.routes.post(LOGOUT_PATH, this::handleLogout);
                config.routes.post(TWO_FACTOR_LOGIN_PATH, this::handleTwoFactorLogin);
                config.routes.post(TWO_FACTOR_SETUP_PATH, this::handleBeginTwoFactorSetup);
                config.routes.post(TWO_FACTOR_CONFIRM_PATH, this::handleConfirmTwoFactorSetup);
                config.routes.post(TWO_FACTOR_DISABLE_PATH, this::handleDisableTwoFactor);
                config.routes.get(ME_PATH, this::handleGetMe);
                config.routes.before(this::requireValidBearerToken);

                config.routes.get(ADMIN_AUTH_USERS_PATH, this::handleListAuthUsers);
                config.routes.get(ADMIN_AUTH_USERS_PATH + "/{id}", this::handleGetAuthUser);
                config.routes.get(ADMIN_AUDIT_LOG_PATH, this::handleListAuditLog);
                config.routes.get(ADMIN_METRICS_PATH, this::handleGetAdminMetrics);
                config.routes.before(this::requireAdmin);

                config.routes.ws(LIVE_UPDATES_PATH, this::configureLiveUpdatesWebSocket);
            }

            if (this.cloudUserService != null) {
                config.routes.get(CLOUD_USER_EXISTS_PATH, this::handleCheckCloudUserExists);
                config.routes.post(TRASH_EMPTY_PATH, this::handleEmptyTrash);
                config.routes.post(FILES_PATH, this::handleUploadFile);
                config.routes.get(FILES_PATH, this::handleListFiles);
                config.routes.get(FILES_TRASH_PATH, this::handleListDeletedFiles);
                // FILES_SHARED_WITH_ME_PATH must be registered before "/files/{id}" below - see
                // that constant's own Javadoc: Javalin's PathMatcher (io.javalin.router.matcher,
                // confirmed via its findFirstEntry/match methods) does a plain linear scan over
                // registered GET routes IN REGISTRATION ORDER and returns the first one whose
                // path template matches, with NO "prefer a static segment over a path param"
                // precedence of its own - unlike what an earlier revision of this file's own
                // Javadoc (and CLAUDE.md) claimed. Registered after "/files/{id}" (a real,
                // confirmed bug, fixed 2026-09-02), every GET /files/shared-with-me request was
                // silently swallowed by handleDownloadFile treating "shared-with-me" as a file id -
                // 404ing with "No StoredFile with id shared-with-me" - so a share's *write* side
                // worked (confirmed via GET /files/{id}/share showing the grant) while its *read*
                // side for the recipient never worked at all, on any deployment, since item 9 first
                // shipped. FILES_TRASH_PATH above only ever worked by the same registration-order
                // coincidence, not because of any framework guarantee.
                config.routes.get(FILES_SHARED_WITH_ME_PATH, this::handleListFilesSharedWithMe);
                config.routes.get(FILES_SHARED_BY_ME_COUNT_PATH, this::handleCountFilesSharedByMe);
                config.routes.get(FILES_PATH + "/{id}", this::handleDownloadFile);
                config.routes.get(FILES_PATH + "/{id}/content", this::handleDownloadFileContent);
                config.routes.delete(FILES_PATH + "/{id}", this::handleDeleteFile);
                config.routes.post(FILES_PATH + "/{id}/restore", this::handleRestoreFile);
                config.routes.put(FILES_PATH + "/{id}/folder", this::handleMoveFile);
                config.routes.post(FILES_PATH + "/{id}/share", this::handleShareFile);
                config.routes.get(FILES_PATH + "/{id}/share", this::handleListFileShares);
                config.routes.delete(FILES_PATH + "/{id}/share/{email}", this::handleRevokeFileShare);

                config.routes.post(FOLDERS_PATH, this::handleCreateFolder);
                config.routes.get(FOLDERS_PATH, this::handleListFolders);
                config.routes.get(FOLDERS_TRASH_PATH, this::handleListDeletedFolders);
                config.routes.put(FOLDERS_PATH + "/{id}", this::handleUpdateFolder);
                config.routes.delete(FOLDERS_PATH + "/{id}", this::handleDeleteFolder);
                config.routes.post(FOLDERS_PATH + "/{id}/restore", this::handleRestoreFolder);
                config.routes.get(FOLDERS_SHARED_WITH_ME_PATH, this::handleListFoldersSharedWithMe);
                config.routes.post(FOLDERS_PATH + "/{id}/share", this::handleShareFolder);
                config.routes.get(FOLDERS_PATH + "/{id}/share", this::handleListFolderShares);
                config.routes.delete(FOLDERS_PATH + "/{id}/share/{email}", this::handleRevokeFolderShare);
                config.routes.get(FOLDERS_PATH + "/{id}/shared-contents", this::handleListSharedFolderContents);
            }

            if (this.icloudImportService != null) {
                config.routes.post(ICLOUD_IMPORT_PATH, this::handleStartIcloudImport);
                config.routes.post(ICLOUD_IMPORT_PATH + "/{jobId}/confirm", this::handleConfirmIcloudImportTwoFactor);
                config.routes.get(ICLOUD_IMPORT_PATH + "/{jobId}/status", this::handleGetIcloudImportStatus);
            }

            this.registerResources.forEach((path, type) -> this.bindRegister(config, path, type));
            this.fetchResources.forEach((path, type) -> this.bindFetch(config, path, type));
            this.updateResources.forEach((path, type) -> this.bindUpdate(config, path, type));
            this.deleteResources.forEach((path, type) -> this.bindDelete(config, path, type));
        });

        this.app.start(host, port);
    }

    /** Stops the running Javalin desktop, if any. Idempotent. */
    @Override
    public void stop() {
        if (this.app != null) {
            this.app.stop();
            this.app = null;
        }
        this.liveUpdateSessions.clear();
    }

    /**
     * Silences every log line Javalin/Jetty would otherwise print, on two independent
     * channels:
     * <ul>
     *     <li>{@link JavalinLogger#enabled} - Javalin's own internal logger (startup banner,
     *     the "Javalin started" line, and anything else it logs directly), set {@code false}
     *     wholesale rather than {@link JavalinLogger#startupInfo} alone (which only covers
     *     the startup line). A plain flag Javalin checks on every log call, so setting it
     *     here - right before {@link #start} builds the desktop - always takes effect.</li>
     *     <li>Jetty's own server/connector logging, which goes through SLF4J directly, not
     *     through {@code JavalinLogger}. This module's {@code slf4j-simple} binding is
     *     already silenced wholesale via {@code src/main/resources/simplelogger.properties}
     *     ({@code org.slf4j.simpleLogger.defaultLogLevel=off}) - a classpath resource, not
     *     done here via {@code System.setProperty}, since {@code slf4j-simple} reads its
     *     configuration exactly once, the first time any SLF4J logger anywhere in the JVM is
     *     created; by the time this method runs (after the database connection, extensions,
     *     etc. have already had a chance to touch SLF4J), a property set here could easily be
     *     too late. The call below is kept anyway as a harmless best-effort fallback for a
     *     consumer that repackages this module without carrying that resource file along.</li>
     * </ul>
     */
    private static void silenceJavalinLogging() {
        JavalinLogger.enabled = false;
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
    }

    /**
     * Javalin {@code before} filter checking the {@code X-API-Key} header against {@link #apiKey}.
     *
     * @throws UnauthorizedResponse if the header is missing or invalid
     */
    private void requireValidApiKey(@NotNull final Context ctx) {
        final String providedKey = ctx.header(API_KEY_HEADER);
        if (providedKey == null || providedKey.isBlank()) {
            throw new UnauthorizedResponse("Missing " + API_KEY_HEADER + " header");
        }
        if (!this.apiKey.isValid(providedKey)) {
            throw new UnauthorizedResponse("Invalid " + API_KEY_HEADER);
        }
    }

    /**
     * Gates every route behind a valid JWT, except {@link #LOGIN_PATH}/{@link
     * #REGISTER_PATH}/{@link #REGISTER_CONFIRM_PATH}/{@link #RESET_PASSWORD_PATH}/{@link
     * #RESET_PASSWORD_CONFIRM_PATH}/{@link #REFRESH_PATH}/{@link #LOGOUT_PATH} themselves - {@code
     * LOGIN_PATH} is how a client obtains the JWT this filter checks for in the first place,
     * {@code REGISTER_PATH}/{@code REGISTER_CONFIRM_PATH} together are how a client obtains an
     * account before it has any JWT at all, {@code RESET_PASSWORD_PATH}/{@code
     * RESET_PASSWORD_CONFIRM_PATH} together are how a client recovers access to an account whose
     * password it no longer has (so, by definition, no valid JWT either), and {@code
     * REFRESH_PATH}/{@code LOGOUT_PATH} both carry their own authority in the request body (a
     * refresh token) rather than a bearer access token - a refresh call's entire purpose is
     * obtaining a fresh access token once the old one has already expired, and a logout call must
     * still work with an already-expired access token, so neither can require one - all seven
     * must stay reachable without one. The token itself is resolved by {@link
     * #resolveBearerToken} (header, preferred, or a query parameter fallback). Stores the
     * validated user id as a request attribute ({@link #USER_ID_ATTRIBUTE}) for the {@link
     * Owned}-scoping checks in {@link #bindRegister}/{@link #bindFetch}/
     * {@link #bindUpdate}/{@link #bindDelete} to read.
     *
     * @throws UnauthorizedResponse if no token is present, or it is malformed/invalid/expired
     */
    private void requireValidBearerToken(@NotNull final Context ctx) {
        if (LOGIN_PATH.equals(ctx.path()) || REGISTER_PATH.equals(ctx.path()) || REGISTER_CONFIRM_PATH.equals(ctx.path())
                || RESET_PASSWORD_PATH.equals(ctx.path()) || RESET_PASSWORD_CONFIRM_PATH.equals(ctx.path())
                || REFRESH_PATH.equals(ctx.path()) || LOGOUT_PATH.equals(ctx.path())
                || TWO_FACTOR_LOGIN_PATH.equals(ctx.path())) {
            return;
        }
        final String token = resolveBearerToken(ctx);
        if (token == null) {
            throw new UnauthorizedResponse(
                    "Missing " + AUTHORIZATION_HEADER + " header or '" + TOKEN_QUERY_PARAM + "' query parameter");
        }
        try {
            final String userId = this.authService.validate(token);
            ctx.attribute(USER_ID_ATTRIBUTE, userId);
        } catch (final InvalidJwtException e) {
            throw new UnauthorizedResponse("Invalid or expired token");
        }
    }

    /**
     * Gates every {@link #ADMIN_PATH_PREFIX}-prefixed route ({@link #ADMIN_AUTH_USERS_PATH} and
     * its {@code /{id}} sibling) behind {@link de.lino.cloud.api.jwt.user.AuthUser#isAdmin()},
     * in addition to (registered after, so it always runs after) {@link
     * #requireValidBearerToken}'s own check - a non-admin caller with an otherwise perfectly
     * valid bearer token is still rejected here. Runs as a global {@code before} filter (the
     * same "global filter, branch on {@link Context#path()} internally" shape {@link
     * #requireValidBearerToken} already uses) rather than a path-scoped one, since a global
     * filter's ordering relative to {@link #requireValidBearerToken} is simpler to reason about
     * than two independently-path-scoped filters racing to run first.
     *
     * <p>Resolves the caller's own {@link de.lino.cloud.api.jwt.user.AuthUser} via {@link
     * AuthService#getAuthUser} off the user id {@link #requireValidBearerToken} already stashed -
     * never from anything in the request itself, so a caller can't claim admin by any means other
     * than actually having the flag set on their own account.
     *
     * @throws ForbiddenResponse if the caller's account doesn't exist (shouldn't happen behind an
     *     already-validated token) or isn't flagged admin
     */
    private void requireAdmin(@NotNull final Context ctx) {
        if (!ctx.path().startsWith(ADMIN_PATH_PREFIX)) {
            return;
        }
        final String userId = requireUserId(ctx);
        final boolean isAdmin = this.authService.getAuthUser(userId)
                .map(AuthUser::isAdmin)
                .orElse(false);
        if (!isAdmin) {
            throw new ForbiddenResponse("Admin privileges required");
        }
    }

    /**
     * Javalin {@code before} filter capping {@code /auth/*} request volume per client IP, via a
     * fixed-window counter ({@link AuthRateLimitBucket}) keyed by {@link Context#ip()}. Applies to
     * all seven original {@code /auth/*} routes ({@link #AUTH_PATH_PREFIX}) alike - both the five
     * exempted from {@link #requireValidBearerToken} (an anonymous caller has nothing else to key a
     * limit on) and the two bearer-gated change-email routes (a stolen/leaked token shouldn't get
     * unlimited free e-mail-change attempts either). Registered as the very first {@code before}
     * filter (ahead of {@link #requireValidBearerToken}/{@link #requireAdmin}) so an over-limit
     * caller is rejected before this instance does any other work on the request.
     *
     * <p><b>{@link #ME_PATH} is deliberately excluded (added 2026-09-02)</b> - unlike the seven
     * routes above, {@code GET /auth/me} is neither an anonymous credential-guessing target (it's
     * already bearer-gated by {@link #requireValidBearerToken}, which runs after this filter) nor a
     * sensitive mutating action like the change-email routes; it's a cheap, frequently-polled
     * informational read (the desktop app calls it on every login <em>and</em> every Dashboard
     * visit). Counting it against the same tight budget as login/register/password-reset meant a
     * handful of logins or Dashboard reloads could exhaust the whole window and spuriously
     * rate-limit a genuine login attempt with no abuse involved - confirmed the hard way once this
     * route shipped. This filter runs unconditionally on every request whose path starts with
     * {@link #AUTH_PATH_PREFIX}, before Javalin even resolves whether a route exists for it, so a
     * client hitting {@link #ME_PATH} against a deployment that predates this route (a 404) still
     * consumed a slot before this fix - worth knowing if this error resurfaces against a stale
     * deployment.
     *
     * <p>This is deliberately a defense against brute-forcing/spam volume, not a hard security
     * perimeter: {@link Context#ip()} is the immediate TCP peer address, which a caller behind a
     * shared NAT/reverse proxy may share with many unrelated legitimate users (a false-positive
     * risk, not a bypass), and a caller with access to many IPs can trivially spread requests
     * across them (a real bypass, accepted the same way this codebase accepts {@link
     * de.lino.cloud.plugin.connectivity.InternetConnectivityChecker}-style "good enough, not
     * bulletproof" trade-offs elsewhere).
     *
     * @throws TooManyRequestsResponse if this IP has exceeded {@link
     *     #resolveAuthRateLimitMaxRequests()} requests within the current {@link
     *     #resolveAuthRateLimitWindowSeconds()}-second window
     */
    private void requireWithinAuthRateLimit(@NotNull final Context ctx) {
        if (!ctx.path().startsWith(AUTH_PATH_PREFIX) || ME_PATH.equals(ctx.path())) {
            return;
        }
        final AuthRateLimitBucket bucket = this.authRateLimitBuckets.computeIfAbsent(ctx.ip(), ignored -> new AuthRateLimitBucket());
        final long windowMillis = resolveAuthRateLimitWindowSeconds() * 1000L;
        final int maxRequests = resolveAuthRateLimitMaxRequests();
        synchronized (bucket) {
            final long now = System.currentTimeMillis();
            if (now - bucket.windowStartEpochMillis >= windowMillis) {
                bucket.windowStartEpochMillis = now;
                bucket.count = 0;
            }
            bucket.count++;
            if (bucket.count > maxRequests) {
                throw new TooManyRequestsResponse(
                        "Too many authentication requests from this address - try again later");
            }
        }
    }

    /**
     * Reads {@link #AUTH_RATE_LIMIT_MAX_REQUESTS_CONFIG_KEY} from {@link
     * CloudDriver#getConfiguration()}, defaulting to {@link #DEFAULT_AUTH_RATE_LIMIT_MAX_REQUESTS}
     * if unset - same {@link JsonDocument#contains}-checked-first convention {@link
     * de.lino.cloud.auth.entity.CloudUser#getMaxBytesToUpload()}'s own config resolution uses,
     * since {@link JsonDocument#getInteger} throws on a missing key rather than defaulting.
     */
    private static int resolveAuthRateLimitMaxRequests() {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        return configuration.contains(AUTH_RATE_LIMIT_MAX_REQUESTS_CONFIG_KEY)
                ? configuration.getInteger(AUTH_RATE_LIMIT_MAX_REQUESTS_CONFIG_KEY)
                : DEFAULT_AUTH_RATE_LIMIT_MAX_REQUESTS;
    }

    /**
     * Item 10 (live push via WebSocket, see {@code architecture/SERVICES.md}): configures the
     * {@link #LIVE_UPDATES_PATH} WebSocket route. Unlike every HTTP route, authentication cannot
     * go through {@link #requireValidBearerToken} - Javalin's {@code before} filters only run
     * ahead of the HTTP upgrade request, and a browser {@code WebSocket} client cannot set a
     * custom {@code Authorization} header on the handshake the way a normal HTTP client can -
     * so the token is instead resolved (header, or the {@link #TOKEN_QUERY_PARAM} query-parameter
     * fallback {@link #resolveBearerToken(Context)}'s own Javadoc already documents the trade-off
     * of) directly inside {@code onConnect}, once the socket itself is already open, and an
     * invalid/missing token immediately closes it with {@link WsCloseStatus#POLICY_VIOLATION}
     * rather than ever registering the session.
     *
     * <p>A validated session is tracked in {@link #liveUpdateSessions}, keyed by the resolved
     * {@code authUserId} (stashed on the {@link WsContext} itself under {@link
     * #USER_ID_ATTRIBUTE}, mirroring {@link #requireValidBearerToken}'s own HTTP-side
     * convention), and untracked again in {@code onClose} regardless of why the connection ended.
     *
     * @param ws the Javalin WebSocket handler registry for {@link #LIVE_UPDATES_PATH}
     */
    private void configureLiveUpdatesWebSocket(@NotNull final WsConfig ws) {
        ws.onConnect(ctx -> {
            final String token = resolveBearerToken(ctx);
            if (token == null) {
                ctx.closeSession(WsCloseStatus.POLICY_VIOLATION, "Missing " + AUTHORIZATION_HEADER + " header or '" + TOKEN_QUERY_PARAM + "' query parameter");
                return;
            }
            final String userId;
            try {
                userId = this.authService.validate(token);
            } catch (final InvalidJwtException e) {
                ctx.closeSession(WsCloseStatus.POLICY_VIOLATION, "Invalid or expired token");
                return;
            }
            ctx.attribute(USER_ID_ATTRIBUTE, userId);
            this.liveUpdateSessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(ctx);
        });
        ws.onClose(ctx -> {
            final String userId = ctx.attribute(USER_ID_ATTRIBUTE);
            if (userId == null) {
                return;
            }
            this.liveUpdateSessions.computeIfPresent(userId, (ignored, sessions) -> {
                sessions.remove(ctx);
                return sessions.isEmpty() ? null : sessions;
            });
        });
    }

    /**
     * {@link #resolveBearerToken(Context)}'s exact logic, against a {@link WsContext} instead of
     * a plain {@link Context} - the two share no common supertype exposing {@code header}/{@code
     * queryParam}, so this is a small, deliberate duplication rather than a shared helper.
     *
     * @return the raw token, or {@code null} if neither source carries one
     */
    @Nullable
    private static String resolveBearerToken(@NotNull final WsContext ctx) {
        final String header = ctx.header(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return ctx.queryParam(TOKEN_QUERY_PARAM);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards to every session tracked in {@link #liveUpdateSessions} under {@code
     * authUserId}, if any - a no-op if nobody belonging to that account is currently connected to
     * {@link #LIVE_UPDATES_PATH}. Never throws: a failure sending to one session (e.g. it just
     * disconnected but hasn't been untracked yet) is caught and skipped so it can never prevent
     * delivery to that account's other connected sessions, matching this interface's own
     * "must never throw" contract.
     */
    @Override
    public void publish(@NonNull final String authUserId, @NonNull final String table,
                         @NonNull final String operation, @NonNull final String id) {
        try {

            final Set<WsContext> sessions = this.liveUpdateSessions.get(authUserId);
            if (sessions == null || sessions.isEmpty()) {
                return;
            }

            final JsonObject payload = new JsonObject();
            payload.addProperty("table", table);
            payload.addProperty("operation", operation);
            payload.addProperty("id", id);
            final String json = this.gson.toJson(payload);

            for (final WsContext session : sessions) {
                try {
                    session.send(json);
                } catch (final RuntimeException ignored) {
                    // A single broken/already-closing session must never stop delivery to its
                    // siblings - onClose (see configureLiveUpdatesWebSocket) is what untracks it.
                }
            }

        } catch (final RuntimeException e) {
            System.err.println("[DefaultRestFactory] failed to publish live update for account '" + authUserId + "':");
            e.printStackTrace();
        }
    }

    /**
     * Reads {@link #AUTH_RATE_LIMIT_WINDOW_SECONDS_CONFIG_KEY} from {@link
     * CloudDriver#getConfiguration()}, defaulting to {@link #DEFAULT_AUTH_RATE_LIMIT_WINDOW_SECONDS}
     * if unset - same convention as {@link #resolveAuthRateLimitMaxRequests()}.
     */
    private static long resolveAuthRateLimitWindowSeconds() {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        return configuration.contains(AUTH_RATE_LIMIT_WINDOW_SECONDS_CONFIG_KEY)
                ? configuration.getLong(AUTH_RATE_LIMIT_WINDOW_SECONDS_CONFIG_KEY)
                : DEFAULT_AUTH_RATE_LIMIT_WINDOW_SECONDS;
    }

    /**
     * Resolves the caller's JWT: the {@code Authorization: Bearer <jwt>} header if present,
     * otherwise a {@code ?token=<jwt>} query parameter - a deliberate fallback so a route can
     * still be reached by typing a URL directly into a browser's address bar, which cannot set
     * a custom header. This is a real security trade-off, not a free convenience: a token
     * passed as a query parameter ends up in browser history, this server's own access logs,
     * and any {@code Referer} header a page at that URL sends onward to a third party - prefer
     * the header (e.g. a {@code fetch()} call setting it explicitly) whenever the caller can
     * set one, and treat a query-parameter token as no more secret than the URL it's part of.
     *
     * @return the raw token, or {@code null} if neither source carries one
     */
    @Nullable
    private static String resolveBearerToken(@NotNull final Context ctx) {
        final String header = ctx.header(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return ctx.queryParam(TOKEN_QUERY_PARAM);
    }

    /**
     * Resolves a folder-id query parameter (named {@code queryParamName}) where both an omitted
     * parameter and an explicit {@link #ROOT_FOLDER_SENTINEL} mean the same thing: the root. Used
     * by {@link #handleUploadFile}/{@link #handleListFolders}, where there is no third meaning
     * (e.g. "unscoped") to distinguish "omitted" from - unlike {@link #handleListFiles}, which
     * reads its own {@link #FOLDER_ID_FIELD} parameter directly instead, since there "omitted"
     * means something else entirely (every file, unscoped).
     *
     * @return {@code null} for the root (parameter omitted or {@link #ROOT_FOLDER_SENTINEL}), otherwise the raw parameter value
     */
    @Nullable
    private static String resolveFolderIdOrRoot(@NotNull final Context ctx, final String queryParamName) {
        final String raw = ctx.queryParam(queryParamName);
        return raw == null || ROOT_FOLDER_SENTINEL.equals(raw) ? null : raw;
    }

    /**
     * Merges {@code folderId} into {@code file}'s own serialized JSON, under {@link
     * #FOLDER_ID_FIELD} - the same {@link JsonObject}-merge idiom {@link #parseOwnedBody}
     * already uses, applied here since {@link StoredFile} carries no folder field of its own to
     * serialize (see {@link Folder}'s Javadoc for why). Used by both {@link #handleUploadFile} and
     * {@link #handleDownloadFile}, so a freshly uploaded or individually-fetched file's response
     * reflects its folder exactly like every other route does. {@link #handleListFiles} does not
     * need this - its {@link StoredFileSummary} entries already carry their own {@code folderId}.
     */
    private JsonObject toJsonObject(final StoredFile file, @Nullable final String folderId) {
        final JsonObject json = this.gson.toJsonTree(file).getAsJsonObject();
        if (folderId != null) {
            json.addProperty(FOLDER_ID_FIELD, folderId);
        } else {
            json.add(FOLDER_ID_FIELD, JsonNull.INSTANCE);
        }
        return json;
    }

    /**
     * {@code POST /auth/login}: reads {@code {"username": ..., "password": ...}}
     * from the request body, dispatched off the Jetty worker thread since
     * {@link AuthService#login} runs Argon2id (deliberately slow) plus a
     * {@code DataFactory} lookup. Branches on {@link LoginResult#requiresTwoFactor()} (item 12,
     * see {@code architecture/SERVICES.md}): a normal completed login responds with the usual
     * {@link LoginResponse}, while an account with two-factor authentication enabled instead
     * responds {@code 200 OK} with a {@link TwoFactorRequiredResponse} carrying a pending token the
     * caller must present, together with a TOTP code, to {@link #handleTwoFactorLogin}.
     */
    private void handleLogin(@NotNull final Context ctx) {
        final LoginRequest request = this.gson.fromJson(ctx.body(), LoginRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.authService.login(request.username(), request.password().toCharArray()))
                .handle((result, failure) -> {
                    if (failure == null) {
                        if (result.requiresTwoFactor()) {
                            ctx.contentType("application/json")
                                    .result(this.gson.toJson(new TwoFactorRequiredResponse(true, result.pendingTwoFactorToken())));
                        } else {
                            ctx.contentType("application/json").result(this.gson.toJson(LoginResponse.of(result.tokens())));
                        }
                        return null;
                    }
                    throw unauthorizedOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"twoFactorRequired", "pendingToken"}} JSON response body {@link #handleLogin}
     * returns instead of a {@link LoginResponse} when the matched account has two-factor
     * authentication enabled - {@code twoFactorRequired} is always {@code true} on this shape
     * (present so a client can branch on one field rather than on the absence of {@code token}),
     * and {@code pendingToken} is what {@link #handleTwoFactorLogin} expects back.
     *
     * @param twoFactorRequired always {@code true}
     * @param pendingToken the token to present, together with a TOTP code, to {@code POST /auth/2fa/login}
     */
    private record TwoFactorRequiredResponse(boolean twoFactorRequired, String pendingToken) {
    }

    /**
     * The {@code {"username", "password"}} JSON body shape shared by {@code POST /auth/login}
     * and {@code POST /auth/register}.
     *
     * @param username the account's e-mail address (named {@code username} to match the wire shape)
     * @param password the plaintext password
     */
    private record LoginRequest(String username, String password) {
    }

    /**
     * The {@code {"token", "refreshToken"}} JSON response body returned by a successful login,
     * completed registration, completed password reset, or a completed {@code POST /auth/refresh} -
     * every one of {@link IAuthService}'s token-issuing methods returns an {@link AuthTokens} pair,
     * and this record is the one place that shape is serialized onto the wire.
     *
     * @param token the signed access JWT
     * @param refreshToken the opaque, longer-lived refresh token - see {@link AuthTokens#refreshToken()}
     */
    private record LoginResponse(String token, String refreshToken) {
        /** Builds a {@link LoginResponse} straight from an {@link AuthTokens} pair - the one place that mapping happens. */
        private static LoginResponse of(final AuthTokens tokens) {
            return new LoginResponse(tokens.accessToken(), tokens.refreshToken());
        }
    }

    /**
     * {@code POST /auth/register}: reads {@code {"username": ..., "password": ...}} from the
     * request body (the same shape {@link #handleLogin} reads, so {@link LoginRequest} is
     * reused rather than a near-identical record) and starts registration via {@link
     * AuthService#register} - which e-mails a verification code rather than creating the
     * account outright. Does <b>not</b> return a JWT; the caller must follow up with {@link
     * #handleConfirmRegistration} once it has the code. Dispatched off the Jetty worker thread
     * since this runs Argon2id/database I/O/an e-mail send. {@code 202 Accepted} on success.
     */
    private void handleRegister(@NotNull final Context ctx) {
        final LoginRequest request = this.gson.fromJson(ctx.body(), LoginRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> {
                    try {
                        this.authService.register(request.username(), request.password().toCharArray());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException(
                                "@DefaultRestFactory.handleRegister: failed to start registration for " + request.username(), e);
                    }
                })
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(202).contentType("application/json")
                                .result(this.gson.toJson(new MessageResponse("Verification code sent to " + request.username())));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"message"}} JSON response body returned by {@code POST /auth/register} on
     * successfully starting registration.
     *
     * @param message a human-readable status message
     */
    private record MessageResponse(String message) {
    }

    /**
     * The {@code {"username", "code"}} JSON body shape read by {@code POST /auth/register/confirm}.
     *
     * @param username the account's e-mail address being confirmed
     * @param code the verification code e-mailed by {@code POST /auth/register}
     */
    private record ConfirmRegistrationRequest(String username, String code) {
    }

    /**
     * {@code POST /auth/register/confirm}: reads {@code {"username": ..., "code": ...}} from
     * the request body and completes registration via {@link AuthService#confirmRegistration} -
     * creating the account and returning the resulting JWT under the same {@link LoginResponse}
     * shape {@code POST /auth/login} returns, so a caller goes straight from a confirmed code
     * into an authenticated session. Dispatched off the Jetty worker thread since this runs
     * database I/O. {@code 201 Created} on success.
     */
    private void handleConfirmRegistration(@NotNull final Context ctx) {
        final ConfirmRegistrationRequest request = this.gson.fromJson(ctx.body(), ConfirmRegistrationRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> {
                    try {
                        return this.authService.confirmRegistration(request.username(), request.code());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException(
                                "@DefaultRestFactory.handleConfirmRegistration: failed to complete registration for " + request.username(), e);
                    }
                })
                .handle((tokens, failure) -> {
                    if (failure == null) {
                        ctx.status(201).contentType("application/json").result(this.gson.toJson(LoginResponse.of(tokens)));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"username"}} JSON body shape read by {@code POST /auth/reset-password}.
     *
     * @param username the account's e-mail address to start a password reset for
     */
    private record RequestPasswordResetRequest(String username) {
    }

    /**
     * {@code POST /auth/reset-password}: reads {@code {"username": ...}} from the request body
     * and starts a password reset via {@link AuthService#requestPasswordReset} - which e-mails a
     * verification code only if an account exists under that address, but responds identically
     * either way (matching {@link AuthService#requestPasswordReset}'s own "don't leak" contract).
     * Dispatched off the Jetty worker thread since this may run database I/O/an e-mail send.
     * {@code 202 Accepted} on success, always, regardless of whether an account was found.
     */
    private void handleRequestPasswordReset(@NotNull final Context ctx) {
        final RequestPasswordResetRequest request = this.gson.fromJson(ctx.body(), RequestPasswordResetRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> {
                    try {
                        this.authService.requestPasswordReset(request.username());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException(
                                "@DefaultRestFactory.handleRequestPasswordReset: failed to start password reset for " + request.username(), e);
                    }
                })
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(202).contentType("application/json")
                                .result(this.gson.toJson(new MessageResponse("If an account exists under " + request.username() + ", a reset code has been sent.")));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"username", "code", "newPassword"}} JSON body shape read by {@code
     * POST /auth/reset-password/confirm}.
     *
     * @param username the account's e-mail address being reset
     * @param code the verification code e-mailed by {@code POST /auth/reset-password}
     * @param newPassword the caller's chosen new plaintext password
     */
    private record ConfirmPasswordResetRequest(String username, String code, String newPassword) {
    }

    /**
     * {@code POST /auth/reset-password/confirm}: reads {@code {"username": ..., "code": ...,
     * "newPassword": ...}} from the request body and completes the reset via {@link
     * AuthService#confirmPasswordReset} - replacing the account's password and returning the
     * resulting JWT under the same {@link LoginResponse} shape {@code POST /auth/login} returns,
     * so a caller goes straight from a confirmed reset into an authenticated session. Dispatched
     * off the Jetty worker thread since this runs Argon2id/database I/O. {@code 200 OK} on
     * success (not {@code 201} - unlike {@link #handleConfirmRegistration}, this doesn't create a
     * new resource, it replaces an existing account's password).
     */
    private void handleConfirmPasswordReset(@NotNull final Context ctx) {
        final ConfirmPasswordResetRequest request = this.gson.fromJson(ctx.body(), ConfirmPasswordResetRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> {
                    try {
                        return this.authService.confirmPasswordReset(request.username(), request.code(), request.newPassword().toCharArray());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException(
                                "@DefaultRestFactory.handleConfirmPasswordReset: failed to complete password reset for " + request.username(), e);
                    }
                })
                .handle((tokens, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json").result(this.gson.toJson(LoginResponse.of(tokens)));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"refreshToken"}} JSON body shape read by {@code POST /auth/refresh} and {@code
     * POST /auth/logout}.
     *
     * @param refreshToken a refresh token previously returned by {@code POST /auth/login}/{@code
     *     POST /auth/register/confirm}/{@code POST /auth/reset-password/confirm}/a prior {@code
     *     POST /auth/refresh}
     */
    private record RefreshRequest(String refreshToken) {
    }

    /**
     * {@code POST /auth/refresh}: exchanges a still-valid refresh token for a fresh {@link
     * LoginResponse} pair via {@link AuthService#refresh}, without requiring the caller to log in
     * again with a password - see that method's own Javadoc for the rotate-on-every-use contract.
     * Not bearer-gated (see {@link #requireValidBearerToken}'s exemption list) - the whole point
     * is to obtain a fresh access token once the old one has already expired. Dispatched off the
     * Jetty worker thread since this runs database I/O. {@code 200 OK} on success.
     */
    private void handleRefresh(@NotNull final Context ctx) {
        final RefreshRequest request = this.gson.fromJson(ctx.body(), RefreshRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> {
                    try {
                        return this.authService.refresh(request.refreshToken());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException("@DefaultRestFactory.handleRefresh: failed to refresh tokens", e);
                    }
                })
                .handle((tokens, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json").result(this.gson.toJson(LoginResponse.of(tokens)));
                        return null;
                    }
                    throw unauthorizedOrPropagate(failure);
                }));
    }

    /**
     * {@code POST /auth/logout}: best-effort, idempotent revocation of the caller's refresh token
     * via {@link AuthService#revokeRefreshToken} - possession of the token itself is this route's
     * own proof of authority, the same way it is for {@code POST /auth/refresh}, so this is not
     * bearer-gated either (see {@link #requireValidBearerToken}'s exemption list) and works even
     * with an already-expired access token. Always {@code 204 No Content}, regardless of whether
     * {@code refreshToken} still existed - logging out is never itself an error, the same
     * "don't leak/don't fail on already-gone state" idiom {@link
     * AuthService#revokeRefreshToken}'s own Javadoc documents. Dispatched off the Jetty worker
     * thread since this runs database I/O.
     */
    private void handleLogout(@NotNull final Context ctx) {
        final RefreshRequest request = this.gson.fromJson(ctx.body(), RefreshRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.authService.revokeRefreshToken(request.refreshToken()))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    System.err.println("[DefaultRestFactory] unmapped logout failure, returning 500:");
                    failure.printStackTrace();
                    throw failure instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(failure);
                }));
    }

    /**
     * The {@code {"newEmail"}} JSON body shape read by {@code POST /auth/change-email}.
     *
     * @param newEmail the address the authenticated caller's account would move to on confirmation
     */
    private record ChangeEmailRequest(String newEmail) {
    }

    /**
     * {@code POST /auth/change-email}: unlike every other {@code /auth/*} route above, this one
     * is <b>not</b> exempted from {@link #requireValidBearerToken} - it changes an already
     * authenticated account's own address, identified from the caller's own bearer token (via
     * {@link #requireUserId}), not from anything in the request body. Reads {@code {"newEmail":
     * ...}} and starts the change via {@link AuthService#requestEmailChange} - which e-mails a
     * verification code to {@code newEmail} rather than applying the change outright. Does
     * <b>not</b> return a JWT; the caller must follow up with {@link #handleConfirmEmailChange}
     * once it has the code. Dispatched off the Jetty worker thread since this runs a live MX
     * lookup/database I/O/an e-mail send. {@code 202 Accepted} on success.
     */
    private void handleRequestEmailChange(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        final ChangeEmailRequest request = this.gson.fromJson(ctx.body(), ChangeEmailRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> {
                    try {
                        this.authService.requestEmailChange(userId, request.newEmail());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException(
                                "@DefaultRestFactory.handleRequestEmailChange: failed to start email change for " + userId, e);
                    }
                })
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(202).contentType("application/json")
                                .result(this.gson.toJson(new MessageResponse("Verification code sent to " + request.newEmail())));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"code"}} JSON body shape read by {@code POST /auth/change-email/confirm}.
     *
     * @param code the verification code e-mailed by {@code POST /auth/change-email} to the pending new address
     */
    private record ConfirmChangeEmailRequest(String code) {
    }

    /**
     * {@code POST /auth/change-email/confirm}: bearer-gated like {@link #handleRequestEmailChange}
     * - the account being changed is the caller's own, from {@link #requireUserId}, not the
     * request body. Reads {@code {"code": ...}} and completes the change via {@link
     * AuthService#confirmEmailChange} - replacing the account's e-mail address. No fresh JWT is
     * returned (unlike {@link #handleConfirmRegistration}/{@link #handleConfirmPasswordReset}): a
     * token's subject is the account's id, never its e-mail address, so the caller's existing
     * token remains valid across this change. Dispatched off the Jetty worker thread since this
     * runs database I/O. {@code 200 OK} on success.
     */
    private void handleConfirmEmailChange(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        final ConfirmChangeEmailRequest request = this.gson.fromJson(ctx.body(), ConfirmChangeEmailRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> {
                    try {
                        this.authService.confirmEmailChange(userId, request.code());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException(
                                "@DefaultRestFactory.handleConfirmEmailChange: failed to complete email change for " + userId, e);
                    }
                })
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json")
                                .result(this.gson.toJson(new MessageResponse("E-mail address updated")));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"secretBase32", "otpauthUri"}} JSON response body returned by {@code
     * POST /auth/2fa/setup}.
     */
    private record TwoFactorSetupResponse(String secretBase32, String otpauthUri) {
        /** Builds a {@link TwoFactorSetupResponse} straight from a {@link TwoFactorSetupStart}. */
        private static TwoFactorSetupResponse of(final TwoFactorSetupStart start) {
            return new TwoFactorSetupResponse(start.secretBase32(), start.otpauthUri());
        }
    }

    /**
     * {@code POST /auth/2fa/setup} (item 12, see {@code architecture/SERVICES.md}): bearer-gated,
     * acts on the caller's own account (from {@link #requireUserId}). Starts enabling two-factor
     * authentication via {@link AuthService#beginTwoFactorSetup} - generates a fresh TOTP secret,
     * not yet live on the account, and returns it plus a ready-to-render {@code otpauth://} URI.
     * The caller must follow up with {@link #handleConfirmTwoFactorSetup} once it has a code from
     * its authenticator app. Dispatched off the Jetty worker thread since this runs database I/O.
     */
    private void handleBeginTwoFactorSetup(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.authService.beginTwoFactorSetup(userId))
                .handle((start, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(TwoFactorSetupResponse.of(start)));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"code"}} JSON body shape read by {@code POST /auth/2fa/confirm}.
     *
     * @param code the current TOTP code, produced by the caller's authenticator app from the pending secret
     */
    private record ConfirmTwoFactorSetupRequest(String code) {
    }

    /**
     * {@code POST /auth/2fa/confirm}: bearer-gated like {@link #handleBeginTwoFactorSetup}. Reads
     * {@code {"code": ...}} and completes setup via {@link AuthService#confirmTwoFactorSetup} -
     * from this point on, {@code POST /auth/login} for this account returns a {@link
     * TwoFactorRequiredResponse} instead of tokens directly. Dispatched off the Jetty worker
     * thread since this runs database I/O. {@code 200 OK} on success.
     */
    private void handleConfirmTwoFactorSetup(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        final ConfirmTwoFactorSetupRequest request = this.gson.fromJson(ctx.body(), ConfirmTwoFactorSetupRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> {
                    try {
                        this.authService.confirmTwoFactorSetup(userId, request.code());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException(
                                "@DefaultRestFactory.handleConfirmTwoFactorSetup: failed to enable two-factor authentication for " + userId, e);
                    }
                })
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json")
                                .result(this.gson.toJson(new MessageResponse("Two-factor authentication enabled")));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"password"}} JSON body shape read by {@code POST /auth/2fa/disable}.
     *
     * @param password the account's current password, re-verified before disabling
     */
    private record DisableTwoFactorRequest(String password) {
    }

    /**
     * {@code POST /auth/2fa/disable}: bearer-gated like {@link #handleBeginTwoFactorSetup}. Reads
     * {@code {"password": ...}} and disables two-factor authentication via {@link
     * AuthService#disableTwoFactor} - which re-verifies {@code password} first, since a
     * stolen-but-still-valid bearer token alone should not be enough to turn off an account's
     * second factor. Dispatched off the Jetty worker thread since this runs Argon2id/database I/O.
     * {@code 200 OK} on success.
     */
    private void handleDisableTwoFactor(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        final DisableTwoFactorRequest request = this.gson.fromJson(ctx.body(), DisableTwoFactorRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.authService.disableTwoFactor(userId, request.password().toCharArray()))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json")
                                .result(this.gson.toJson(new MessageResponse("Two-factor authentication disabled")));
                        return null;
                    }
                    throw unauthorizedOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"pendingToken", "code"}} JSON body shape read by {@code POST /auth/2fa/login}.
     *
     * @param pendingToken the token returned by {@code POST /auth/login}'s {@link TwoFactorRequiredResponse}
     * @param code the current TOTP code, produced by the caller's authenticator app
     */
    private record TwoFactorLoginRequest(String pendingToken, String code) {
    }

    /**
     * {@code POST /auth/2fa/login}: completes a login left pending by {@code POST /auth/login}
     * returning a {@link TwoFactorRequiredResponse}, via {@link AuthService#completeTwoFactorLogin} -
     * verifies the TOTP code and, on success, issues real tokens under the same {@link
     * LoginResponse} shape {@code POST /auth/login} returns for a non-2FA account. Not bearer-gated
     * (see {@link #requireValidBearerToken}'s exemption list) - the caller has no real access token
     * yet by definition. Dispatched off the Jetty worker thread since this runs database I/O.
     * {@code 200 OK} on success.
     */
    private void handleTwoFactorLogin(@NotNull final Context ctx) {
        final TwoFactorLoginRequest request = this.gson.fromJson(ctx.body(), TwoFactorLoginRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> {
                    try {
                        return this.authService.completeTwoFactorLogin(request.pendingToken(), request.code());
                    } catch (final DatabaseClientException | KeyWrapException e) {
                        throw new RuntimeException("@DefaultRestFactory.handleTwoFactorLogin: failed to complete two-factor login", e);
                    }
                })
                .handle((tokens, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json").result(this.gson.toJson(LoginResponse.of(tokens)));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
                }));
    }

    /**
     * The {@code {"authUserId", "emailAddress", "isAdmin"}} JSON response shape returned by {@code
     * GET /auth/me}.
     */
    private record MeResponse(String authUserId, String emailAddress, boolean isAdmin) {
        /** Builds a {@link MeResponse} straight from an {@link AuthUser} - the one place that mapping happens. */
        private static MeResponse of(final AuthUser user) {
            return new MeResponse(user.getId(), user.getEmailAddress(), user.isAdmin());
        }
    }

    /**
     * {@code GET /auth/me}: bearer-gated, resolves the caller's own account (from {@link
     * #requireUserId}, never anything in the request itself) via {@link
     * AuthService#getAuthUser(String)} and returns its id/email/admin flag. Added so a client can
     * learn whether the signed-in account is flagged {@link AuthUser#isAdmin()} without needing to
     * probe an admin-gated route and interpret a {@code 403} - the desktop app's Admin sidebar
     * entry is shown/hidden based on this response.
     */
    private void handleGetMe(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.authService.getAuthUser(userId))
                .handle((authUser, failure) -> {
                    if (failure == null) {
                        if (authUser.isEmpty()) {
                            throw new UnauthorizedResponse("Account no longer exists");
                        }
                        ctx.contentType("application/json").result(this.gson.toJson(MeResponse.of(authUser.get())));
                        return null;
                    }
                    System.err.println("[DefaultRestFactory] unmapped /auth/me lookup failure, returning 500:");
                    failure.printStackTrace();
                    throw failure instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(failure);
                }));
    }

    /**
     * {@code GET /admin/authUsers}: lists every registered {@link AuthUser} via {@link
     * AuthService#getAuthUsers()} - gated by {@link #requireAdmin} (in addition to {@link
     * #requireValidBearerToken}), so only a caller whose own account is flagged {@link
     * AuthUser#isAdmin()} ever reaches this handler. Returns the raw {@link AuthUser} JSON
     * array as-is (Gson's own field-reflection serialization, the same shape every other
     * {@code Serialized} entity is returned in over this API) - {@code passwordHash} is a
     * one-way Argon2id hash, not a secret an admin caller needs redacted from an
     * already-admin-gated route.
     */
    private void handleListAuthUsers(@NotNull final Context ctx) {
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(this.authService::getAuthUsers)
                .handle((authUsers, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json").result(this.gson.toJson(authUsers));
                        return null;
                    }
                    System.err.println("[DefaultRestFactory] unmapped admin authUsers listing failure, returning 500:");
                    failure.printStackTrace();
                    throw failure instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(failure);
                }));
    }

    /**
     * {@code GET /admin/authUsers/{id}}: looks up a single {@link AuthUser} by id via {@link
     * AuthService#getAuthUser(String)} - gated by {@link #requireAdmin} the same way {@link
     * #handleListAuthUsers} is. {@code 404} (via {@link NotFoundResponse}) if no account exists
     * under {@code id} - an admin caller is trusted with existence information here, unlike the
     * deliberate "don't leak" idiom the rest of this class uses for a non-admin caller's own data.
     */
    private void handleGetAuthUser(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.authService.getAuthUser(id))
                .handle((authUser, failure) -> {
                    if (failure == null) {
                        if (authUser.isEmpty()) {
                            throw new NotFoundResponse("No AuthUser with id " + id);
                        }
                        ctx.status(200).contentType("application/json").result(this.gson.toJson(authUser.get()));
                        return null;
                    }
                    System.err.println("[DefaultRestFactory] unmapped admin authUser lookup failure, returning 500:");
                    failure.printStackTrace();
                    throw failure instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(failure);
                }));
    }

    /**
     * One entry in {@code GET /admin/audit-log}'s response array - mirrors {@link
     * de.lino.cloud.api.audit.AuditEvent}'s fields, with {@code actorAuthUserId} resolved to an
     * email address (via {@link #resolveAuditActorEmail}) for display, the same way {@code
     * AuditLogCommand}'s terminal listing already does.
     */
    private record AuditLogEntryResponse(long timestampEpochMillis, String action, String actorEmail, String targetId) {
        /** Builds an {@link AuditLogEntryResponse} from a raw {@link de.lino.cloud.api.audit.AuditEvent} plus its already-resolved actor email. */
        private static AuditLogEntryResponse of(final de.lino.cloud.api.audit.AuditEvent event, final String actorEmail) {
            return new AuditLogEntryResponse(event.getTimestampEpochMillis(), event.getAction().name(), actorEmail, event.getTargetId());
        }
    }

    /**
     * {@code GET /admin/audit-log}: gated by {@link #requireAdmin} the same way {@link
     * #handleListAuthUsers} is. Reads every {@link de.lino.cloud.api.audit.AuditEvent} directly off
     * {@link #dataFactory} (mirroring {@code AuditLogCommand}'s terminal implementation, since
     * {@code AuditLogService} itself deliberately exposes only {@code record} - see its own
     * Javadoc), sorted newest-first. Returns the most recent {@link #DEFAULT_AUDIT_LOG_LIMIT}
     * entries by default; {@code ?all=true} returns every entry instead, and {@code
     * ?email=<address>} filters to only that account's own actions (resolved via {@link
     * AuthService#getAuthUsers()} the same case-insensitive way {@code AuditLogCommand} does) -
     * both query parameters can be combined.
     */
    private void handleListAuditLog(@NotNull final Context ctx) {
        final boolean all = Boolean.parseBoolean(ctx.queryParam(AUDIT_LOG_ALL_QUERY_PARAM));
        final String emailFilter = ctx.queryParam(AUDIT_LOG_EMAIL_QUERY_PARAM);
        ctx.future(() -> this.dataFactory.getEntitiesAsync(de.lino.cloud.api.audit.AuditEvent.class)
                .handle((events, failure) -> {
                    if (failure != null) {
                        System.err.println("[DefaultRestFactory] unmapped admin audit-log listing failure, returning 500:");
                        failure.printStackTrace();
                        throw failure instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(failure);
                    }
                    final List<de.lino.cloud.api.audit.AuditEvent> sorted = events.stream()
                            .sorted(Comparator.comparingLong(de.lino.cloud.api.audit.AuditEvent::getTimestampEpochMillis).reversed())
                            .toList();
                    final List<de.lino.cloud.api.audit.AuditEvent> filtered;
                    if (emailFilter != null && !emailFilter.isBlank()) {
                        final String actorAuthUserId = this.authService.getAuthUsers().stream()
                                .filter(candidate -> candidate.getEmailAddress().equalsIgnoreCase(emailFilter))
                                .map(AuthUser::getId)
                                .findFirst()
                                .orElse(null);
                        filtered = actorAuthUserId == null ? List.of()
                                : sorted.stream().filter(event -> actorAuthUserId.equals(event.getActorAuthUserId())).toList();
                    } else {
                        filtered = sorted;
                    }
                    final List<de.lino.cloud.api.audit.AuditEvent> limited = all ? filtered : filtered.stream().limit(DEFAULT_AUDIT_LOG_LIMIT).toList();
                    final List<AuditLogEntryResponse> response = limited.stream()
                            .map(event -> AuditLogEntryResponse.of(event, resolveAuditActorEmail(event.getActorAuthUserId())))
                            .toList();
                    ctx.contentType("application/json").result(this.gson.toJson(response));
                    return null;
                }));
    }

    /** Resolves an {@link de.lino.cloud.api.audit.AuditEvent#getActorAuthUserId()} back to its account's email for display, or {@code null}/the raw id if it can't be resolved - mirrors {@code AuditLogCommand}'s own terminal resolution. */
    private String resolveAuditActorEmail(final String actorAuthUserId) {
        if (actorAuthUserId == null) {
            return null;
        }
        return this.authService.getAuthUser(actorAuthUserId).map(AuthUser::getEmailAddress).orElse(actorAuthUserId);
    }

    /**
     * {@code GET /admin/metrics}: returns the current {@link de.lino.cloud.api.metrics.MetricsSnapshot}
     * (item 13's counters/gauges), read in-process off {@code
     * cloud-driver-extensions-metrics}'s own {@code MicrometerMetricsSnapshotProvider} - gated by
     * {@link #requireAdmin} the same way {@link #handleListAuthUsers}/{@link #handleListAuditLog}
     * are. This route deliberately never makes an HTTP call to that extension's own separate,
     * loopback-only Prometheus port ({@code MetricsHttpServer}) - it reads the same {@code
     * PrometheusMeterRegistry} that port scrapes directly, via {@link
     * de.lino.cloud.api.factory.service.IServiceContainer#getMetricsSnapshotProvider()}. Responds
     * {@code 503} (via {@link ServiceUnavailableResponse}) if {@code cloud-driver-extensions-metrics}
     * isn't running in this deployment at all, rather than a bare {@code 500} or a fabricated
     * all-zero snapshot that would misreport a genuinely running metrics exporter as idle.
     */
    private void handleGetAdminMetrics(@NotNull final Context ctx) {
        final de.lino.cloud.api.metrics.MetricsSnapshotProvider provider =
                CloudDriver.getInstance().getServiceContainer().getMetricsSnapshotProvider();
        if (provider == null) {
            throw new ServiceUnavailableResponse("Metrics extension is not running on this deployment");
        }
        ctx.status(200).contentType("application/json").result(this.gson.toJson(provider.getSnapshot()));
    }

    /**
     * {@code POST /files?fileName=<url-encoded name>&folderId=<id-or-omitted>}: streams the raw
     * request body ({@code application/octet-stream}, not base64-encoded JSON - a base64 body
     * inflates the transferred/parsed size by ~37% and forces {@link #gson} to parse one huge
     * JSON string field, both pure overhead on top of the size-limit concern below; large uploads
     * pay for both) to a scratch file via {@link #receiveUploadToScratchFile} - <b>not</b> {@link
     * Context#bodyAsBytes()}, which fully buffers the whole body in JVM heap before this method
     * ever sees it - then uploads the received bytes via {@link
     * CloudUserService#uploadFile(String, String, byte[], String)}, tracked under the caller's
     * own user id (from {@link #USER_ID_ATTRIBUTE}, set by {@link #requireValidBearerToken}).
     * {@link #FOLDER_ID_FIELD} is resolved via {@link #resolveFolderIdOrRoot} - omitted (or
     * {@link #ROOT_FOLDER_SENTINEL}) places the file at the root, matching this route's
     * pre-folders behavior.
     *
     * <p>The receive-to-scratch-file step runs synchronously on the calling (Jetty worker)
     * thread, same as the {@link Context#bodyAsBytes()} call it replaces - no threading
     * regression, since that call was already a blocking read for the whole request-body
     * duration; the difference is heap stays bounded to one small copy buffer regardless of file
     * size, rather than one giant {@code byte[]}, and an oversized upload is rejected (via {@link
     * #receiveUploadToScratchFile} throwing {@link ContentTooLargeResponse} mid-stream) before
     * the rest of the body is even read. Only the scratch file's <em>own</em> read (into the
     * {@code byte[]} {@link CloudUserService#uploadFile} still needs - see Phase 3 Option 1 in
     * {@code architecture/OPTIMIZE_UPLOAD.md}) plus the real database/encryption I/O are
     * dispatched off the Jetty worker thread, via {@link MultiTaskingFactory}, as before.
     *
     * <p>Responds with a {@link StoredFileSummary} of the uploaded file - not the full {@link
     * StoredFile} (content included) the way this route used to - since the caller already has
     * the bytes it just uploaded and doesn't need them echoed back; base64-round-tripping an
     * entire large file on every upload was pure waste. Fetch full content afterward via {@code
     * GET /files/{id}}/{@code GET /files/{id}/content} if it's ever actually needed again.
     */
    private void handleUploadFile(@NotNull final Context ctx) {
        final String fileName = ctx.queryParam(FILE_NAME_QUERY_PARAM);
        if (fileName == null || fileName.isBlank()) {
            throw new BadRequestResponse("Missing '" + FILE_NAME_QUERY_PARAM + "' query parameter");
        }
        final String folderId = resolveFolderIdOrRoot(ctx, FOLDER_ID_FIELD);
        final String userId = requireUserId(ctx);

        final Path scratchFile = receiveUploadToScratchFile(ctx.bodyInputStream());

        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> {
                    // Delete the scratch file on every path out of this block - a successful
                    // upload, a business-rule failure (e.g. UploadQuotaExceededException), or an
                    // I/O failure reading it back - matching Phase 4's "delete on both success
                    // and failure" requirement. By the time uploadFile returns (or throws), its
                    // content is already fully in hand (copied into the StoredFile it built, or
                    // queued as one by DefaultFileFactory's offline path) - the scratch file's
                    // job is done either way, so this never collides with PendingUploadScheduler's
                    // own, separate retry-later machinery.
                    try {
                        final byte[] content = Files.readAllBytes(scratchFile);
                        return this.cloudUserService.uploadFile(userId, fileName, content, folderId);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(
                                "@DefaultRestFactory.handleUploadFile: failed to read scratch file for '" + fileName + "'", e);
                    } finally {
                        deleteScratchFileQuietly(scratchFile);
                    }
                })
                .handle((storedFile, failure) -> {
                    if (failure == null) {
                        ctx.status(201).contentType("application/json").result(this.gson.toJson(toSummary(storedFile, folderId)));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, StoredFile.class, fileName);
                }));
    }

    /**
     * Streams {@code requestBody} to a fresh temp file under {@link Constraints#UPLOAD_SCRATCH_PATH}
     * (created on first use), in fixed-size chunks - never holding more than one {@link
     * #UPLOAD_STREAM_BUFFER_SIZE}-byte buffer in memory regardless of how large the upload is.
     * Rejects early, via {@link ContentTooLargeResponse} (before reading the rest of the stream,
     * deleting the partially-written scratch file first), the moment the running total exceeds
     * {@link #MAX_REQUEST_SIZE_BYTES} - unlike {@link Context#bodyAsBytes()}'s enforcement of
     * {@link JavalinConfig#http}{@code .maxRequestSize}, which only ever catches an oversized
     * body <em>after</em> the whole thing has already been buffered.
     *
     * @param requestBody the request's raw body stream ({@link Context#bodyInputStream()})
     * @return the scratch file's path, fully written - the caller is responsible for deleting it
     */
    private static Path receiveUploadToScratchFile(@NotNull final InputStream requestBody) {
        final Path scratchFile;
        try {
            Files.createDirectories(Constraints.UPLOAD_SCRATCH_PATH);
            scratchFile = Files.createTempFile(Constraints.UPLOAD_SCRATCH_PATH, "upload-", ".tmp");
        } catch (final IOException e) {
            throw new UncheckedIOException(
                    "@DefaultRestFactory.receiveUploadToScratchFile: failed to create a scratch file under "
                            + Constraints.UPLOAD_SCRATCH_PATH, e);
        }

        try (OutputStream scratchOutput = Files.newOutputStream(scratchFile)) {
            final byte[] buffer = new byte[UPLOAD_STREAM_BUFFER_SIZE];
            long totalBytesReceived = 0;
            int bytesRead;
            while ((bytesRead = requestBody.read(buffer)) != -1) {
                totalBytesReceived += bytesRead;
                if (totalBytesReceived > MAX_REQUEST_SIZE_BYTES) {
                    throw new ContentTooLargeResponse(
                            "Upload exceeds the " + MAX_REQUEST_SIZE_BYTES + " byte limit");
                }
                scratchOutput.write(buffer, 0, bytesRead);
            }
        } catch (final IOException e) {
            deleteScratchFileQuietly(scratchFile);
            throw new UncheckedIOException(
                    "@DefaultRestFactory.receiveUploadToScratchFile: failed to receive upload body", e);
        } catch (final RuntimeException oversizedOrOther) {
            deleteScratchFileQuietly(scratchFile);
            throw oversizedOrOther;
        }

        return scratchFile;
    }

    /** Best-effort delete of a Phase-3 scratch file - a failure here is logged nowhere, matching this class's other quiet-cleanup helpers; the file is small and transient either way. */
    private static void deleteScratchFileQuietly(final Path scratchFile) {
        try {
            Files.deleteIfExists(scratchFile);
        } catch (final IOException ignored) {
            // Best-effort cleanup only - the file is under Constraints.UPLOAD_SCRATCH_PATH, an
            // operator can clear it manually if this ever fails (e.g. a permissions problem).
        }
    }

    /**
     * Builds a {@link StoredFileSummary} straight from a freshly uploaded/fetched {@link
     * StoredFile} plus its resolved folder placement - the same descriptive-fields-only shape
     * {@link CloudUserService#resolveFileSummary} builds for a listing, reused here by {@link
     * #handleUploadFile} so an upload's response never carries content.
     */
    private static StoredFileSummary toSummary(final StoredFile file, @Nullable final String folderId) {
        return new StoredFileSummary(file.fileId(), file.fileName(), file.contentType(), file.sizeBytes(),
                file.createdAt().toEpochMilli(), file.updatedAt().toEpochMilli(), folderId);
    }

    /**
     * {@code GET /files}, optionally {@code ?folderId=<id-or-{@value #ROOT_FOLDER_SENTINEL}>}:
     * lists every {@link StoredFile} tracked as belonging to the caller as a {@link
     * StoredFileSummary} - descriptive fields plus folder placement, deliberately without content
     * (see {@link CloudUserService#listFileSummaries}), so this route never decrypts/decompresses
     * a single file's content just to render a list. Fetch a specific file's content afterwards
     * via {@code GET /files/{id}} ({@link #handleDownloadFile}). {@link #FOLDER_ID_FIELD}
     * <b>omitted</b> lists every file regardless of folder - the pre-folders behavior this route
     * always had, kept as the default for any client that doesn't yet know about folders; present
     * (including {@link #ROOT_FOLDER_SENTINEL}) scopes the list to just that one folder (or the
     * root).
     *
     * <p>Optionally {@code ?limit=<n>} (and {@code ?cursor=<opaque>}): opts into cursor pagination
     * (see {@link #LIMIT_QUERY_PARAM}'s own Javadoc for exactly what "opts into" means) - the
     * response becomes {@code {"items": [...], "nextCursor": ...}} instead of a bare array, backed
     * by {@link CloudUserService#listFileSummariesPage}.
     */
    private void handleListFiles(@NotNull final Context ctx) {
        final String folderIdParam = ctx.queryParam(FOLDER_ID_FIELD);
        final String resolvedFolderId = folderIdParam == null ? null
                : (ROOT_FOLDER_SENTINEL.equals(folderIdParam) ? null : folderIdParam);
        final Integer limit = parsePageLimit(ctx);
        final String userId = requireUserId(ctx);
        if (limit == null) {
            ctx.future(() -> MultiTaskingFactory.getInstance()
                    .supplyAsync(() -> folderIdParam == null
                            ? this.cloudUserService.listFileSummaries(userId)
                            : this.cloudUserService.listFileSummaries(userId, resolvedFolderId))
                    .thenAccept(summaries -> ctx.contentType("application/json").result(this.gson.toJson(summaries))));
            return;
        }
        final String cursor = ctx.queryParam(CURSOR_QUERY_PARAM);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listFileSummariesPage(userId, resolvedFolderId, cursor, limit))
                .thenAccept(page -> ctx.contentType("application/json").result(this.gson.toJson(toPageEnvelope(page)))));
    }

    /**
     * Reads {@link #LIMIT_QUERY_PARAM} as a positive integer, or {@code null} if absent/blank -
     * {@code null} means "this request did not opt into pagination", not "page size zero".
     *
     * @throws BadRequestResponse if {@link #LIMIT_QUERY_PARAM} is present but not a positive integer
     */
    private static Integer parsePageLimit(final Context ctx) {
        final String raw = ctx.queryParam(LIMIT_QUERY_PARAM);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            final int limit = Integer.parseInt(raw.trim());
            if (limit <= 0) {
                throw new BadRequestResponse("'" + LIMIT_QUERY_PARAM + "' must be a positive integer");
            }
            return limit;
        } catch (final NumberFormatException exception) {
            throw new BadRequestResponse("'" + LIMIT_QUERY_PARAM + "' must be a positive integer");
        }
    }

    /**
     * Builds the {@code {"items": [...], "nextCursor": ...}} envelope {@link #handleListFiles}/
     * {@link #handleListFolders} serialize once a caller opts into pagination.
     */
    private JsonObject toPageEnvelope(final CursorPage<?> page) {
        final JsonObject envelope = new JsonObject();
        envelope.add(PAGE_ITEMS_FIELD, this.gson.toJsonTree(page.items()));
        envelope.addProperty(PAGE_NEXT_CURSOR_FIELD, page.nextCursor());
        return envelope;
    }

    /**
     * {@code GET /files/{id}}: fetches one {@link StoredFile}'s full content via {@link
     * CloudUserService#getFile}, which checks the caller actually owns it - an {@link
     * IllegalArgumentException} from that check is translated into {@link NotFoundResponse}, the
     * same "don't confirm existence" idiom {@link #handleDeleteFile} already uses. The response
     * carries the same {@link #FOLDER_ID_FIELD}-merged shape {@link #handleUploadFile} returns.
     */
    private void handleDownloadFile(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.getFile(userId, id))
                .handle((entry, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(this.toJsonObject(entry.file(), entry.folderId())));
                        return null;
                    }
                    throw notFoundOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * {@code GET /files/{id}/content}: streams one {@link StoredFile}'s decrypted, decompressed
     * content directly to the response body - {@code Content-Type} set from the file's own {@link
     * StoredFile#contentType()}, {@code Content-Disposition: attachment} carrying its {@link
     * StoredFile#fileName()} (RFC 5987 {@code filename*=} encoding, so a non-ASCII name survives
     * intact) - rather than the base64-in-JSON shape {@link #handleDownloadFile} (kept unmounted,
     * unchanged, alongside this route) returns. Ownership-checked the same way via {@link
     * CloudUserService#getFile}.
     *
     * <p>{@link StoredFile#content()} still fully materializes the decrypted plaintext in memory
     * before this method runs - {@code EnvelopeEncryptionService}'s AES-GCM decrypt is single-shot,
     * not chunked (see {@code architecture/OPTIMIZE_UPLOAD.md}'s "Open decision" - a real,
     * deliberately out-of-scope limitation, not an oversight here). What this route actually
     * eliminates is everything downstream of that plaintext: the ~1.37x base64 string, the
     * enclosing JSON document, and the UTF-8 re-encoding {@link Context#result(String)} would
     * otherwise perform on top of it - {@link Context#writeSeekableStream(java.io.InputStream,
     * String, long)} streams the already-resolved bytes straight to the response instead.
     */
    private void handleDownloadFileContent(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.getFile(userId, id))
                .handle((entry, failure) -> {
                    if (failure == null) {
                        final StoredFile file = entry.file();
                        final byte[] content = file.content();
                        final String encodedFileName = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
                        ctx.header("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName);
                        ctx.writeSeekableStream(new ByteArrayInputStream(content), file.contentType(), content.length);
                        return null;
                    }
                    throw notFoundOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * {@code DELETE /files/{id}}: deletes a {@link StoredFile} via {@link
     * CloudUserService#deleteFile}, which itself checks the caller actually owns it -
     * an {@link IllegalArgumentException} from that check is translated into {@link
     * NotFoundResponse} here, the same "don't confirm existence" idiom {@link
     * #isOwnedByCaller} already uses for {@link Owned} entities.
     */
    private void handleDeleteFile(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.deleteFile(userId, id))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw notFoundOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * {@code GET /files/trash}: lists every {@link StoredFile} currently in the caller's trash, as
     * {@link de.lino.cloud.api.file.TrashedFileSummary}s (each carrying a {@code purgeAtEpochMillis}
     * - see that record's own Javadoc, added 2026-09-02) - the same descriptive-fields-only
     * shape/cost {@link #handleListFiles} returns for a live listing, via {@link
     * CloudUserService#listDeletedFiles}.
     */
    private void handleListDeletedFiles(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listDeletedFiles(userId))
                .thenAccept(summaries -> ctx.contentType("application/json").result(this.gson.toJson(summaries))));
    }

    /**
     * {@code POST /files/{id}/restore}: restores a trashed {@link StoredFile} via {@link
     * CloudUserService#restoreFile}, which checks the caller owns it. {@code 204} on success,
     * {@code 404} if unowned/nonexistent (via {@link #folderFailureOrPropagate}'s {@link
     * IllegalArgumentException} handling), {@code 409} if it isn't currently in the trash (via
     * that same method's {@link IllegalStateException} handling).
     */
    private void handleRestoreFile(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.restoreFile(userId, id))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * The {@code {"folderId"}} JSON body shape read by {@code PUT /files/{id}/folder} -
     * {@code folderId} may be an explicit JSON {@code null} to move the file back to the root
     * (unlike a query parameter, a JSON body can carry a real {@code null}, so no {@link
     * #ROOT_FOLDER_SENTINEL}-style sentinel is needed here).
     *
     * @param folderId the folder to move the file into, or {@code null} for the root
     */
    private record MoveFileRequest(String folderId) {
    }

    /**
     * {@code PUT /files/{id}/folder}: moves a {@link StoredFile} into another folder (or back to
     * the root) via {@link CloudUserService#moveFile}, which checks the caller owns both the
     * file and the target folder. {@code 204} on success.
     */
    private void handleMoveFile(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final MoveFileRequest request = this.gson.fromJson(ctx.body(), MoveFileRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.moveFile(userId, id, request.folderId()))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * The {@code {"name", "parentFolderId"}} JSON body shape read by {@code POST /folders}.
     *
     * @param name the new folder's display name
     * @param parentFolderId the parent folder to nest the new folder inside, or {@code null} for the top level
     */
    private record CreateFolderRequest(String name, String parentFolderId) {
    }

    /**
     * {@code POST /folders}: creates a new {@link Folder} owned by the caller via {@link
     * CloudUserService#createFolder}. {@code 201} with the created {@link Folder} on success.
     */
    private void handleCreateFolder(@NotNull final Context ctx) {
        final CreateFolderRequest request = this.gson.fromJson(ctx.body(), CreateFolderRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.createFolder(userId, request.name(), request.parentFolderId()))
                .handle((folder, failure) -> {
                    if (failure == null) {
                        ctx.status(201).contentType("application/json").result(this.gson.toJson(folder));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, request.parentFolderId());
                }));
    }

    /**
     * {@code GET /folders}, optionally {@code ?parentFolderId=<id-or-{@value
     * #ROOT_FOLDER_SENTINEL}>}: lists every {@link Folder} owned by the caller directly inside
     * that parent, via {@link CloudUserService#listFolders} - omitted (or {@link
     * #ROOT_FOLDER_SENTINEL}) lists the caller's top-level folders.
     *
     * <p>Optionally {@code ?limit=<n>} (and {@code ?cursor=<opaque>}): same pagination opt-in as
     * {@link #handleListFiles}, backed by {@link CloudUserService#listFoldersPage} instead.
     */
    private void handleListFolders(@NotNull final Context ctx) {
        final String parentFolderId = resolveFolderIdOrRoot(ctx, PARENT_FOLDER_ID_QUERY_PARAM);
        final Integer limit = parsePageLimit(ctx);
        final String userId = requireUserId(ctx);
        if (limit == null) {
            ctx.future(() -> MultiTaskingFactory.getInstance()
                    .supplyAsync(() -> this.cloudUserService.listFolders(userId, parentFolderId))
                    .thenAccept(folders -> ctx.contentType("application/json").result(this.gson.toJson(folders))));
            return;
        }
        final String cursor = ctx.queryParam(CURSOR_QUERY_PARAM);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listFoldersPage(userId, parentFolderId, cursor, limit))
                .thenAccept(page -> ctx.contentType("application/json").result(this.gson.toJson(toPageEnvelope(page)))));
    }

    /**
     * The {@code {"name", "parentFolderId"}} JSON body shape read by {@code PUT /folders/{id}} -
     * a full replace of both fields, matching {@code PUT}'s whole-resource-replace semantics
     * (the same convention {@link #bindUpdate} already uses for a generically-registered type).
     *
     * @param name the folder's new display name
     * @param parentFolderId the folder's new parent, or {@code null} to move it to the top level
     */
    private record UpdateFolderRequest(String name, String parentFolderId) {
    }

    /**
     * {@code PUT /folders/{id}}: renames and/or moves a {@link Folder} in one step via {@link
     * CloudUserService#updateFolder}, which validates both that the caller owns the folder (and
     * the new parent, if changing) and that the move would not create a cycle.
     */
    private void handleUpdateFolder(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final UpdateFolderRequest request = this.gson.fromJson(ctx.body(), UpdateFolderRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.updateFolder(userId, id, request.name(), request.parentFolderId()))
                .handle((folder, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(folder));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, id);
                }));
    }

    /**
     * {@code DELETE /folders/{id}}: deletes a {@link Folder} via {@link
     * CloudUserService#deleteFolder}, which checks the caller owns it and that it is currently
     * empty. {@code 204} on success, {@code 409} if it still has children (via {@link
     * #folderFailureOrPropagate}'s {@link IllegalStateException} handling).
     */
    private void handleDeleteFolder(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.deleteFolder(userId, id))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, id);
                }));
    }

    /**
     * {@code GET /folders/trash}: lists every {@link Folder} currently in the caller's trash, as
     * {@link de.lino.cloud.api.file.TrashedFolderSummary}s (each carrying a {@code
     * purgeAtEpochMillis}, added 2026-09-02) via {@link CloudUserService#listDeletedFolders}.
     */
    private void handleListDeletedFolders(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listDeletedFolders(userId))
                .thenAccept(folders -> ctx.contentType("application/json").result(this.gson.toJson(folders))));
    }

    /**
     * {@code POST /trash/empty} (added 2026-09-02): permanently removes every file and folder
     * currently in the caller's trash via {@link CloudUserService#emptyTrash} - bypassing the
     * configured retention window entirely, the "Empty trash bin" action. {@code 204} on success
     * (also on an already-empty trash - idempotent, matching {@link CloudUserService#emptyTrash}'s
     * own contract). No domain-specific failure is expected here (unlike {@link #handleDeleteFolder}
     * etc.), so an unmapped failure is printed to {@link System#err} and rethrown directly, the same
     * "make a silent 500 visible" fix already applied to {@link #folderFailureOrPropagate}/{@link
     * #notFoundOrPropagate}/{@link #unauthorizedOrPropagate}/{@link #registrationFailureOrPropagate}.
     */
    private void handleEmptyTrash(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.emptyTrash(userId))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                    System.err.println("[DefaultRestFactory] unmapped empty-trash failure for " + userId + ", returning 500:");
                    cause.printStackTrace();
                    throw cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
                }));
    }

    /**
     * {@code POST /folders/{id}/restore}: restores a trashed {@link Folder} via {@link
     * CloudUserService#restoreFolder}, which checks the caller owns it. Same status mapping as
     * {@link #handleRestoreFile}.
     */
    private void handleRestoreFolder(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.restoreFolder(userId, id))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, id);
                }));
    }

    /**
     * The {@code {"granteeEmail"}} JSON body shape read by {@code POST /files/{id}/share} and
     * {@code POST /folders/{id}/share} (item 9, file/folder sharing).
     *
     * @param granteeEmail the email address of the account to grant read access to
     */
    private record ShareRequest(String granteeEmail) {
    }

    /**
     * {@code POST /files/{id}/share}: grants {@code granteeEmail}'s account read-only access to
     * {@code id} via {@link CloudUserService#shareFile}, which checks the caller owns it. {@code
     * 204} on success, {@code 404} (via {@link #folderFailureOrPropagate}'s {@link
     * IllegalArgumentException} handling) if {@code id} isn't owned by the caller, is currently
     * trashed, or {@code granteeEmail} has no registered account.
     */
    private void handleShareFile(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final ShareRequest request = this.gson.fromJson(ctx.body(), ShareRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.shareFile(userId, id, request.granteeEmail()))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * {@code DELETE /files/{id}/share/{email}}: revokes a previously-granted share of {@code id}
     * from {@code email} via {@link CloudUserService#revokeFileShare}. {@code 204} on success
     * (idempotent - also {@code 204} if no such grant existed), {@code 404} if {@code id} isn't
     * owned by the caller or {@code email} has no registered account.
     */
    private void handleRevokeFileShare(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String email = ctx.pathParam("email");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.revokeFileShare(userId, id, email))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * {@code GET /files/{id}/share}: lists the email addresses of every account {@code id} is
     * currently shared with, via {@link CloudUserService#listFileShares} - owner-only, backing a
     * "who can see this file"/revoke UI. {@code 404} (via {@link #folderFailureOrPropagate}'s
     * {@link IllegalArgumentException} handling) if {@code id} isn't owned by the caller.
     */
    private void handleListFileShares(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listFileShares(userId, id))
                .handle((emails, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(emails));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, StoredFile.class, id);
                }));
    }

    /**
     * {@code GET /files/shared-with-me}: lists every file directly shared with the caller, as
     * {@link StoredFileSummary}s, via {@link CloudUserService#listSharedWithMe}. Does not include a
     * file only reachable through a folder-level share - see that method's own Javadoc.
     */
    private void handleListFilesSharedWithMe(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listSharedWithMe(userId))
                .thenAccept(summaries -> ctx.contentType("application/json").result(this.gson.toJson(summaries))));
    }

    /** Response shape of {@link #handleCountFilesSharedByMe} - a bare count, no other fields needed. */
    private record SharedByMeCountResponse(int count) {
    }

    /**
     * {@code GET /files/shared-by-me/count}: counts the caller's own distinct files that currently
     * have at least one active share, via {@link CloudUserService#countFilesSharedByMe} - the
     * owner-side counterpart to {@link #handleListFilesSharedWithMe}'s grantee-side listing.
     */
    private void handleCountFilesSharedByMe(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.countFilesSharedByMe(userId))
                .thenAccept(count -> ctx.contentType("application/json").result(this.gson.toJson(new SharedByMeCountResponse(count)))));
    }

    /**
     * {@code POST /folders/{id}/share}: grants {@code granteeEmail}'s account read-only access to
     * {@code id} and everything nested inside it via {@link CloudUserService#shareFolder}. Same
     * status mapping as {@link #handleShareFile}.
     */
    private void handleShareFolder(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final ShareRequest request = this.gson.fromJson(ctx.body(), ShareRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.shareFolder(userId, id, request.granteeEmail()))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, id);
                }));
    }

    /**
     * {@code DELETE /folders/{id}/share/{email}}: revokes a previously-granted share of {@code id}
     * from {@code email} via {@link CloudUserService#revokeFolderShare}. Same status mapping as
     * {@link #handleRevokeFileShare}. Does not affect a direct {@code /files/{id}/share} grant on a
     * file nested inside {@code id} - those must be revoked separately.
     */
    private void handleRevokeFolderShare(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String email = ctx.pathParam("email");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .runAsync(() -> this.cloudUserService.revokeFolderShare(userId, id, email))
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        ctx.status(204);
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, id);
                }));
    }

    /**
     * {@code GET /folders/{id}/share}: lists the email addresses of every account {@code id} is
     * currently shared with, via {@link CloudUserService#listFolderShares} - same shape as {@link
     * #handleListFileShares}.
     */
    private void handleListFolderShares(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listFolderShares(userId, id))
                .handle((emails, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(emails));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, id);
                }));
    }

    /**
     * {@code GET /folders/shared-with-me}: lists every folder directly shared with the caller via
     * {@link CloudUserService#listSharedFoldersWithMe}.
     */
    private void handleListFoldersSharedWithMe(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listSharedFoldersWithMe(userId))
                .thenAccept(folders -> ctx.contentType("application/json").result(this.gson.toJson(folders))));
    }

    /**
     * {@code GET /folders/{id}/shared-contents} (added 2026-09-02): lists the non-trashed
     * files/subfolders directly inside folder {@code id} via {@link
     * CloudUserService#listSharedFolderContents} - reachable by the folder's owner or anyone it's
     * shared with (directly or via an ancestor). {@code 404} (via {@link #folderFailureOrPropagate}'s
     * {@link IllegalArgumentException} handling) if {@code id} doesn't exist, is trashed, or isn't
     * owned by/shared with the caller.
     */
    private void handleListSharedFolderContents(@NotNull final Context ctx) {
        final String id = ctx.pathParam("id");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listSharedFolderContents(userId, id))
                .handle((contents, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(contents));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, Folder.class, id);
                }));
    }

    /** The {@code {"appleId", "password"}} JSON body shape read by {@code POST /icloud/import}. */
    private record StartIcloudImportRequest(String appleId, String password) {
    }

    /** The {@code {"code"}} JSON body shape read by {@code POST /icloud/import/{jobId}/confirm}. */
    private record ConfirmIcloudImportRequest(String code) {
    }

    /** The {@code {"jobId", "status", "filesImported", "totalFiles", "errorMessage"}} JSON shape mirroring {@link IcloudImportHandle}. */
    private record IcloudImportStatusResponse(String jobId, String status, int filesImported, int totalFiles, String errorMessage) {
        private static IcloudImportStatusResponse of(final IcloudImportHandle handle) {
            return new IcloudImportStatusResponse(handle.jobId(), handle.status().name(), handle.filesImported(), handle.totalFiles(), handle.errorMessage());
        }
    }

    /**
     * {@code POST /icloud/import}: starts a new on-demand iCloud import job via {@link
     * IcloudImportService#startImport} - see that interface's own Javadoc for why this is a
     * one-shot import, not a persistent sync. Returns immediately with the job's initial state;
     * the caller polls {@code GET /icloud/import/{jobId}/status} for progress.
     */
    private void handleStartIcloudImport(@NotNull final Context ctx) {
        final StartIcloudImportRequest request = this.gson.fromJson(ctx.body(), StartIcloudImportRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.icloudImportService.startImport(userId, request.appleId(), request.password().toCharArray()))
                .handle((handle, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(IcloudImportStatusResponse.of(handle)));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, IcloudImportHandle.class, request.appleId());
                }));
    }

    /**
     * {@code POST /icloud/import/{jobId}/confirm}: completes a job left waiting on Apple's
     * two-factor challenge via {@link IcloudImportService#confirmTwoFactor}, then proceeds into the
     * tree-walk-and-upload phase.
     */
    private void handleConfirmIcloudImportTwoFactor(@NotNull final Context ctx) {
        final String jobId = ctx.pathParam("jobId");
        final ConfirmIcloudImportRequest request = this.gson.fromJson(ctx.body(), ConfirmIcloudImportRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.icloudImportService.confirmTwoFactor(userId, jobId, request.code()))
                .handle((handle, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(IcloudImportStatusResponse.of(handle)));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, IcloudImportHandle.class, jobId);
                }));
    }

    /**
     * {@code GET /icloud/import/{jobId}/status}: returns a job's current state via {@link
     * IcloudImportService#getStatus}, for the desktop app's polling loop.
     */
    private void handleGetIcloudImportStatus(@NotNull final Context ctx) {
        final String jobId = ctx.pathParam("jobId");
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.icloudImportService.getStatus(userId, jobId))
                .handle((handle, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(IcloudImportStatusResponse.of(handle)));
                        return null;
                    }
                    throw folderFailureOrPropagate(failure, IcloudImportHandle.class, jobId);
                }));
    }

    /** The {@code {"exists"}} JSON response body returned by {@code GET /cloudUsers/exists}. */
    private record EmailExistsResponse(boolean exists) {
    }

    /**
     * {@code GET /cloudUsers/exists?email=<address>} (added 2026-09-02): bearer-gated like every
     * other {@code /cloudUsers} route, but not scoped to the caller's own account - it only ever
     * answers "does <em>any</em> account exist under this address", via {@link
     * CloudUserService#getCloudUserByEmail}. Backs the desktop app's Share dialog, which live-checks
     * a typed grantee address as the caller types rather than only finding out it's wrong once
     * {@code POST .../share} rejects it with {@link
     * de.lino.cloud.api.user.GranteeAccountNotFoundException}. Revealing existence here is
     * intentional for the same reason that exception's own translation is - the caller is already
     * an authenticated account holder, not an anonymous visitor, so this isn't the same
     * login-enumeration risk {@code AuthService#requestPasswordReset}'s own "don't leak" contract
     * guards against. A missing/blank {@code email} query parameter is treated as simply not
     * existing, rather than a {@code 400} - the caller's own debounced check already only fires for
     * a non-blank value, so this is purely a defensive fallback.
     */
    private void handleCheckCloudUserExists(@NotNull final Context ctx) {
        final String email = ctx.queryParam(EMAIL_QUERY_PARAM);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> email != null && !email.isBlank() && this.cloudUserService.getCloudUserByEmail(email).isPresent())
                .thenAccept(exists -> ctx.contentType("application/json").result(this.gson.toJson(new EmailExistsResponse(exists)))));
    }

    /** {@code POST path}  ->  create (DataFactory#registerAsync), dispatched off the Jetty worker thread. */
    private <T extends Serialized> void bindRegister(final JavalinConfig config, final String path, final Class<T> type) {
        config.routes.post(path, ctx -> {
            final T entity = this.parseOwnedBody(ctx, type);
            ctx.future(() -> this.dataFactory.registerAsync(entity).thenRun(() ->
                    ctx.status(201).contentType("application/json").result(this.gson.toJson(entity))));
        });
    }

    /**
     * {@code GET path/{id}} to fetch one, {@code GET path} to list all
     * (DataFactory#fetchAsync / #getEntitiesAsync), both dispatched off the
     * Jetty worker thread. When {@code type} implements {@link Owned} and
     * this instance is JWT-authenticated, a record belonging to another user
     * is hidden entirely (404 for the single-entity route, silently omitted
     * from the list) rather than exposed with a 403 - consistent with this
     * codebase's existing "don't confirm existence" convention (see {@code
     * AuthService#login}).
     */
    private <T extends Serialized> void bindFetch(final JavalinConfig config, final String path, final Class<T> type) {

        config.routes.get(path + "/{id}", ctx -> {
            final String id = ctx.pathParam("id");
            ctx.future(() -> this.dataFactory.fetchAsync(id, type).handle((entity, failure) -> {
                if (failure == null) {
                    if (!this.isOwnedByCaller(ctx, entity)) {
                        throw new NotFoundResponse("No " + type.getSimpleName() + " with id " + id);
                    }
                    ctx.contentType("application/json").result(this.gson.toJson(entity));
                    return null;
                }
                throw notFoundOrPropagate(failure, type, id);
            }));
        });

        config.routes.get(path, ctx -> ctx.future(() -> this.dataFactory.getEntitiesAsync(type).thenAccept(entities ->
                ctx.contentType("application/json").result(this.gson.toJson(this.filterOwnedByCaller(ctx, entities))))));
    }

    /**
     * {@code PUT path/{id}}  ->  update (DataFactory#updateAsync), dispatched
     * off the Jetty worker thread. When {@code type} implements {@link
     * Owned} and this instance is JWT-authenticated, the existing record's
     * owner is checked before the update is applied (404 if it belongs to
     * someone else), and the incoming body's owner is stamped to the caller
     * - see {@link #parseOwnedBody}.
     */
    private <T extends Serialized> void bindUpdate(final JavalinConfig config, final String path, final Class<T> type) {
        config.routes.put(path + "/{id}", ctx -> {
            final String id = ctx.pathParam("id");
            final T entity = this.parseOwnedBody(ctx, type);
            ctx.future(() -> this.authorizeExisting(ctx, id, type)
                    .thenCompose(ignored -> this.dataFactory.updateAsync(entity))
                    .handle((ignored, failure) -> {
                        if (failure == null) {
                            ctx.contentType("application/json").result(this.gson.toJson(entity));
                            return null;
                        }
                        throw notFoundOrPropagate(failure, type, id);
                    }));
        });
    }

    /**
     * {@code DELETE path/{id}}  ->  remove (DataFactory#deleteAsync),
     * dispatched off the Jetty worker thread. Same owner check as {@link
     * #bindUpdate} before the delete is applied.
     */
    private <T extends Serialized> void bindDelete(final JavalinConfig config, final String path, final Class<T> type) {
        config.routes.delete(path + "/{id}", ctx -> {
            final String id = ctx.pathParam("id");
            ctx.future(() -> this.authorizeExisting(ctx, id, type)
                    .thenCompose(ignored -> this.dataFactory.deleteAsync(id, type))
                    .handle((ignored, failure) -> {
                        if (failure == null) {
                            ctx.status(204);
                            return null;
                        }
                        throw notFoundOrPropagate(failure, type, id);
                    }));
        });
    }

    /**
     * Parses the request body into {@code type}. When this instance is
     * JWT-authenticated and {@code type} implements {@link Owned}, the
     * body's {@link #OWNER_ID_FIELD} is overwritten with the authenticated
     * caller's user id before deserializing - so a client can never write a
     * record under someone else's ownership, even by sending a spoofed
     * {@code ownerId} of its own.
     */
    private <T extends Serialized> T parseOwnedBody(@NotNull final Context ctx, final Class<T> type) {
        if (this.authService != null && Owned.class.isAssignableFrom(type)) {
            final JsonObject json = this.gson.fromJson(ctx.body(), JsonObject.class);
            json.addProperty(OWNER_ID_FIELD, requireUserId(ctx));
            return this.gson.fromJson(json, type);
        }
        return this.gson.fromJson(ctx.body(), type);
    }

    /**
     * Reports whether {@code entity} may be read/written by whoever this
     * request is authenticated as. {@code true} whenever there is no
     * ownership to check - not JWT-authenticated, or {@code entity} doesn't
     * implement {@link Owned} - so this is safe to call unconditionally.
     */
    private boolean isOwnedByCaller(@NotNull final Context ctx, @NotNull final Serialized entity) {
        if (this.authService == null || !(entity instanceof Owned owned)) {
            return true;
        }
        return owned.ownerId().equals(ctx.<String>attribute(USER_ID_ATTRIBUTE));
    }

    /** Filters {@code entities} down to the ones {@link #isOwnedByCaller} allows. */
    private <T extends Serialized> List<T> filterOwnedByCaller(@NotNull final Context ctx, final List<T> entities) {
        if (this.authService == null) {
            return entities;
        }
        return entities.stream().filter(entity -> this.isOwnedByCaller(ctx, entity)).toList();
    }

    /**
     * For an {@link Owned} type on a JWT-authenticated instance, fetches the
     * existing record under {@code id} and fails with {@link
     * NotFoundResponse} if it belongs to someone other than the caller - a
     * no-op, successfully-completed future otherwise (not JWT-authenticated,
     * or {@code type} isn't {@link Owned}).
     */
    private <T extends Serialized> CompletableFuture<Void> authorizeExisting(@NotNull final Context ctx, final String id, final Class<T> type) {
        if (this.authService == null || !Owned.class.isAssignableFrom(type)) {
            return CompletableFuture.completedFuture(null);
        }
        return this.dataFactory.fetchAsync(id, type).thenAccept(existing -> {
            if (!this.isOwnedByCaller(ctx, existing)) {
                throw new NotFoundResponse("No " + type.getSimpleName() + " with id " + id);
            }
        });
    }

    /**
     * Reads the authenticated caller's user id stashed by {@link #requireValidBearerToken}.
     *
     * @param ctx the request context
     * @return the validated user id
     * @throws UnauthorizedResponse if {@link #USER_ID_ATTRIBUTE} isn't set - should be unreachable behind {@link #requireValidBearerToken}
     */
    private static String requireUserId(@NotNull final Context ctx) {
        final String userId = ctx.attribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            throw new UnauthorizedResponse("Missing authenticated user context");
        }
        return userId;
    }

    /**
     * Unwraps a {@code DataFactory#*Async} (or {@link CloudUserService}) failure's {@link
     * CompletionException} and translates a {@link DatabaseClientException} - no such record
     * at all - or an {@link IllegalArgumentException} - a record that exists but doesn't
     * belong to the caller (see {@link CloudUserService#deleteFile}) - into {@link
     * NotFoundResponse} either way, so a caller can't distinguish "doesn't exist" from "isn't
     * yours" (the same "don't confirm existence" idiom {@link #isOwnedByCaller} already uses
     * for {@link Owned} entities). Any other cause is rethrown as-is to reach Javalin's
     * default (500) handling.
     *
     * <p>Any other cause is printed directly to {@link System#err} (bypassing this module's own
     * deliberately-silenced {@code slf4j-simple}/{@link JavalinLogger} logging, see {@link
     * #silenceJavalinLogging}) before being rethrown as-is to reach Javalin's default (500)
     * handling - without this, an unmapped cause here reaches the client as a bare {@code 500
     * Server Error} with zero trace of why on the server side, the same fix {@link
     * #registrationFailureOrPropagate} already applied.
     *
     * @param failure the raw failure from a {@code *Async} call
     * @param type the entity type being handled
     * @param id the entity id being handled
     * @return the exception to throw from the route handler
     */
    private static RuntimeException notFoundOrPropagate(final Throwable failure, final Class<?> type, final String id) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof DatabaseClientException || cause instanceof IllegalArgumentException) {
            return new NotFoundResponse("No " + type.getSimpleName() + " with id " + id);
        }
        CloudDriver.getInstance().getLogger().severe("@DefaultRestFactorynotFoundOrPropagate: unmapped " + type.getSimpleName() + " failure (id " + id + "), returning 500:");
        cause.printStackTrace();
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }

    /**
     * {@link #notFoundOrPropagate}, extended with two more cases: {@link IllegalStateException} -
     * a {@link CloudUserService} folder-operation validation failure that <em>does</em> confirm
     * the resource's existence rather than hiding it (unlike {@link IllegalArgumentException}'s
     * "not yours"/"doesn't exist" case above), since "this folder still has files in it" or "that
     * would create a cycle" are normal, expected client-facing feedback, not something to hide the
     * same way a missing/foreign record is - translated to {@link ConflictResponse} (409); and
     * {@link UploadQuotaExceededException} - translated to {@link ContentTooLargeResponse} (413),
     * the same status family Javalin's own {@code maxRequestSize} cap already uses, just scoped
     * per-account instead of per-request. Any other cause is printed directly to {@link
     * System#err} (bypassing this module's own deliberately-silenced {@code
     * slf4j-simple}/{@link JavalinLogger} logging, see {@link #silenceJavalinLogging}) before
     * being rethrown as-is to reach Javalin's default (500) handling - without this, an unmapped
     * cause from {@link #handleUploadFile}/{@link #handleMoveFile}/the folder handlers reached
     * the client as a bare {@code 500 Server Error} with zero trace of why on the server side;
     * confirmed missing (2026-09-01) while diagnosing exactly that against a real upload.
     *
     * @param failure the raw failure from a {@code *Async} call
     * @param type the entity type being handled
     * @param id the entity id being handled
     * @return the exception to throw from the route handler
     */
    private static RuntimeException folderFailureOrPropagate(final Throwable failure, final Class<?> type, final String id) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof de.lino.cloud.api.user.GranteeAccountNotFoundException granteeNotFound) {
            // Checked ahead of the plain IllegalArgumentException case below - it must never be
            // collapsed into that branch's generic "No <type> with id <id>" message, which would
            // misleadingly imply the file/folder itself is missing rather than the grantee address
            // being wrong (a real, confirmed bug - see this exception's own Javadoc).
            return new NotFoundResponse(granteeNotFound.getMessage());
        }
        if (cause instanceof IcloudAuthenticationException icloudAuthentication) {
            // Checked ahead of the plain IllegalArgumentException case below for the same reason as
            // GranteeAccountNotFoundException above - Apple rejecting the presented credentials/code
            // is a distinct, more specific failure than "no such job", not a 404.
            return new UnauthorizedResponse(icloudAuthentication.getMessage());
        }
        if (cause instanceof DatabaseClientException || cause instanceof IllegalArgumentException) {
            return new NotFoundResponse("No " + type.getSimpleName() + " with id " + id);
        }
        if (cause instanceof IllegalStateException illegalState) {
            return new ConflictResponse(illegalState.getMessage());
        }
        if (cause instanceof UploadQuotaExceededException uploadQuotaExceeded) {
            return new ContentTooLargeResponse(uploadQuotaExceeded.getMessage());
        }
        CloudDriver.getInstance().getLogger().severe("@DefaultRestFactory.folderFailureOrPropagate: unmapped " + type.getSimpleName() + " failure (id " + id + "), returning 500:");
        cause.printStackTrace();
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }

    /**
     * Unwraps an {@link AuthService#login}/{@link AuthService#refresh} failure's {@link
     * CompletionException} and translates {@link InvalidCredentialsException}/{@link
     * InvalidRefreshTokenException} into {@link UnauthorizedResponse}; any other cause is logged
     * (bypassing this module's own deliberately-silenced {@code slf4j-simple}/{@link
     * JavalinLogger} logging, see {@link #silenceJavalinLogging}) before being rethrown as-is to
     * reach Javalin's default (500) handling - same reasoning as {@link
     * #registrationFailureOrPropagate}/{@link #folderFailureOrPropagate}.
     */
    private static RuntimeException unauthorizedOrPropagate(final Throwable failure) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof InvalidCredentialsException invalidCredentials) {
            return new UnauthorizedResponse(invalidCredentials.getMessage());
        }
        if (cause instanceof InvalidRefreshTokenException invalidRefreshToken) {
            return new UnauthorizedResponse(invalidRefreshToken.getMessage());
        }
        CloudDriver.getInstance().getLogger().severe("@DefaultRestFactory.unauthorizedOrPropagate: unmapped login failure, returning 500:");
        cause.printStackTrace();
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }

    /**
     * Unwraps a {@link #handleRegister}/{@link #handleConfirmRegistration}/{@link
     * #handleRequestPasswordReset}/{@link #handleConfirmPasswordReset} failure's {@link
     * CompletionException} and translates {@link EmailAlreadyRegisteredException} into {@link
     * ConflictResponse} (409 - unlike login, confirming an email is already taken is normal,
     * expected signup-form feedback, not an enumeration risk), {@link
     * InvalidCredentialsException} - reused by {@link AuthService#register} for a syntactically
     * invalid email/undeliverable domain, not a wrong password here - into {@link
     * BadRequestResponse} (400), {@link InvalidPasswordFormatException} - thrown by {@link
     * AuthService#register}/{@link AuthService#confirmPasswordReset} when a caller-chosen
     * password doesn't meet the format requirement - into {@link BadRequestResponse} (400) as
     * well, and {@link InvalidVerificationCodeException} - thrown by both
     * {@link AuthService#confirmRegistration} and {@link AuthService#confirmPasswordReset} for a
     * missing/expired/mismatched code - into {@link BadRequestResponse} (400) as well; any other
     * cause is printed directly to {@link System#err} (bypassing this module's own
     * deliberately-silenced {@code slf4j-simple}/{@link JavalinLogger} logging, see {@link
     * #silenceJavalinLogging} - without this, an unmapped cause here, e.g. {@code
     * EmailDeliveryException} from a misconfigured SMTP server, reached the client as a bare
     * {@code 500 Server Error} with zero trace of why on the server side) before being rethrown
     * as-is to reach Javalin's default (500) handling. {@link #handleRequestPasswordReset} itself
     * never actually throws {@link InvalidVerificationCodeException}/{@link
     * EmailAlreadyRegisteredException} (it has no code to check and never rejects on existing
     * state) - it shares this helper purely to get the same unmapped-failure logging/500 fallback
     * as every other auth handler, not because those two branches are reachable from it.
     */
    private static RuntimeException registrationFailureOrPropagate(final Throwable failure) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof EmailAlreadyRegisteredException emailAlreadyRegistered) {
            return new ConflictResponse(emailAlreadyRegistered.getMessage());
        }
        if (cause instanceof InvalidCredentialsException invalidCredentials) {
            return new BadRequestResponse(invalidCredentials.getMessage());
        }
        if (cause instanceof InvalidPasswordFormatException invalidPasswordFormat) {
            return new BadRequestResponse(invalidPasswordFormat.getMessage());
        }
        if (cause instanceof InvalidVerificationCodeException invalidVerificationCode) {
            return new BadRequestResponse(invalidVerificationCode.getMessage());
        }
        CloudDriver.getInstance().getLogger().severe("@DefaultRestFactory.registrationFailureOrPropagate: unmapped registration failure, returning 500:");
        cause.printStackTrace();
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }
}
