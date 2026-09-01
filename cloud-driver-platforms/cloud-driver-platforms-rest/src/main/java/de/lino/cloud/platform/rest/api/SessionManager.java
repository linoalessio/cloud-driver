package de.lino.cloud.platform.rest.api;

import de.lino.cloud.platform.rest.api.ApiClient.ApiException;
import de.lino.cloud.platform.rest.api.session.TokenStore;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Ties {@link ApiClient} to a {@link TokenStore} so a session survives a desktop restart, and
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

    /** The client every network call is issued through and whose in-memory token this class keeps in sync with {@link #tokenStore}. */
    private final ApiClient apiClient;

    /** Where the current session's token is persisted across restarts. */
    private final TokenStore tokenStore;

    /**
     * @param apiClient  the client to authenticate/deauthenticate as this manager's calls succeed/fail
     * @param tokenStore the persistence layer to read/write the session token to/from
     */
    public SessionManager(final ApiClient apiClient, final TokenStore tokenStore) {
        this.apiClient = apiClient;
        this.tokenStore = tokenStore;
    }

    /** @return the underlying {@link ApiClient} for making authenticated calls once logged in */
    public ApiClient api() {
        return this.apiClient;
    }

    /**
     * Call once at desktop startup. Loads a previously persisted token (if any) and verifies it's
     * still accepted by the server with a single lightweight authenticated call ({@code GET
     * /files}) - there's no dedicated "whoami" endpoint, so this doubles as that check.
     *
     * @return {@code true} if a valid session was restored and the desktop can go straight to the
     * main screen; {@code false} if there was no stored token, or it's no longer valid - either
     * way, show the login screen
     * @throws TokenStoreException if reading the persisted token fails
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

    /**
     * Async form of {@link #tryRestoreSession()} - see the class Javadoc for the threading/executor contract.
     *
     * @return a future completing the same way {@link #tryRestoreSession()} returns, or
     * exceptionally with a {@link CompletionException} wrapping a {@link TokenStoreException}
     */
    public CompletableFuture<Boolean> tryRestoreSessionAsync() {
        return CompletableFuture
                .supplyAsync(this::loadStoredTokenOrThrow, this.apiClient.executor())
                .thenCompose(storedToken -> storedToken.isEmpty()
                        ? CompletableFuture.completedFuture(Boolean.FALSE)
                        : this.probeRestoredSessionAsync(storedToken.get()));
    }

    /**
     * Reads the persisted token, wrapping a checked {@link TokenStoreException} in an unchecked
     * {@link CompletionException} so this can run as a {@link CompletableFuture#supplyAsync} task.
     *
     * @return the persisted token, if any
     */
    private Optional<String> loadStoredTokenOrThrow() {
        try {
            return this.tokenStore.load();
        } catch (final TokenStoreException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Restores {@code storedToken} into {@link #apiClient} and probes it with a lightweight
     * authenticated call, clearing the session on a {@code 401} the same way {@link
     * #tryRestoreSession()} does.
     *
     * @param storedToken the token loaded from {@link #tokenStore}
     * @return a future completing with {@code true} if the token is still accepted, {@code false} otherwise
     */
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

    /**
     * {@code POST /auth/login}, then persists the resulting token.
     *
     * @param emailAddress the account's e-mail address
     * @param password     the account's plaintext password
     * @throws ApiException        {@code 401} on wrong credentials, or any other transport/HTTP failure
     * @throws TokenStoreException if persisting the freshly issued token fails
     */
    public void login(final String emailAddress, final String password) throws ApiException, TokenStoreException {
        final String token = this.apiClient.login(emailAddress, password);
        this.tokenStore.save(token);
    }

    /**
     * Async form of {@link #login} - see the class Javadoc for the threading/executor contract.
     *
     * @param emailAddress the account's e-mail address
     * @param password     the account's plaintext password
     * @return a future completing once the token is both obtained and persisted, or exceptionally
     * with a {@link CompletionException} wrapping an {@link ApiException}/{@link TokenStoreException}
     */
    public CompletableFuture<Void> loginAsync(final String emailAddress, final String password) {
        return this.apiClient.loginAsync(emailAddress, password)
                .thenApplyAsync(this::saveTokenOrThrow, this.apiClient.executor());
    }

    /**
     * {@code POST /auth/register} - step one of registration, e-mails a verification code. Does
     * not create an account and leaves no session to persist; call {@link #confirmRegistration}
     * with the e-mailed code to actually create the account.
     *
     * @param emailAddress the address to register
     * @param password     the plaintext password to hash and store once the account is confirmed
     * @throws ApiException {@code 409} if an account already exists for {@code emailAddress}, or
     *                       any other transport/HTTP failure
     */
    public void register(final String emailAddress, final String password) throws ApiException {
        this.apiClient.register(emailAddress, password);
    }

    /**
     * Async form of {@link #register} - see the class Javadoc for the threading/executor contract.
     *
     * @param emailAddress the address to register
     * @param password     the plaintext password to hash and store once the account is confirmed
     * @return a future completing once the code has been requested, or exceptionally with a
     * {@link CompletionException} wrapping an {@link ApiException}
     */
    public CompletableFuture<Void> registerAsync(final String emailAddress, final String password) {
        return this.apiClient.registerAsync(emailAddress, password).thenApply(ignored -> null);
    }

    /**
     * {@code POST /auth/register/confirm} (step two - creates the account and logs it in), then persists the resulting token.
     *
     * @param emailAddress the address being confirmed - the same one passed to {@link #register}
     * @param code         the 6-digit verification code the server e-mailed
     * @throws ApiException        {@code 400} if the code is missing, expired, or does not match,
     *                              or any other transport/HTTP failure
     * @throws TokenStoreException if persisting the freshly issued token fails
     */
    public void confirmRegistration(final String emailAddress, final String code) throws ApiException, TokenStoreException {
        final String token = this.apiClient.confirmRegistration(emailAddress, code);
        this.tokenStore.save(token);
    }

    /**
     * Async form of {@link #confirmRegistration} - see the class Javadoc for the threading/executor contract.
     *
     * @param emailAddress the address being confirmed - the same one passed to {@link #register}
     * @param code         the 6-digit verification code the server e-mailed
     * @return a future completing once the token is both obtained and persisted, or exceptionally
     * with a {@link CompletionException} wrapping an {@link ApiException}/{@link TokenStoreException}
     */
    public CompletableFuture<Void> confirmRegistrationAsync(final String emailAddress, final String code) {
        return this.apiClient.confirmRegistrationAsync(emailAddress, code)
                .thenApplyAsync(this::saveTokenOrThrow, this.apiClient.executor());
    }

    /**
     * Persists {@code token} via {@link #tokenStore}, wrapping a checked {@link
     * TokenStoreException} in an unchecked {@link CompletionException} so this can run as a
     * {@link CompletableFuture#thenApplyAsync} stage.
     *
     * @param token the token to persist
     * @return always {@code null} (this stage produces {@link Void})
     */
    private Void saveTokenOrThrow(final String token) {
        try {
            this.tokenStore.save(token);
        } catch (final TokenStoreException e) {
            throw new CompletionException(e);
        }
        return null;
    }

    /**
     * Ends the session both in memory and in persisted storage.
     *
     * @throws TokenStoreException if clearing the persisted token fails
     */
    public void logout() throws TokenStoreException {
        this.apiClient.logout();
        this.tokenStore.clear();
    }

    /**
     * Async form of {@link #logout} - see the class Javadoc for the threading/executor contract.
     *
     * @return a future completing once the persisted token is cleared, or exceptionally with a
     * {@link CompletionException} wrapping a {@link TokenStoreException}
     */
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
     *
     * @param failure the exception thrown by a failed {@link ApiClient} call
     * @throws TokenStoreException if clearing the persisted token fails (only possible when {@code failure} was a {@code 401})
     */
    public void handleFailure(final ApiException failure) throws TokenStoreException {
        if (failure.isUnauthorized()) {
            this.apiClient.logout();
            this.tokenStore.clear();
        }
    }

    /**
     * Same as {@link #handleFailure}, swallowing a {@link TokenStoreException} - used from {@link #probeRestoredSessionAsync} where the in-memory session is already cleared either way.
     *
     * @param failure the exception thrown by a failed {@link ApiClient} call
     */
    private void handleFailureQuietly(final ApiException failure) {
        try {
            this.handleFailure(failure);
        } catch (final TokenStoreException ignored) {
            // Best-effort only - the in-memory token is already cleared by handleFailure before
            // this could throw; only the persisted copy might survive until the next attempt.
        }
    }

}
