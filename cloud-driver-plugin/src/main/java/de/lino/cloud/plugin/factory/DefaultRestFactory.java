package de.lino.cloud.plugin.factory;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.StoredFileSummary;
import de.lino.cloud.api.file.exception.UploadQuotaExceededException;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.InvalidPasswordFormatException;
import de.lino.cloud.api.jwt.InvalidVerificationCodeException;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.rest.ApiKey;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.api.utility.CursorPage;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.database.database.entity.Serialized;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.*;
import io.javalin.util.JavalinLogger;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
public final class DefaultRestFactory extends RestFactory {

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
    /** Path mounted by {@link #start} for {@link #handleRequestEmailChange} - bearer-gated, unlike the paths above: this changes an already-authenticated account's own address. */
    private static final String CHANGE_EMAIL_PATH = "/auth/change-email";
    /** Path mounted by {@link #start} for {@link #handleConfirmEmailChange} - bearer-gated, same reasoning as {@link #CHANGE_EMAIL_PATH}. */
    private static final String CHANGE_EMAIL_CONFIRM_PATH = "/auth/change-email/confirm";
    /** Path mounted by {@link #start} for {@link #handleUploadFile}/{@link #handleListFiles}/{@link #handleDownloadFile}/{@link #handleDeleteFile}. */
    private static final String FILES_PATH = "/files";
    /** Path mounted by {@link #start} for {@link #handleCreateFolder}/{@link #handleListFolders}/{@link #handleUpdateFolder}/{@link #handleDeleteFolder}. */
    private static final String FOLDERS_PATH = "/folders";
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
     * array carries that folder id under (merged in via {@link #toJsonArray} - {@link StoredFile}
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
        this.dataFactory = dataFactory;
        this.apiKey = null;
        this.authService = Objects.requireNonNull(authService, "@DefaultRestFactory.init: authService cannot be null");
        this.cloudUserService = cloudUserService;
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
                config.routes.post(LOGIN_PATH, this::handleLogin);
                config.routes.post(REGISTER_PATH, this::handleRegister);
                config.routes.post(REGISTER_CONFIRM_PATH, this::handleConfirmRegistration);
                config.routes.post(RESET_PASSWORD_PATH, this::handleRequestPasswordReset);
                config.routes.post(RESET_PASSWORD_CONFIRM_PATH, this::handleConfirmPasswordReset);
                config.routes.post(CHANGE_EMAIL_PATH, this::handleRequestEmailChange);
                config.routes.post(CHANGE_EMAIL_CONFIRM_PATH, this::handleConfirmEmailChange);
                config.routes.before(this::requireValidBearerToken);
            }

            if (this.cloudUserService != null) {
                config.routes.post(FILES_PATH, this::handleUploadFile);
                config.routes.get(FILES_PATH, this::handleListFiles);
                config.routes.get(FILES_PATH + "/{id}", this::handleDownloadFile);
                config.routes.get(FILES_PATH + "/{id}/content", this::handleDownloadFileContent);
                config.routes.delete(FILES_PATH + "/{id}", this::handleDeleteFile);
                config.routes.put(FILES_PATH + "/{id}/folder", this::handleMoveFile);

                config.routes.post(FOLDERS_PATH, this::handleCreateFolder);
                config.routes.get(FOLDERS_PATH, this::handleListFolders);
                config.routes.put(FOLDERS_PATH + "/{id}", this::handleUpdateFolder);
                config.routes.delete(FOLDERS_PATH + "/{id}", this::handleDeleteFolder);
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
     * #RESET_PASSWORD_CONFIRM_PATH} themselves - {@code LOGIN_PATH} is how a client obtains the
     * JWT this filter checks for in the first place, {@code REGISTER_PATH}/{@code
     * REGISTER_CONFIRM_PATH} together are how a client obtains an account before it has any JWT
     * at all, and {@code RESET_PASSWORD_PATH}/{@code RESET_PASSWORD_CONFIRM_PATH} together are
     * how a client recovers access to an account whose password it no longer has (so, by
     * definition, no valid JWT either) - all five must stay reachable without one. The token
     * itself is resolved by {@link #resolveBearerToken} (header, preferred,
     * or a query parameter fallback). Stores the validated user id as a
     * request attribute ({@link #USER_ID_ATTRIBUTE}) for the {@link
     * Owned}-scoping checks in {@link #bindRegister}/{@link #bindFetch}/
     * {@link #bindUpdate}/{@link #bindDelete} to read.
     *
     * @throws UnauthorizedResponse if no token is present, or it is malformed/invalid/expired
     */
    private void requireValidBearerToken(@NotNull final Context ctx) {
        if (LOGIN_PATH.equals(ctx.path()) || REGISTER_PATH.equals(ctx.path()) || REGISTER_CONFIRM_PATH.equals(ctx.path())
                || RESET_PASSWORD_PATH.equals(ctx.path()) || RESET_PASSWORD_CONFIRM_PATH.equals(ctx.path())) {
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
     * {@code DataFactory} lookup.
     */
    private void handleLogin(@NotNull final Context ctx) {
        final LoginRequest request = this.gson.fromJson(ctx.body(), LoginRequest.class);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.authService.login(request.username(), request.password().toCharArray()))
                .handle((token, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(new LoginResponse(token)));
                        return null;
                    }
                    throw unauthorizedOrPropagate(failure);
                }));
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
     * The {@code {"token"}} JSON response body returned by a successful login or completed
     * registration.
     *
     * @param token the signed JWT
     */
    private record LoginResponse(String token) {
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
                .handle((token, failure) -> {
                    if (failure == null) {
                        ctx.status(201).contentType("application/json").result(this.gson.toJson(new LoginResponse(token)));
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
                .handle((token, failure) -> {
                    if (failure == null) {
                        ctx.status(200).contentType("application/json").result(this.gson.toJson(new LoginResponse(token)));
                        return null;
                    }
                    throw registrationFailureOrPropagate(failure);
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
     * per-account instead of per-request. Any other cause is rethrown as-is to reach Javalin's
     * default (500) handling.
     *
     * @param failure the raw failure from a {@code *Async} call
     * @param type the entity type being handled
     * @param id the entity id being handled
     * @return the exception to throw from the route handler
     */
    private static RuntimeException folderFailureOrPropagate(final Throwable failure, final Class<?> type, final String id) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof DatabaseClientException || cause instanceof IllegalArgumentException) {
            return new NotFoundResponse("No " + type.getSimpleName() + " with id " + id);
        }
        if (cause instanceof IllegalStateException illegalState) {
            return new ConflictResponse(illegalState.getMessage());
        }
        if (cause instanceof UploadQuotaExceededException uploadQuotaExceeded) {
            return new ContentTooLargeResponse(uploadQuotaExceeded.getMessage());
        }
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }

    /**
     * Unwraps an {@link AuthService#login} failure's {@link CompletionException} and
     * translates {@link InvalidCredentialsException} into {@link UnauthorizedResponse}; any
     * other cause is rethrown as-is to reach Javalin's default (500) handling.
     */
    private static RuntimeException unauthorizedOrPropagate(final Throwable failure) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof InvalidCredentialsException) {
            return new UnauthorizedResponse("invalid credentials");
        }
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
        System.err.println("[DefaultRestFactory] unmapped registration failure, returning 500:");
        cause.printStackTrace();
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }
}
