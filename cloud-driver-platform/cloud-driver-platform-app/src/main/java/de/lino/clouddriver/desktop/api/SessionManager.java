package de.lino.clouddriver.desktop.api;

import de.lino.clouddriver.desktop.api.ApiClient.ApiException;
import de.lino.clouddriver.desktop.api.session.TokenStore;
import de.lino.clouddriver.desktop.api.session.TokenStoreException;

import java.util.Optional;

/**
 * Ties {@link ApiClient} to a {@link TokenStore} so a session survives an app restart, and
 * centralizes the "token expired mid-use" handling: {@code cloud-driver} issues 12h JWTs with
 * no refresh mechanism (see {@code AUTH_IMPLEMENTATION.md}), so any authenticated call can fail
 * with a 401 well after a successful login. {@link #handleFailure(ApiException)} is the single
 * place that reacts to that by clearing the stale session, so every screen behaves consistently
 * instead of each controller re-implementing the same check.
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

    /** {@code POST /api/register}, then persists the resulting token. */
    public void register(final String emailAddress, final String password) throws ApiException, TokenStoreException {
        final String token = this.apiClient.register(emailAddress, password);
        this.tokenStore.save(token);
    }

    /** {@code POST /api/login}, then persists the resulting token. */
    public void login(final String emailAddress, final String password) throws ApiException, TokenStoreException {
        final String token = this.apiClient.login(emailAddress, password);
        this.tokenStore.save(token);
    }

    /** Ends the session both in memory and in persisted storage. */
    public void logout() throws TokenStoreException {
        this.apiClient.logout();
        this.tokenStore.clear();
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

}
