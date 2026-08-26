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

    /** The running Javalin app, or {@code null} before {@link #start} / after {@link #stop}. */
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

    /** Builds the Javalin app from every route registered so far and starts listening on {@code port}. */
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

    /** Stops the running Javalin app, if any. Idempotent. */
    @Override
    public void stop() {
        if (this.app != null) {
            this.app.stop();
            this.app = null;
        }
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
     * Unwraps a {@code DataFactory#*Async} failure's {@link CompletionException} and
     * translates a {@link DatabaseClientException} into {@link NotFoundResponse}; any
     * other cause is rethrown as-is to reach Javalin's default (500) handling.
     *
     * @param failure the raw failure from a {@code *Async} call
     * @param type the entity type being handled
     * @param id the entity id being handled
     * @return the exception to throw from the route handler
     */
    private static RuntimeException notFoundOrPropagate(final Throwable failure, final Class<?> type, final String id) {
        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof DatabaseClientException) {
            return new NotFoundResponse("No " + type.getSimpleName() + " with id " + id);
        }
        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);
    }
}
