import Foundation

/// Ties `APIClient`'s in-memory tokens to `KeychainTokenStore`'s on-disk persistence - the iOS
/// counterpart to `cloud-driver-platforms-rest`'s `SessionManager`. Kept as its own type (rather
/// than folding this into `AppViewModel` directly) so the "how a session is restored/persisted"
/// concern stays separate from UI/navigation state.
@MainActor
final class SessionManager {
    private let client: APIClient
    private let tokenStore = KeychainTokenStore()

    init(client: APIClient) {
        self.client = client
    }

    /// Loads a persisted session (if any) and confirms it's still valid with one lightweight
    /// authenticated call (`GET /auth/me`) before reporting success - a token that's expired or
    /// been revoked server-side is cleared rather than left around to fail on first real use.
    func tryRestoreSession() async -> Bool {
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

    /// Call once the client's tokens actually change (a fresh login/register/reset, or a
    /// transparent refresh) - persists whatever `APIClient` is currently holding.
    func persistCurrentSession() async {
        guard let access = await client.accessToken, let refresh = await client.refreshToken else { return }
        tokenStore.save(StoredSession(accessToken: access, refreshToken: refresh))
    }

    /// Revokes the refresh token server-side (best-effort) and clears the local session either way.
    func clearSession() async {
        await client.logout()
        tokenStore.clear()
    }
}
