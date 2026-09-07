package de.lino.cloud.platform.rest.api.push;

import com.google.gson.Gson;
import de.lino.cloud.platform.rest.api.ApiClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Item 10 (live push via WebSocket/SSE for change notifications, see {@code
 * architecture/SERVICES.md}) - connects to the server's {@code /ws/updates} route and forwards
 * each pushed change notification to a {@link Listener}, so a long-running client (the desktop
 * app) can react to a change made from elsewhere (another device, a teammate sharing a file - see
 * item 9) instead of only ever refreshing on explicit user action.
 *
 * <p><b>A deliberate sibling class to {@link ApiClient}, not an extension of it.</b> {@code
 * ApiClient}'s whole documented shape is "one request, one response, blocking or {@code
 * CompletableFuture}-based" - a persistent, long-lived connection with its own reconnect-on-drop
 * state machine is a genuinely different lifecycle concern, and folding it into {@code ApiClient}
 * would mean that class's fields/constructor start carrying connection state that has nothing to
 * do with any individual HTTP call. Instead, this class is constructed around an already-built
 * {@link ApiClient} (via {@link ApiClient#httpClient()}/{@link ApiClient#apiBaseUrl()}/{@link
 * ApiClient#currentToken()}) and shares its {@link HttpClient} (connection pool, {@code HTTP_2}
 * negotiation) rather than standing up a second one - no new dependency needed, {@link
 * HttpClient#newWebSocketBuilder()} is built into {@code java.net.http}.
 *
 * <p>Authenticates by setting the {@code Authorization} header directly on the WebSocket
 * handshake request via {@link WebSocket.Builder#header(String, String)} - unlike a browser
 * client (which cannot set a custom header on a WebSocket handshake, hence {@code
 * DefaultRestFactory}'s server-side {@code ?token=} query-parameter fallback, see that class's
 * own Javadoc), a plain Java {@link HttpClient}-based client can, so the token never needs to
 * travel in the URL/connection logs for this client specifically.
 *
 * <p><b>Reconnects automatically on any drop</b> - required, not optional, since a desktop app
 * can stay open for hours; uses a simple fixed delay ({@link #RECONNECT_DELAY}) rather than
 * exponential backoff, matching this codebase's general "simple, not maximally clever" trade-off
 * for infrastructure like this (see {@code InternetConnectivityChecker}). Retries indefinitely
 * until {@link #close()} is called.
 */
public final class LiveUpdateClient implements AutoCloseable {

    /** Path this class connects its WebSocket to - mirrors {@code DefaultRestFactory.LIVE_UPDATES_PATH}. */
    private static final String LIVE_UPDATES_PATH = "/ws/updates";

    /** Delay before a reconnect attempt after any disconnect (clean or not) - see this class's own Javadoc. */
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

    private static final Gson GSON = new Gson();

    /**
     * One pushed change notification - mirrors {@code DatabaseWatchEvent}'s own notification
     * payload shape ({@code "table"}/{@code "operation"}/{@code "id"}), which {@code
     * DefaultRestFactory#publish} forwards verbatim as this message's JSON text frame.
     */
    public record Update(String table, String operation, String id) {
    }

    /** Receives {@link Update}s pushed over this connection, and is told about connect/disconnect transitions. */
    public interface Listener {
        /** Called for every {@link Update} pushed over the connection, on an internal HTTP-client thread - keep it cheap and non-blocking. */
        void onUpdate(Update update);

        /** Called once the WebSocket handshake completes successfully (including after a reconnect). No-op default - most callers only care about {@link #onUpdate}. */
        default void onConnected() {
        }

        /** Called after the connection drops, before a reconnect attempt is scheduled. No-op default. */
        default void onDisconnected() {
        }
    }

    private final ApiClient apiClient;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();

    public LiveUpdateClient(final ApiClient apiClient, final Listener listener) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient cannot be null");
        this.listener = Objects.requireNonNull(listener, "listener cannot be null");
    }

    /**
     * Opens the connection (asynchronously - does not block the calling thread). Requires {@link
     * ApiClient#currentToken()} to already carry a token (i.e. the caller must already be logged
     * in); a subsequent {@link #close()} is the only way to stop the reconnect loop this may
     * start.
     */
    public void connect() {
        if (this.closed.get()) {
            return;
        }
        final String token = this.apiClient.currentToken()
                .orElseThrow(() -> new IllegalStateException("@LiveUpdateClient.connect: not authenticated - ApiClient has no current token"));

        this.apiClient.httpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(this.resolveWebSocketUri(), new WsListener())
                .whenComplete((socket, failure) -> {
                    if (failure != null) {
                        this.scheduleReconnect();
                        return;
                    }
                    this.webSocket.set(socket);
                    this.listener.onConnected();
                });
    }

    /** Stops the reconnect loop and closes the current connection, if any. Idempotent. */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        final WebSocket socket = this.webSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
    }

    private void scheduleReconnect() {
        if (this.closed.get()) {
            return;
        }
        CompletableFuture.delayedExecutor(RECONNECT_DELAY.toMillis(), TimeUnit.MILLISECONDS, this.apiClient.executor())
                .execute(this::connect);
    }

    /**
     * Derives {@code ws://}/{@code wss://} + {@link #LIVE_UPDATES_PATH} from {@link
     * ApiClient#apiBaseUrl()}'s scheme/host/port, since the WebSocket handshake is otherwise a
     * plain HTTP upgrade against the exact same server.
     */
    private URI resolveWebSocketUri() {
        final URI base = this.apiClient.apiBaseUrl();
        final String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        try {
            return new URI(scheme, base.getUserInfo(), base.getHost(), base.getPort(), LIVE_UPDATES_PATH, null, null);
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("@LiveUpdateClient.resolveWebSocketUri: failed to build WebSocket URI from " + base, e);
        }
    }

    /**
     * Forwards text frames to {@link Listener#onUpdate}, and any disconnect (clean or not) to
     * {@link #scheduleReconnect()} - a {@link WebSocket.Listener} has no single "disconnected"
     * callback, so both {@link #onClose}/{@link #onError} funnel into the same private {@link
     * #handleDisconnect} to avoid scheduling two reconnect attempts for one drop.
     */
    private final class WsListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private final AtomicBoolean disconnectHandled = new AtomicBoolean(false);

        @Override
        public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
            this.buffer.append(data);
            webSocket.request(1);
            if (!last) {
                return null;
            }
            final String message = this.buffer.toString();
            this.buffer.setLength(0);
            try {
                LiveUpdateClient.this.listener.onUpdate(GSON.fromJson(message, Update.class));
            } catch (final RuntimeException ignored) {
                // A malformed push must never take down the connection - just drop this one message.
            }
            return null;
        }

        @Override
        public void onOpen(final WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
            this.handleDisconnect();
            return null;
        }

        @Override
        public void onError(final WebSocket webSocket, final Throwable error) {
            this.handleDisconnect();
        }

        private void handleDisconnect() {
            if (!this.disconnectHandled.compareAndSet(false, true)) {
                return;
            }
            LiveUpdateClient.this.webSocket.set(null);
            LiveUpdateClient.this.listener.onDisconnected();
            LiveUpdateClient.this.scheduleReconnect();
        }
    }

}
