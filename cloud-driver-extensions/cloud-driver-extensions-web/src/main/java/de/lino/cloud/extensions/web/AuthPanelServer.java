package de.lino.cloud.extensions.web;

import com.google.gson.Gson;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.auth.AuthService;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * A small, self-contained Javalin server hosting the static browser panel
 * ({@code src/main/resources/public/index.html} - registration and login forms) alongside the
 * two API routes it calls, {@code POST /api/register} and {@code POST /api/login}.
 *
 * <p>Deliberately independent of {@code cloud-driver-plugin}'s {@code DefaultRestFactory} /
 * {@code cloud-driver-extensions-rest}'s already-running JWT-gated REST API: this listens on
 * its own port so it can be reverse-proxied under its own subdomain (e.g.
 * {@code auth.cloud-driver.de}) without needing CORS - the panel and the two routes it calls
 * are always same-origin. It is handed its own {@link AuthService}, built by
 * {@link CloudWebExtension} from the same {@code "jwt-signing-key"} configuration value the
 * main REST API uses, so a token minted here is verified the same way by both.
 *
 * <p><b>The panel is served from a plain {@code GET /} route reading raw classpath bytes,
 * deliberately not Javalin's own {@code config.staticFiles.add(..., Location.CLASSPATH)}.</b>
 * An extension jar is loaded through its own isolated {@code URLClassLoader} (see
 * {@code ExtensionJarLoader} - {@code cloud-driver-plugin}), but Javalin's static-file handler
 * resolves a {@code CLASSPATH} location via {@code ResourceFactory.of(this)} against Javalin's
 * <i>own</i> classloader (the shared/parent one, since {@code io.javalin.*} is already on the
 * host process's classpath and therefore never reloaded by an extension's child loader) - not
 * the caller's. That parent classloader has no visibility into this extension jar's own bundled
 * {@code public/index.html}, so {@code Location.CLASSPATH} throws {@code JavalinException:
 * Static resource directory with path: '/public' does not exist} even though the resource is
 * genuinely packaged in the jar. Reading it via {@code AuthPanelServer.class.getClassLoader()}
 * instead works, because that classloader <i>is</i> this extension's own child loader.
 *
 * <p><b>Open self-registration, by design.</b> {@link AuthService#register} is deliberately not
 * wired to any public HTTP route anywhere else in this codebase - see
 * {@link de.lino.cloud.api.jwt.auth.IAuthService}'s own Javadoc, which documents this exact
 * situation as the sanctioned exception: "unless the deployment explicitly wants open
 * self-registration". {@code POST /api/register} is that explicit choice, made here and nowhere
 * else in the codebase.
 */
public final class AuthPanelServer {

    private static final String INDEX_RESOURCE = "public/index.html";
    private static final String REGISTER_PATH = "/api/register";
    private static final String LOGIN_PATH = "/api/login";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final DataFactory dataFactory;
    private final AuthService authService;
    private final Gson gson = new Gson();
    private final byte[] indexHtml = loadIndexHtml();

    /** The running Javalin app, or {@code null} before {@link #start} / after {@link #stop}. */
    private volatile Javalin app;

    /**
     * @param dataFactory used only to check for an already-registered email address before
     *                    calling {@link AuthService#register} - see the class Javadoc
     * @param authService backs both routes
     */
    public AuthPanelServer(@NotNull final DataFactory dataFactory, @NotNull final AuthService authService) {
        this.dataFactory = Objects.requireNonNull(dataFactory, "@AuthPanelServer.init: dataFactory cannot be null");
        this.authService = Objects.requireNonNull(authService, "@AuthPanelServer.init: authService cannot be null");
    }

    /**
     * Starts serving the static panel (at {@code GET /}, see the class Javadoc for why this
     * isn't Javalin's own classpath static-file handler) and the two API routes on
     * {@code bindHost:port}. Calling this again while already running is a no-op - call
     * {@link #stop()} first.
     *
     * @param bindHost the interface to bind to
     * @param port     the port to listen on
     */
    public synchronized void start(@NotNull final String bindHost, final int port) {

        if (this.app != null) {
            return;
        }

        this.app = Javalin.create(config -> {
            config.routes.get("/", this::handleIndex);
            config.routes.post(REGISTER_PATH, this::handleRegister);
            config.routes.post(LOGIN_PATH, this::handleLogin);
        });

        this.app.start(bindHost, port);

    }

    /**
     * {@code GET /}: serves the panel's HTML, loaded once at construction time (see
     * {@link #loadIndexHtml()}) rather than re-read from the classpath on every request - it
     * never changes at runtime.
     *
     * @param ctx the current request context
     */
    private void handleIndex(@NotNull final Context ctx) {
        ctx.contentType("text/html; charset=utf-8").result(this.indexHtml);
    }

    /**
     * Reads {@value #INDEX_RESOURCE} via {@code AuthPanelServer.class}'s own classloader - see
     * the class Javadoc for why this, and not Javalin's {@code Location.CLASSPATH} static-file
     * handler, is what actually works once this class runs inside an extension jar's own
     * isolated {@code URLClassLoader}.
     *
     * @return the panel's raw HTML bytes
     * @throws IllegalStateException if {@value #INDEX_RESOURCE} isn't on the classpath
     */
    private static byte[] loadIndexHtml() {

        try (final InputStream resourceStream = AuthPanelServer.class.getClassLoader().getResourceAsStream(INDEX_RESOURCE)) {

            if (resourceStream == null) {
                throw new IllegalStateException("@AuthPanelServer.loadIndexHtml: classpath resource '" + INDEX_RESOURCE + "' not found");
            }

            return resourceStream.readAllBytes();

        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }

    }

    /** Stops the Javalin server. A no-op if not currently running. */
    public synchronized void stop() {

        final Javalin runningApp = this.app;

        if (runningApp == null) {
            return;
        }

        runningApp.stop();
        this.app = null;

    }

    /**
     * {@code POST /api/register}: reads {@code {"emailAddress", "password"}}, rejects an
     * already-registered email or an obviously-too-short password, otherwise registers the
     * account and immediately logs it in so the panel can display a ready-to-use token in one
     * step (mirroring {@code CreateUserCli}'s own register-then-login behavior).
     *
     * @param ctx the current request context
     */
    private void handleRegister(@NotNull final Context ctx) {

        final AuthRequest request = this.gson.fromJson(ctx.body(), AuthRequest.class);

        if (request == null || request.emailAddress() == null || request.emailAddress().isBlank()) {
            throw new BadRequestResponse("emailAddress is required");
        }

        if (request.password() == null || request.password().length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestResponse("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.registerAndLogin(request.emailAddress(), request.password().toCharArray()))
                .handle((token, failure) -> {
                    if (failure == null) {
                        ctx.status(201).contentType("application/json").result(this.gson.toJson(new AuthResponse(token)));
                        return null;
                    }
                    throw translateRegisterFailure(failure);
                }));

    }

    /**
     * Runs on {@link MultiTaskingFactory}'s virtual-thread executor (via {@link Context#future}
     * in {@link #handleRegister}), never on the Jetty worker thread - both
     * {@link AuthService#register} (Argon2id, deliberately slow) and the duplicate-email lookup
     * below do real work.
     *
     * <p><b>Known limitation:</b> the duplicate-email check and the actual
     * {@link AuthService#register} call are not atomic - two concurrent registrations for the
     * same address could both pass the check before either persists, since {@code AuthUser}'s
     * primary key is a random UUID, not the email address itself (see
     * {@link EmailAlreadyRegisteredException}'s own Javadoc). Acceptable for this panel's
     * expected load; a deployment expecting high concurrent signup volume would need a real
     * uniqueness constraint at the database layer instead.
     *
     * @param emailAddress the address to register
     * @param rawPassword  the chosen password
     * @return a signed JWT for the newly created account, from an immediate {@link AuthService#login}
     */
    private String registerAndLogin(final String emailAddress, final char[] rawPassword) {
        try {

            final boolean alreadyRegistered = this.dataFactory.getEntities(AuthUser.class).stream()
                    .anyMatch(candidate -> candidate.getEmailAddress().equalsIgnoreCase(emailAddress));

            if (alreadyRegistered) {
                throw new EmailAlreadyRegisteredException("An account for '" + emailAddress + "' already exists");
            }

            this.authService.register(emailAddress, rawPassword);
            return this.authService.login(emailAddress, rawPassword);

        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException exception) {
            throw new RuntimeException("@AuthPanelServer.registerAndLogin: failed to register '" + emailAddress + "'", exception);
        }
    }

    /**
     * {@code POST /api/login}: reads {@code {"emailAddress", "password"}}, returns a fresh JWT -
     * for a user whose earlier token (12h TTL, no refresh mechanism - see {@code CLAUDE.md}'s
     * "JWT authentication for end-user clients" section) has since expired.
     *
     * @param ctx the current request context
     */
    private void handleLogin(@NotNull final Context ctx) {

        final AuthRequest request = this.gson.fromJson(ctx.body(), AuthRequest.class);

        if (request == null || request.emailAddress() == null || request.password() == null) {
            throw new BadRequestResponse("emailAddress and password are required");
        }

        ctx.future(() -> MultiTaskingFactory.getInstance()
                .supplyAsync(() -> this.authService.login(request.emailAddress(), request.password().toCharArray()))
                .handle((token, failure) -> {
                    if (failure == null) {
                        ctx.contentType("application/json").result(this.gson.toJson(new AuthResponse(token)));
                        return null;
                    }
                    throw unauthorizedOrPropagate(failure);
                }));

    }

    /**
     * Unwraps a failure that reached here via {@link Context#future}'s async handling (always a
     * {@link CompletionException}), translating {@link EmailAlreadyRegisteredException} to
     * {@code 409} and {@link InvalidCredentialsException} (thrown by {@link AuthService#register}
     * for a malformed email/undeliverable domain) to {@code 400}; anything else propagates so
     * Javalin's default exception handling turns it into a {@code 500}.
     *
     * @param failure the failure {@link Context#future}'s handler observed
     * @return the {@link RuntimeException} to throw from the {@code handle} callback
     */
    private static RuntimeException translateRegisterFailure(final Throwable failure) {

        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;

        if (cause instanceof EmailAlreadyRegisteredException emailAlreadyRegisteredException) {
            return new ConflictResponse(emailAlreadyRegisteredException.getMessage());
        }

        if (cause instanceof InvalidCredentialsException invalidCredentialsException) {
            return new BadRequestResponse(invalidCredentialsException.getMessage());
        }

        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);

    }

    /**
     * Same unwrap-and-translate shape as {@link #translateRegisterFailure}, for
     * {@link #handleLogin}: an {@link InvalidCredentialsException} becomes {@code 401} rather
     * than leaking whether the email exists (see {@link InvalidCredentialsException}'s own
     * Javadoc).
     *
     * @param failure the failure {@link Context#future}'s handler observed
     * @return the {@link RuntimeException} to throw from the {@code handle} callback
     */
    private static RuntimeException unauthorizedOrPropagate(final Throwable failure) {

        final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;

        if (cause instanceof InvalidCredentialsException) {
            return new UnauthorizedResponse("invalid credentials");
        }

        return cause instanceof RuntimeException runtimeException ? runtimeException : new CompletionException(cause);

    }

    /**
     * The JSON body shape both {@code POST /api/register} and {@code POST /api/login} accept.
     *
     * @param emailAddress the account's email address
     * @param password     the account's password, in plaintext (over TLS - see the Caddy setup)
     */
    private record AuthRequest(String emailAddress, String password) {
    }

    /**
     * The JSON body shape both routes respond with on success.
     *
     * @param token a signed JWT, valid for 12h
     */
    private record AuthResponse(String token) {
    }

}
