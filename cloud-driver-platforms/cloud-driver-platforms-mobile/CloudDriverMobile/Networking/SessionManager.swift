import Foundation

/// Ties `APIClient`'s in-memory tokens to `KeychainTokenStore`'s on-disk persistence - the iOS
/// counterpart to `cloud-driver-platforms-rest`'s `SessionManager`. Kept as its own type (rather
/// than folding this into `AppViewModel` directly) so the "how a session is restored/persisted"
/// concern stays separate from UI/navigation state.
@MainActor
final class SessionManager {
    private let client: APIClient
    private let tokenStore = KeychainTokenStore()
    private var handlerInstalled = false

    init(client: APIClient) {
        self.client = client
    }

    /// Registers `APIClient.onTokensRotated` exactly once, before this session's tokens can
    /// change for the first time - every public method below calls this first. Fixes a real bug:
    /// `APIClient`'s own transparent 401-retry refresh (and `tryRestoreSession`'s own restore-time
    /// `me()` call, which can trigger that same retry) used to rotate tokens only in the actor's
    /// in-memory state, never persisting the new refresh token to the Keychain. Since the server
    /// rotates the refresh token on every use, the first silent refresh that happened during an
    /// otherwise-ordinary session left the Keychain holding an already-invalidated token - so the
    /// next cold launch's restore attempt failed with no warning, logging the user out despite a
    /// working session moments earlier. The handler persists synchronously off the exact
    /// (access, refresh) pair `APIClient` just minted, with no extra actor hop back into it.
    private func installTokenRotationHandlerIfNeeded() async {
        guard !handlerInstalled else { return }
        handlerInstalled = true
        await client.setTokensRotatedHandler { [weak self] access, refresh in
            Task { @MainActor in
                self?.save(access: access, refresh: refresh)
            }
        }
    }

    /// Loads a persisted session (if any) and confirms it's still valid with one lightweight
    /// authenticated call (`GET /auth/me`) before reporting success - a token that's expired or
    /// been revoked server-side is cleared rather than left around to fail on first real use.
    func tryRestoreSession() async -> Bool {
        await installTokenRotationHandlerIfNeeded()
        guard let stored = tokenStore.load() else { return false }
        await client.restoreTokens(access: stored.accessToken, refresh: stored.refreshToken)
        do {
            _ = try await client.me()
            return true
        } catch {
            await client.clearTokens()
            tokenStore.clear()
            return false
        }
    }

    /// Call once the client's tokens actually change (a fresh login/register/reset) - persists
    /// whatever `APIClient` is currently holding. A transparent/silent refresh no longer needs an
    /// explicit call to this method - see `installTokenRotationHandlerIfNeeded` above.
    func persistCurrentSession() async {
        await installTokenRotationHandlerIfNeeded()
        guard let access = await client.accessToken, let refresh = await client.refreshToken else { return }
        save(access: access, refresh: refresh)
    }

    /// Revokes the refresh token server-side (best-effort) and clears the local session either way.
    func clearSession() async {
        await installTokenRotationHandlerIfNeeded()
        await client.logout()
        tokenStore.clear()
    }

    private func save(access: String, refresh: String) {
        tokenStore.save(StoredSession(accessToken: access, refreshToken: refresh))
    }
}
