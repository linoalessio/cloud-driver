package de.lino.cloud.api.factory;

import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.database.database.entity.Serialized;
import lombok.NonNull;
import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Exposes {@link Serialized} domain entities already reachable through a
 * {@link DataFactory} over a REST HTTP API - reached through {@code
 * CloudDriver#getFactoryContainer()}'s {@code getRestFactory()} (unauthenticated
 * by default there; an authenticated instance is constructed directly by a
 * caller that needs one, e.g. {@code CloudRestExtension}). This class itself
 * carries no Javalin dependency though - purely {@code DataFactory}/{@link
 * Serialized}/{@code MultiTaskingFactory}-based - which is exactly why it can
 * live in {@code cloud-driver-api} despite Javalin only ever appearing in the
 * {@code cloud-driver-plugin} implementation.
 *
 * <p>{@link #register}, {@link #fetch}, {@link #update}, {@link #delete},
 * {@link #findByPath}, {@link #getRegisteredPaths}, {@link #start}, and
 * {@link #stop} are abstract; every {@code *Async} variant below is
 * implemented here generically in terms of those, using {@code
 * DataFactory}'s own primitive names since each just wires the HTTP verb
 * that carries it out ({@code register} → {@code POST}, {@code fetch} →
 * {@code GET}, {@code update} → {@code PUT}, {@code delete} → {@code
 * DELETE}). All four must be called before {@link #start}.
 */
public abstract class RestFactory {

    /**
     * Mounts {@code POST path} for {@code type}: creates a new entity via
     * {@link DataFactory#register}, reading it from the request body.
     *
     * @param path the base path to mount {@code type} at, e.g. {@code "/notes"}
     * @param type the entity type to expose
     * @param <T> the entity type
     * @throws IllegalStateException if called after {@link #start}, or if {@code path} already has a {@code POST} handler
     */
    public abstract <T extends Serialized> void register(@NotNull String path, @NotNull Class<T> type);

    /**
     * Mounts {@code GET path/{id}} and {@code GET path} for {@code type}:
     * fetches one entity via {@link DataFactory#fetch}, or lists every
     * entity of {@code type} via {@link DataFactory#getEntities}.
     *
     * @param path the base path to mount {@code type} at, e.g. {@code "/notes"}
     * @param type the entity type to expose
     * @param <T> the entity type
     * @throws IllegalStateException if called after {@link #start}, or if {@code path} already has a {@code GET} handler
     */
    public abstract <T extends Serialized> void fetch(@NotNull String path, @NotNull Class<T> type);

    /**
     * Mounts {@code PUT path/{id}} for {@code type}: overwrites an existing
     * entity via {@link DataFactory#update}, reading it from the request body.
     *
     * @param path the base path to mount {@code type} at, e.g. {@code "/notes"}
     * @param type the entity type to expose
     * @param <T> the entity type
     * @throws IllegalStateException if called after {@link #start}, or if {@code path} already has a {@code PUT} handler
     */
    public abstract <T extends Serialized> void update(@NotNull String path, @NotNull Class<T> type);

    /**
     * Mounts {@code DELETE path/{id}} for {@code type}: removes an entity
     * via {@link DataFactory#delete}.
     *
     * @param path the base path to mount {@code type} at, e.g. {@code "/notes"}
     * @param type the entity type to expose
     * @param <T> the entity type
     * @throws IllegalStateException if called after {@link #start}, or if {@code path} already has a {@code DELETE} handler
     */
    public abstract <T extends Serialized> void delete(@NotNull String path, @NotNull Class<T> type);

    /**
     * Looks up which entity type, if any, is registered at {@code path}
     * under any operation.
     *
     * @param path the path to look up
     * @return the entity type registered at {@code path}, or {@link Optional#empty()} if none is
     */
    @NotNull
    public abstract Optional<Class<? extends Serialized>> findByPath(@NotNull String path);

    /**
     * Every path currently registered under any operation.
     *
     * @return the union of every path registered via {@link #register}, {@link #fetch}, {@link #update}, and {@link #delete}
     */
    @NotNull
    public abstract Collection<String> getRegisteredPaths();

    /**
     * Returns the end-user file-ownership service backing the {@code /files} routes, so a
     * caller mounting those routes can reach the same instance this factory's own handlers use.
     * The sole implementation, {@code DefaultRestFactory}, only ever holds a non-{@code null}
     * value here when constructed with one (its three-argument, JWT-authenticated constructor);
     * every other constructor leaves it {@code null} and mounts no {@code /files} routes at all,
     * despite the {@code @NonNull} annotation above having no effect on an abstract method with
     * no body to inject a null-check into.
     *
     * @return the {@link ICloudUserService}, or {@code null} if this instance mounts no {@code /files} routes
     */
    @NonNull
    public abstract ICloudUserService getCloudUserService();

    /**
     * Returns the JWT-authentication service backing this factory's own
     * {@code /auth/login}/{@code /auth/register}/{@code /auth/register/confirm} routes and its
     * {@code Authorization: Bearer} filter, so a caller mounting additional routes can reach the
     * same instance those handlers use. The sole implementation, {@code DefaultRestFactory}, only
     * holds a non-{@code null} value here when constructed with one (its two- or three-argument,
     * JWT-authenticated constructors); the unauthenticated and {@code ApiKey}-gated constructors
     * leave it {@code null}, despite the {@code @NonNull} annotation above having no effect on an
     * abstract method with no body to inject a null-check into.
     *
     * @return the {@link IAuthService}, or {@code null} if this instance is not JWT-authenticated
     */
    @NonNull
    public abstract IAuthService getAuthService();

    /**
     * Builds the underlying HTTP server from every route registered so far
     * and starts it listening on {@code host}:{@code port}. No further
     * {@code register}/{@code fetch}/{@code update}/{@code delete} calls are
     * accepted once this returns.
     *
     * <p>{@code host} matters beyond just "which interface": Javalin serves
     * plain HTTP, no TLS, so binding to {@code "0.0.0.0"} (every interface,
     * including public ones) means credentials/JWTs travel unencrypted to
     * anyone who can reach that port directly. Binding to {@code
     * "127.0.0.1"} instead makes the server reachable only from the same
     * machine - the intended shape once a TLS-terminating reverse proxy
     * (e.g. Caddy) sits in front, proxying its own public, HTTPS port to
     * this one locally. See the {@code cloud-driver-platform-app} section of
     * this repo's {@code CLAUDE.md} for the actual reverse-proxy setup this
     * is used with in production (configured directly on the deployment
     * server's own Caddyfile, which is not checked into this repo).
     *
     * @param host the interface to bind to, e.g. {@code "0.0.0.0"} (every interface) or {@code "127.0.0.1"} (loopback only)
     * @param port the port to listen on
     * @throws IllegalStateException if the server is already started
     */
    public abstract void start(@NotNull String host, int port);

    /**
     * {@link #start(String, int)}, binding to every interface ({@code "0.0.0.0"}) - the
     * original, pre-reverse-proxy default. Only appropriate for local development/testing
     * (plain HTTP reachable from anywhere that can reach this port) or for a deployment that
     * terminates TLS some other way before traffic ever reaches this process; a real internet-
     * facing deployment should call {@link #start(String, int)} with {@code "127.0.0.1"}
     * directly, behind a reverse proxy, instead.
     *
     * @param port the port to listen on
     * @throws IllegalStateException if the server is already started
     */
    public void start(final int port) {
        this.start("0.0.0.0", port);
    }

    /** Stops the underlying HTTP server started by {@link #start}. A no-op if never started. */
    public abstract void stop();

    /** Async counterpart of {@link #register(String, Class)}. */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> registerAsync(@NotNull final String path, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.register(path, type));
    }

    /** Async counterpart of {@link #fetch(String, Class)}. */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> fetchAsync(@NotNull final String path, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.fetch(path, type));
    }

    /** Async counterpart of {@link #update(String, Class)}. */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> updateAsync(@NotNull final String path, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.update(path, type));
    }

    /** Async counterpart of {@link #delete(String, Class)}. */
    @NotNull
    public <T extends Serialized> CompletableFuture<Void> deleteAsync(@NotNull final String path, @NotNull final Class<T> type) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.delete(path, type));
    }

    /** Async counterpart of {@link #start(int)}. */
    @NotNull
    public CompletableFuture<Void> startAsync(final int port) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.start(port));
    }

    /** Async counterpart of {@link #start(String, int)}. */
    @NotNull
    public CompletableFuture<Void> startAsync(@NotNull final String host, final int port) {
        return MultiTaskingFactory.getInstance().runAsync(() -> this.start(host, port));
    }

    /** Async counterpart of {@link #stop()}. */
    @NotNull
    public CompletableFuture<Void> stopAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::stop);
    }

}
