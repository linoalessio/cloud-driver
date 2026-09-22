import Foundation

/// One cached copy of a file's plaintext content, as `ContentCache` hands it back.
///
/// `entityTag` is the server's `ETag` for exactly these bytes, or `nil` when the server recorded
/// none - such an entry can never be revalidated, so the caller must download again rather than
/// guess. `sizeBytes` is what the entry was stored with and is re-checked on every lookup.
struct CachedContent {
    let entityTag: String?
    let url: URL
    let sizeBytes: Int64
}

/// A process-lifetime, per-account, on-disk cache of already-downloaded plaintext content, used by
/// the preview path so reopening the same file costs one conditional metadata round trip instead
/// of a whole download. The iOS twin of cloud-driver-platforms-desktop's own `ContentCache.kt`.
///
/// Every rule here exists because the cached bytes are plaintext:
///
/// - **Keyed by account id plus file id, never by name.** A file id is unique; a name is not, and
///   two accounts used on one device must never be able to read each other's cached content.
/// - **Stored under a `content-cache` subdirectory of the app's temporary directory**, which iOS
///   itself reclaims - cached plaintext has no business outliving the install that fetched it.
///   Every written file gets `.completeFileProtection`, the platform's equivalent of the desktop
///   cache's owner-only permissions: at rest, behind the passcode.
/// - **`lookup` revalidates structurally.** An entry whose file has since been reclaimed, or whose
///   size no longer matches what was recorded, is dropped rather than handed back - a truncated or
///   externally replaced cache file must never be served as content.
/// - **Bounded.** At most `maxEntries` entries and `maxTotalBytes` in total, least-recently-used
///   evicted first (and its file removed), so a long browsing session cannot fill the disk.
///
/// `clear()` drops everything and is called on sign-out: cached plaintext belongs to the account
/// that fetched it.
@MainActor
final class ContentCache {

    /// The one instance the app uses - `@MainActor`, like `AppViewModel`, which is its only caller.
    static let shared = ContentCache()

    /// Hard cap on how many entries are retained before the least recently used one is evicted.
    private let maxEntries = 25

    /// Hard cap on the cache's total on-disk size before eviction starts.
    private let maxTotalBytes: Int64 = 200 * 1024 * 1024

    /// Cache keys in least-recently-used order, so `removeFirst()` always names the next eviction.
    private var usageOrder: [String] = []

    /// Every retained entry, by `accountId + fileId` key.
    private var entries: [String: CachedContent] = [:]

    private init() {}

    /// The cached plaintext for `fileId` under `accountId`, or `nil` if there is none usable. A
    /// returned entry's `entityTag` may still be `nil`, which means it cannot be revalidated -
    /// download again instead.
    func lookup(accountId: String?, fileId: String) -> CachedContent? {
        let key = Self.key(accountId: accountId, fileId: fileId)
        guard let entry = entries[key] else { return nil }
        let attributes = try? FileManager.default.attributesOfItem(atPath: entry.url.path)
        let currentSize = (attributes?[.size] as? NSNumber)?.int64Value
        guard let currentSize, currentSize == entry.sizeBytes else {
            forget(key: key)
            return nil
        }
        touch(key: key)
        return entry
    }

    /// Copies `source` into the cache as `fileId`'s content under `accountId` and returns the
    /// cached file's own URL - the caller reads from that URL, not from `source`, which it remains
    /// free to delete. A failure to cache is never fatal: `source` is returned unchanged, so the
    /// caller still has the content it just downloaded.
    @discardableResult
    func store(accountId: String?, fileId: String, source: URL, entityTag: String?) -> URL {
        let key = Self.key(accountId: accountId, fileId: fileId)
        do {
            let directory = try cacheDirectory()
            let cached = directory.appendingPathComponent("\(UUID().uuidString).bin")
            forget(key: key)
            try FileManager.default.copyItem(at: source, to: cached)
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.complete], ofItemAtPath: cached.path
            )
            let attributes = try FileManager.default.attributesOfItem(atPath: cached.path)
            let sizeBytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
            entries[key] = CachedContent(entityTag: entityTag, url: cached, sizeBytes: sizeBytes)
            touch(key: key)
            evictDownToBounds()
            return cached
        } catch {
            return source
        }
    }

    /// Removes every cached file and forgets every entry - call on sign-out.
    func clear() {
        for entry in entries.values {
            try? FileManager.default.removeItem(at: entry.url)
        }
        entries.removeAll()
        usageOrder.removeAll()
    }

    /// Drops least-recently-used entries until both the count and total-size bounds hold again.
    private func evictDownToBounds() {
        var totalBytes = entries.values.reduce(Int64(0)) { $0 + $1.sizeBytes }
        while !usageOrder.isEmpty && (entries.count > maxEntries || totalBytes > maxTotalBytes) {
            let key = usageOrder.removeFirst()
            if let evicted = entries.removeValue(forKey: key) {
                totalBytes -= evicted.sizeBytes
                try? FileManager.default.removeItem(at: evicted.url)
            }
        }
    }

    /// Drops one entry and its file.
    private func forget(key: String) {
        if let entry = entries.removeValue(forKey: key) {
            try? FileManager.default.removeItem(at: entry.url)
        }
        usageOrder.removeAll { $0 == key }
    }

    /// Moves `key` to the most-recently-used end of `usageOrder`.
    private func touch(key: String) {
        usageOrder.removeAll { $0 == key }
        usageOrder.append(key)
    }

    /// The cache directory, created on first use.
    private func cacheDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("content-cache")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// The cache key - account id and file id together, never a name.
    private static func key(accountId: String?, fileId: String) -> String {
        "\(accountId ?? "anonymous"):\(fileId)"
    }
}
