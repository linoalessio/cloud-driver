package de.lino.cloud.plugin.factory;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.rest.ApiKey;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.api.file.StoredFile;
import de.lino.database.database.entity.Serialized;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.util.JavalinLogger;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String LOGIN_PATH = "/auth/login";
    private static final String FILES_PATH = "/files";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_QUERY_PARAM = "token";
    private static final String USER_ID_ATTRIBUTE = "userId";
    private static final String OWNER_ID_FIELD = "ownerId";

    private final DataFactory dataFactory;
    private final ApiKey apiKey;
    private final AuthService authService;
    @Getter
    private final CloudUserService cloudUserService;
    private final Gson gson = new Gson();

    private final Map<String, Class<? extends Serialized>> registerResources = Maps.newHashMap();
    private final Map<String, Class<? extends Serialized>> fetchResources = Maps.newHashMap();
    private final Map<String, Class<? extends Serialized>> updateResources = Maps.newHashMap();
    private final Map<String, Class<? extends Serialized>> deleteResources = Maps.newHashMap();

    /** The running Javalin app, or {@code null} before {@link #start} / after {@link #stop}. */
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
     * header, checked via {@code authService}, except {@code POST
     * /auth/login} itself - mounted automatically by this constructor -
     * which issues that JWT in the first place. Use this constructor
     * (instead of the {@link ApiKey} one) when the clients calling this API
     * are end users authenticating with a username/password, not another
     * service holding a static key. Any registered entity type implementing
     * {@link Owned} is additionally scoped to the authenticated caller - see
     * this class's own Javadoc.
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     * @param authService verifies login and issued JWTs; must not be {@code null}
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory, @NonNull final AuthService authService) {
        this(dataFactory, authService, null);
    }

    /**
     * Same as {@link #DefaultRestFactory(DataFactory, AuthService)}, additionally mounting
     * {@code POST /files}/{@code GET /files}/{@code DELETE /files/{id}} - each user's own
     * {@link StoredFile} uploads, backed by {@code cloudUserService}. Unlike {@link
     * #register}/{@link #fetch}/{@link #update}/{@link #delete}, these three routes are
     * not generic over a {@code (path, type)} pair registered separately - they're fixed,
     * mounted directly by this constructor, since uploading/listing/deleting a user's own
     * files is business logic ({@link CloudUserService}), not a plain {@code DataFactory}
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
     * Builds the Javalin app from every route registered so far and starts listening on
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

            if (this.apiKey != null) {
                config.routes.before(this::requireValidApiKey);
            }

            if (this.authService != null) {
                config.routes.post(LOGIN_PATH, this::handleLogin);
                config.routes.before(this::requireValidBearerToken);
            }

            if (this.cloudUserService != null) {
                config.routes.post(FILES_PATH, this::handleUploadFile);
                config.routes.get(FILES_PATH, this::handleListFiles);
                config.routes.delete(FILES_PATH + "/{id}", this::handleDeleteFile);
            }

            this.registerResources.forEach((path, type) -> this.bindRegister(config, path, type));
            this.fetchResources.forEach((path, type) -> this.bindFetch(config, path, type));
            this.updateResources.forEach((path, type) -> this.bindUpdate(config, path, type));
            this.deleteResources.forEach((path, type) -> this.bindDelete(config, path, type));
        });

        this.app.start(host, port);
    }

    /** Stops the running Javalin app, if any. Idempotent. */
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
     *     here - right before {@link #start} builds the app - always takes effect.</li>
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
     * Gates every route behind a valid JWT, except {@link #LOGIN_PATH} itself
     * - that route is how a client obtains the JWT this filter checks for in
     * the first place, so it must stay reachable without one. The token
     * itself is resolved by {@link #resolveBearerToken} (header, preferred,
     * or a query parameter fallback). Stores the validated user id as a
     * request attribute ({@link #USER_ID_ATTRIBUTE}) for the {@link
     * Owned}-scoping checks in {@link #bindRegister}/{@link #bindFetch}/
     * {@link #bindUpdate}/{@link #bindDelete} to read.
     *
     * @throws UnauthorizedResponse if no token is present, or it is malformed/invalid/expired
     */
    private void requireValidBearerToken(@NotNull final Context ctx) {
        if (LOGIN_PATH.equals(ctx.path())) {
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

    private record LoginRequest(String username, String password) {
    }

    private record LoginResponse(String token) {
    }

    /**
     * {@code POST /files}: reads {@code {"fileName": ..., "contentBase64": ...}} from the
     * request body and uploads it via {@link CloudUserService#uploadFile}, tracked under
     * the caller's own user id (from {@link #USER_ID_ATTRIBUTE}, set by {@link
     * #requireValidBearerToken}). Dispatched off the Jetty worker thread since {@code
     * uploadFile} does real database/encryption I/O.
     */
    private void handleUploadFile(@NotNull final Context ctx) {
        final UploadFileRequest request = this.gson.fromJson(ctx.body(), UploadFileRequest.class);
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.uploadFile(
                        userId, request.fileName(), Base64.getDecoder().decode(request.contentBase64())))
                .handle((storedFile, failure) -> {
                    if (failure == null) {
                        ctx.status(201).contentType("application/json").result(this.gson.toJson(storedFile));
                        return null;
                    }
                    throw notFoundOrPropagate(failure, StoredFile.class, request.fileName());
                }));
    }

    /**
     * {@code GET /files}: lists every {@link StoredFile} tracked as belonging to the
     * caller, via {@link CloudUserService#listFiles}.
     */
    private void handleListFiles(@NotNull final Context ctx) {
        final String userId = requireUserId(ctx);
        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.cloudUserService.listFiles(userId))
                .thenAccept(files -> ctx.contentType("application/json").result(this.gson.toJson(files))));
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

    private record UploadFileRequest(String fileName, String contentBase64) {
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

    /** @throws UnauthorizedResponse if {@link #USER_ID_ATTRIBUTE} isn't set - should be unreachable behind {@link #requireValidBearerToken}. */
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
}
