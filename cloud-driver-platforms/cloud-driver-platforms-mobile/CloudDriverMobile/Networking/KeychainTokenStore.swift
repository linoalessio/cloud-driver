import Foundation
import Security

/// The access + refresh token pair persisted between launches - the iOS counterpart to
/// `cloud-driver-platforms-rest`'s `SessionManager`/`TokenStore`, which on desktop shells out to
/// the OS keychain (`security`/`secret-tool`/DPAPI). Here there's a first-class API for that: the
/// `Security` framework's keychain services, no subprocess involved.
struct StoredSession: Codable {
    let accessToken: String
    let refreshToken: String
}

/// Persists one `StoredSession` in the iOS Keychain under a fixed service/account pair - there is
/// only ever one signed-in account per install of this app, so no per-user keying is needed.
/// `kSecAttrAccessibleAfterFirstUnlock` keeps the session readable while the app runs in the
/// background (e.g. a scheduled refresh) without requiring the device to be freshly unlocked.
final class KeychainTokenStore {
    private let service = "de.lino.cloud.platform.mobile"
    private let account = "session"

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    func save(_ session: StoredSession) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        SecItemDelete(baseQuery as CFDictionary)
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    func load() -> StoredSession? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(StoredSession.self, from: data)
    }

    func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}
