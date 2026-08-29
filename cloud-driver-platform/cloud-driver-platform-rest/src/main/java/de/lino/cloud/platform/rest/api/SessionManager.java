package de.lino.cloud.platform.rest.api;

import de.lino.cloud.platform.rest.api.ApiClient.ApiException;
import de.lino.cloud.platform.rest.api.session.TokenStore;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Ties {@link ApiClient} to a {@link TokenStore} so a session survives an app restart, and
 * centralizes the "token expired mid-use" handling: {@code cloud-driver} issues 12h JWTs with
 * no refresh mechanism (see {@code AUTH_IMPLEMENTATION.md}), so any authenticated call can fail
 * with a 401 well after a successful login. {@link #handleFailure(ApiException)} is the single
 * place that reacts to that by clearing the stale session, so every screen behaves consistently
 * instead of each controller re-implementing the same check.
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
     * Call once at app startup. Loads a previously persisted token (if any) and verifies it's
     * still accepted by the server with a single lightweight authenticated call ({@code GET
     * /files}) - there's no dedicated "whoami" endpoint, so this doubles as that check.
     *
     * @return {@code true} if a valid session was restored and the app can go straight to the
     * main screen; {@code false} if there was no stored token, or it's no longer valid - either
     * way, show the login screen
     */
    public boolean tryRestoreSession() throws TokenStoreException {
        final Optional<String> storedToken = this.tokenStore.load();
        if (storedToken.isEmpty()) {
            return false;
        }

        this.apiClient.restoreSession(storedToken.get());
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
                .supplyAsync(this::loadStoredTokenOrThrow, this.apiClient.executor())
                .thenCompose(storedToken -> storedToken.isEmpty()
                        ? CompletableFuture.completedFuture(Boolean.FALSE)
                        : this.probeRestoredSessionAsync(storedToken.get()));
    }

    private Optional<String> loadStoredTokenOrThrow() {
        try {
            return this.tokenStore.load();
        } catch (final TokenStoreException e) {
            throw new CompletionException(e);
        }
    }

    private CompletableFuture<Boolean> probeRestoredSessionAsync(final String storedToken) {
        this.apiClient.restoreSession(storedToken);
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

    /** {@code POST /auth/login}, then persists the resulting token. */
    public void login(final String emailAddress, final String password) throws ApiException, TokenStoreException {
        final String token = this.apiClient.login(emailAddress, password);
        this.tokenStore.save(token);
    }

    /** Async form of {@link #login} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> loginAsync(final String emailAddress, final String password) {
        return this.apiClient.loginAsync(emailAddress, password)
                .thenApplyAsync(this::saveTokenOrThrow, this.apiClient.executor());
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

    /** {@code POST /auth/register/confirm} (step two - creates the account and logs it in), then persists the resulting token. */
    public void confirmRegistration(final String emailAddress, final String code) throws ApiException, TokenStoreException {
        final String token = this.apiClient.confirmRegistration(emailAddress, code);
        this.tokenStore.save(token);
    }

    /** Async form of {@link #confirmRegistration} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> confirmRegistrationAsync(final String emailAddress, final String code) {
        return this.apiClient.confirmRegistrationAsync(emailAddress, code)
                .thenApplyAsync(this::saveTokenOrThrow, this.apiClient.executor());
    }

    private Void saveTokenOrThrow(final String token) {
        try {
            this.tokenStore.save(token);
        } catch (final TokenStoreException e) {
            throw new CompletionException(e);
        }
        return null;
    }

    /** Ends the session both in memory and in persisted storage. */
    public void logout() throws TokenStoreException {
        this.apiClient.logout();
        this.tokenStore.clear();
    }

    /** Async form of {@link #logout} - see the class Javadoc for the threading/executor contract. */
    public CompletableFuture<Void> logoutAsync() {
        this.apiClient.logout();
        return CompletableFuture.runAsync(() -> {
            try {
                this.tokenStore.clear();
            } catch (final TokenStoreException e) {
                throw new CompletionException(e);
            }
        }, this.apiClient.executor());
    }

    /**
     * Reacts to a failed authenticated call. On {@code 401} (expired/invalid token), clears
     * both the in-memory and persisted session, since a stale token is worthless to keep
     * around. Every other failure (network error, 404, 500, ...) is left untouched - it's a
     * real error the caller should still show to the user, not a reason to log them out.
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
