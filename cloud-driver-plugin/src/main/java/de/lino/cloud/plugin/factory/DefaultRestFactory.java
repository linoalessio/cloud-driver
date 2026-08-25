package de.lino.cloud.plugin.factory;

import com.google.gson.Gson;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.rest.ApiKey;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.database.database.entity.Serialized;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RestFactory} backed by <a href="https://javalin.io">Javalin</a>:
 * each of {@link #register}/{@link #fetch}/{@link #update}/{@link #delete}
 * wires exactly one HTTP verb for a {@code (path, type)} pair directly
 * onto a {@link DataFactory} - Javalin only ever serializes/deserializes
 * JSON at the edge, {@code DataFactory} still does the actual envelope-
 * encrypted persistence. Every operation is
 * tracked in its own {@link ConcurrentHashMap} registry, keyed by path -
 * the same "safe to register from multiple threads without external
 * synchronization, {@code putIfAbsent} for atomic duplicate detection"
 * shape {@link DefaultExtensionFactory} uses for its own registry, and
 * deliberately not {@code database-driver-api}'s {@code Cache} the way
 * {@link DefaultEventFactory}/{@code InMemoryPendingUploadCache} store
 * theirs: those benefit from {@code Cache}'s async, stampede-protected
 * loader (constructing a class reflectively) or its amortized-scale
 * put/invalidate/snapshot characteristics for a queue that can grow large;
 * a handful of REST paths registered once at startup and never constructed
 * on demand needs neither. A path can carry any subset of the four
 * operations (e.g. {@code fetch} + {@code delete} for a read-and-remove-
 * only resource). Routes are only assembled once {@link #start} is called,
 * from every registry as it stands at that point - see {@link
 * RestFactory}'s Javadoc for why.
 *
 * <p>Each route handler reads/writes the request synchronously (parsing
 * the body, path params) but hands the actual {@link DataFactory} call to
 * its {@code *Async} counterpart, wired through {@link Context#future},
 * so a Jetty worker thread is never blocked on the encryption/database I/O
 * {@link DataFactory} performs - the same reasoning {@link
 * MultiTaskingFactory} exists for everywhere else in this codebase,
 * applied at the one place this class does I/O inside a request handler.
 *
 * <p>Optionally gates every route behind a static API key, checked in a
 * Javalin {@code before} filter against the {@code X-API-Key} header - see
 * {@link ApiKey}. The single-argument constructor leaves every
 * route open; only use it for local development, never for anything
 * reachable off of {@code localhost}.
 */
public final class DefaultRestFactory extends RestFactory {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final DataFactory dataFactory;
    private final ApiKey apiKey;
    private final Gson gson = new Gson();

    private final Map<String, Class<? extends Serialized>> registerResources = new ConcurrentHashMap<>();
    private final Map<String, Class<? extends Serialized>> fetchResources = new ConcurrentHashMap<>();
    private final Map<String, Class<? extends Serialized>> updateResources = new ConcurrentHashMap<>();
    private final Map<String, Class<? extends Serialized>> deleteResources = new ConcurrentHashMap<>();

    private volatile Javalin app;

    /**
     * Every route is left open - no API-key check at all. Only appropriate
     * for local development; use {@link #DefaultRestFactory(DataFactory, ApiKey)}
     * for anything that leaves {@code localhost}.
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory) {
        this(dataFactory, null);
    }

    /**
     * Every route requires a valid {@code X-API-Key} header, checked
     * against {@code apiKey}.
     *
     * @param dataFactory the {@link DataFactory} every registered resource is backed by
     * @param apiKey checks the {@code X-API-Key} header on every request, or {@code null} to leave every route open
     */
    public DefaultRestFactory(@NonNull final DataFactory dataFactory, @Nullable final ApiKey apiKey) {
        this.dataFactory = dataFactory;
        this.apiKey = apiKey;
    }

    @Override
    public <T extends Serialized> void register(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.registerResources, path, type, "register");
    }

    @Override
    public <T extends Serialized> void fetch(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.fetchResources, path, type, "fetch");
    }

    @Override
    public <T extends Serialized> void update(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.updateResources, path, type, "update");
    }

    @Override
    public <T extends Serialized> void delete(@NonNull final String path, @NonNull final Class<T> type) {
        this.registerOperation(this.deleteResources, path, type, "delete");
    }

    /**
     * Shared registration primitive backing {@link #register}/{@link
     * #fetch}/{@link #update}/{@link #delete}: {@link Map#putIfAbsent}
     * makes the duplicate-path check atomic - never a {@code containsKey}-
     * then-{@code put} pair, which would leave a race window between two
     * threads registering the same path concurrently.
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

    @Override
    public void start(final int port) {
        if (this.app != null) {
            throw new IllegalStateException("@DefaultRestFactory.start: already started");
        }

        this.app = Javalin.create(config -> {

            if (this.apiKey != null) {
                config.routes.before(this::requireValidApiKey);
            }

            this.registerResources.forEach((path, type) -> this.bindRegister(config, path, type));
            this.fetchResources.forEach((path, type) -> this.bindFetch(config, path, type));
            this.updateResources.forEach((path, type) -> this.bindUpdate(config, path, type));
            this.deleteResources.forEach((path, type) -> this.bindDelete(config, path, type));
        });

        this.app.start(port);
    }

    @Override
    public void stop() {
        if (this.app != null) {
            this.app.stop();
            this.app = null;
        }
    }

    private void requireValidApiKey(@NotNull final Context ctx) {
        final String providedKey = ctx.header(API_KEY_HEADER);
        if (providedKey == null || providedKey.isBlank()) {
            throw new UnauthorizedResponse("Missing " + API_KEY_HEADER + " header");
        }
        if (!this.apiKey.isValid(providedKey)) {
            throw new UnauthorizedResponse("Invalid " + API_KEY_HEADER);
        }
    }

    /** {@code POST path}  ->  create (DataFactory#registerAsync), dispatched off the Jetty worker thread. */
    private <T extends Serialized> void bindRegister(final JavalinConfig config, final String path, final Class<T> type) {
        config.routes.post(path, ctx -> {
            final T entity = this.gson.fromJson(ctx.body(), type);
            ctx.future(() -> this.dataFactory.registerAsync(entity).thenRun(() ->
                    ctx.status(201).contentType("application/json").result(this.gson.toJson(entity))));
        });
    }

    /**
     * {@code GET path/{id}} to fetch one, {@code GET path} to list all
     * (DataFactory#fetchAsync / #getEntitiesAsync), both dispatched off the
     * Jetty worker thread.
     */
    private <T extends Serialized> void bindFetch(final JavalinConfig config, final String path, final Class<T> type) {

        config.routes.get(path + "/{id}", ctx -> {
            final String id = ctx.pathParam("id");
            ctx.future(() -> this.dataFactory.fetchAsync(id, type).handle((entity, failure) -> {
                if (failure == null) {
                    ctx.contentType("application/json").result(this.gson.toJson(entity));
                    return null;
                }
                throw notFoundOrPropagate(failure, type, id);
            }));
        });

        config.routes.get(path, ctx -> ctx.future(() -> this.dataFactory.getEntitiesAsync(type).thenAccept(entities ->
                ctx.contentType("application/json").result(this.gson.toJson(entities)))));
    }

    /** {@code PUT path/{id}}  ->  update (DataFactory#updateAsync), dispatched off the Jetty worker thread. */
    private <T extends Serialized> void bindUpdate(final JavalinConfig config, final String path, final Class<T> type) {
        config.routes.put(path + "/{id}", ctx -> {
            final String id = ctx.pathParam("id");
            final T entity = this.gson.fromJson(ctx.body(), type);
            ctx.future(() -> this.dataFactory.updateAsync(entity).handle((ignored, failure) -> {
                if (failure == null) {
                    ctx.contentType("application/json").result(this.gson.toJson(entity));
                    return null;
                }
                throw notFoundOrPropagate(failure, type, id);
            }));
        });
    }

    /** {@code DELETE path/{id}}  ->  remove (DataFactory#deleteAsync), dispatched off the Jetty worker thread. */
    private <T extends Serialized> void bindDelete(final JavalinConfig config, final String path, final Class<T> type) {
        config.routes.delete(path + "/{id}", ctx -> {
            final String id = ctx.pathParam("id");
            ctx.future(() -> this.dataFactory.deleteAsync(id, type).handle((ignored, failure) -> {
                if (failure == null) {
                    ctx.status(204);
                    return null;
                }
                throw notFoundOrPropagate(failure, type, id);
            }));
        });
    }

    /**
     * Every {@code DataFactory#*Async} failure arrives here wrapped in a
     * {@link CompletionException} (see e.g. {@link DataFactory#fetchAsync}'s
     * Javadoc); unwrap it and translate a {@link DatabaseClientException} -
     * "no such record" - into {@link NotFoundResponse}, the same mapping
     * the pre-{@code RestFactory} hand-wired sample used. Anything else
     * (a real {@code KeyWrapException}/{@code AuthenticationFailedException}
     * failure) is rethrown as-is so it still reaches Javalin's default
     * exception handling (500), unchanged from before this class wired
     * requests through {@link Context#future} instead of a blocking call.
     */
    private static RuntimeException notFoundOrPropagate(final Throwable failure, final Class<?> type, final String id) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof DatabaseClientException) {
            return new NotFoundResponse("No " + type.getSimpleName() + " with id " + id);
        }
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }
}
