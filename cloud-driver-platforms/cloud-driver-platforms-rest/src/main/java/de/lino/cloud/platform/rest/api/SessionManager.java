package de.lino.cloud.platform.rest.api;

import de.lino.cloud.platform.rest.api.ApiClient.ApiException;
import de.lino.cloud.platform.rest.api.session.TokenStore;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Ties {@link ApiClient} to a {@link TokenStore} so a session survives an desktop restart, and
 * centralizes the "token expired mid-use" handling: {@code cloud-driver} issues 12h access JWTs,
 * refreshable via a longer-lived refresh token (see {@code IAuthService#refresh} server-side) -
 * {@link ApiClient} already retries an authenticated call transparently once, via that refresh
 * token, on a {@code 401} (see {@link ApiClient#send(java.net.http.HttpRequest, java.lang.reflect.Type)}'s
 * own Javadoc), so a caller here only ever observes {@link #handleFailure(ApiException)}'s reaction
 * once <em>that</em> automatic retry has also failed - meaning the refresh token itself is no
 * longer usable either, and only a fresh login can recover the session.
 *
 * <p>Every method here has an {@code *Async} counterpart built directly on {@link ApiClient}'s
 * own async methods, chained via {@link ApiClient#executor()} - so a {@link TokenStore}
 * implementation's blocking OS call (shelling out to {@code security}/{@code secret-tool}/
 * PowerShell, see that interface's implementations) always runs on {@link ApiClient}'s dedicated
 * virtual-thread executor rather than an internal JDK HTTP-client thread. Like {@link ApiClient}
 * itself, a checked {@link TokenStoreException} raised while completing an async chain surfaces
 * wrapped in a {@link CompletionException} - unwrap it the same way the sync methods' own
 * {@code throws} clauses would otherwise require.
 */
public final class SessionManager {

    private final ApiClient apiClient;
    private final TokenStore tokenStore;

    public SessionManager(final ApiClient apiClient, final TokenStore tokenStore) {
        this.apiClient = apiClient;
        this.tokenStore = tokenStore;
    }

    /** @return the underlying {@link ApiClient} for making authenticated calls once logged in */
    public ApiClient api() {
        return this.apiClient;
    }

    /**
     * The access/refresh token pair {@link TokenStore} persists as one opaque value, since {@link
     * TokenStore#save(String)}/{@link TokenStore#load()} only ever carry a single {@link String} -
     * changing that interface's shape would touch every OS-specific implementation ({@code
     * MacKeychainTokenStore}/{@code WindowsDpapiTokenStore}/{@code LinuxSecretServiceTokenStore}/
     * {@code FileTokenStore}) for no real benefit, so this class encodes/decodes the pair into one
     * string instead. {@link #encode()}/{@link #decode(String)} use a newline delimiter - safe
     * since neither a JWT (base64url segments joined by {@code .}) nor a refresh token (a single
     * base64url string, see {@code RefreshToken} server-side) can ever contain one.
     *
     * @param accessToken the short-lived access JWT
     * @param refreshToken the longer-lived refresh token
     */
    private record StoredSession(String accessToken, String refreshToken) {

        private static final String DELIMITER = "\n";

        private String encode() {
            return this.accessToken + DELIMITER + this.refreshToken;
        }

        /**
         * @return the decoded pair, or {@link Optional#empty()} if {@code raw} doesn't carry the
         * delimiter at all (e.g. a bare access token persisted by a session predating this
         * feature) or either half is blank - either way, there is no refresh-capable session
         * worth restoring, and the caller should fall back to a fresh login instead.
         */
        private static Optional<StoredSession> decode(final String raw) {
            final int delimiterIndex = raw.indexOf(DELIMITER);
            if (delimiterIndex < 0) {
                return Optional.empty();
            }
            final String accessToken = raw.substring(0, delimiterIndex);
            final String refreshToken = raw.substring(delimiterIndex + DELIMITER.length());
            if (accessToken.isBlank() || refreshToken.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new StoredSession(accessToken, refreshToken));
        }
    }

    /**
     * Call once at desktop startup. Loads a previously persisted access/refresh token pair (if
     * any) and verifies the access token is still accepted by the server with a single
     * lightweight authenticated call ({@code GET /files}) - there's no dedicated "whoami"
     * endpoint, so this doubles as that check. If the access token has since expired, {@link
     * ApiClient} transparently refreshes it via the persisted refresh token as part of that same
     * call (see the class Javadoc) - this method does not need its own refresh-handling logic.
     *
     * @return {@code true} if a valid session was restored and the desktop can go straight to the
     * main screen; {@code false} if there was no stored session, it didn't carry a usable refresh
     * token, or neither token is valid anymore - either way, show the login screen
     */
    public boolean tryRestoreSession() throws TokenStoreException {
        final Optional<StoredSession> session = this.tokenStore.load().flatMap(StoredSession::decode);
        if (session.isEmpty()) {
            return false;
        }

        this.apiClient.restoreSession(session.get().accessToken(), session.get().refreshToken());
        try {
            this.apiClient.listFiles();
            return true;
        } catch (final ApiException probeFailed) {
            this.handleFailure(probeFailed);
            return false;
        }
    }

    /** Async form of {@link #tryRestoreSession()} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Boolean> tryRestoreSessionAsync() {
        return CompletableFuture
                .supplyAsync(this::loadStoredSessionOrThrow, this.apiClient.executor())
                .thenCompose(session -> session.map(this::probeRestoredSessionAsync).orElseGet(() -> CompletableFuture.completedFuture(Boolean.FALSE)));
    }

    private Optional<StoredSession> loadStoredSessionOrThrow() {
        try {
            return this.tokenStore.load().flatMap(StoredSession::decode);
        } catch (final TokenStoreException e) {
            throw new CompletionException(e);
        }
    }

    private CompletableFuture<Boolean> probeRestoredSessionAsync(final StoredSession session) {
        this.apiClient.restoreSession(session.accessToken(), session.refreshToken());
        return this.apiClient.listFilesAsync().handleAsync((ignored, error) -> {
            if (error == null) {
                return Boolean.TRUE;
            }
            final Throwable cause = error instanceof CompletionException completionException
                    ? completionException.getCause() : error;
            if (cause instanceof ApiException apiException) {
                this.handleFailureQuietly(apiException);
            }
            return Boolean.FALSE;
        }, this.apiClient.executor());
    }

    /** {@code POST /auth/login}, then persists the resulting access/refresh token pair. */
    public void login(final String emailAddress, final String password) throws ApiException, TokenStoreException {
        this.apiClient.login(emailAddress, password);
        this.tokenStore.save(this.encodeCurrentSessionOrThrow());
    }

    /** Async form of {@link #login} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> loginAsync(final String emailAddress, final String password) {
        return this.apiClient.loginAsync(emailAddress, password)
                .thenApplyAsync(this::persistCurrentSessionOrThrow, this.apiClient.executor());
    }

    /**
     * {@code POST /auth/register} - step one of registration, e-mails a verification code. Does
     * not create an account and leaves no session to persist; call {@link #confirmRegistration}
     * with the e-mailed code to actually create the account.
     */
    public void register(final String emailAddress, final String password) throws ApiException {
        this.apiClient.register(emailAddress, password);
    }

    /** Async form of {@link #register} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> registerAsync(final String emailAddress, final String password) {
        return this.apiClient.registerAsync(emailAddress, password).thenApply(ignored -> null);
    }

    /** {@code POST /auth/register/confirm} (step two - creates the account and logs it in), then persists the resulting access/refresh token pair. */
    public void confirmRegistration(final String emailAddress, final String code) throws ApiException, TokenStoreException {
        this.apiClient.confirmRegistration(emailAddress, code);
        this.tokenStore.save(this.encodeCurrentSessionOrThrow());
    }

    /** Async form of {@link #confirmRegistration} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> confirmRegistrationAsync(final String emailAddress, final String code) {
        return this.apiClient.confirmRegistrationAsync(emailAddress, code)
                .thenApplyAsync(this::persistCurrentSessionOrThrow, this.apiClient.executor());
    }

    /** {@code POST /auth/reset-password/confirm} (step two - replaces the password and logs it in), then persists the resulting access/refresh token pair. */
    public void confirmPasswordReset(final String emailAddress, final String code, final String newPassword) throws ApiException, TokenStoreException {
        this.apiClient.confirmPasswordReset(emailAddress, code, newPassword);
        this.tokenStore.save(this.encodeCurrentSessionOrThrow());
    }

    /** Async form of {@link #confirmPasswordReset} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> confirmPasswordResetAsync(final String emailAddress, final String code, final String newPassword) {
        return this.apiClient.confirmPasswordResetAsync(emailAddress, code, newPassword)
                .thenApplyAsync(this::persistCurrentSessionOrThrow, this.apiClient.executor());
    }

    /** @return {@link #apiClient}'s currently held access/refresh token pair, encoded via {@link StoredSession#encode()} */
    private String encodeCurrentSessionOrThrow() {
        final String accessToken = this.apiClient.currentToken()
                .orElseThrow(() -> new IllegalStateException("@SessionManager: no access token held after a successful auth call"));
        final String refreshToken = this.apiClient.currentRefreshToken()
                .orElseThrow(() -> new IllegalStateException("@SessionManager: no refresh token held after a successful auth call"));
        return new StoredSession(accessToken, refreshToken).encode();
    }

    private Void persistCurrentSessionOrThrow(final Object ignoredAuthCallResult) {
        try {
            this.tokenStore.save(this.encodeCurrentSessionOrThrow());
        } catch (final TokenStoreException e) {
            throw new CompletionException(e);
        }
        return null;
    }

    /**
     * Ends the session both in memory and in persisted storage - first revoking the held refresh
     * token server-side (see {@link ApiClient#revokeRefreshToken()}), best-effort: a failed
     * revoke (e.g. no network) never blocks clearing the local session, since the caller is
     * logging out regardless and there is nothing more useful to do with that failure here.
     */
    public void logout() throws TokenStoreException {
        try {
            this.apiClient.revokeRefreshToken();
        } catch (final ApiException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
        this.apiClient.logout();
        this.tokenStore.clear();
    }

    /** Async form of {@link #logout} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> logoutAsync() {
        return this.apiClient.revokeRefreshTokenAsync()
                .exceptionally(ignored -> null)
                .thenComposeAsync(ignored -> {
                    this.apiClient.logout();
                    return CompletableFuture.runAsync(() -> {
                        try {
                            this.tokenStore.clear();
                        } catch (final TokenStoreException e) {
                            throw new CompletionException(e);
                        }
                    }, this.apiClient.executor());
                }, this.apiClient.executor());
    }

    /**
     * Reacts to a failed authenticated call. On {@code 401} (expired/invalid token - by the time
     * a caller observes this, {@link ApiClient}'s own transparent refresh-and-retry has already
     * failed too, see the class Javadoc), clears both the in-memory and persisted session, since a
     * stale token pair is worthless to keep around. Every other failure (network error, 404, 500,
     * ...) is left untouched - it's a real error the caller should still show to the user, not a
     * reason to log them out.
     *
     * <p>Call this from a screen's error handling whenever an {@link ApiClient} call throws,
     * then check {@link ApiException#isUnauthorized()} on the same exception to decide whether
     * to navigate back to the login screen.
     */
    public void handleFailure(final ApiException failure) throws TokenStoreException {
        if (failure.isUnauthorized()) {
            this.apiClient.logout();
            this.tokenStore.clear();
        }
    }

    /** Same as {@link #handleFailure}, swallowing a {@link TokenStoreException} - used from {@link #probeRestoredSessionAsync} where the in-memory session is already cleared either way. */
    private void handleFailureQuietly(final ApiException failure) {
        try {
            this.handleFailure(failure);
        } catch (final TokenStoreException ignored) {
            // Best-effort only - the in-memory token is already cleared by handleFailure before
            // this could throw; only the persisted copy might survive until the next attempt.
        }
    }

}
