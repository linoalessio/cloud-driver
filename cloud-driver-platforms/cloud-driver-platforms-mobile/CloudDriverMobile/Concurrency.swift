import Foundation

/// Runs `action` for every element of `items` with at most `maxConcurrency` running at once - the
/// Swift counterpart to cloud-driver-platforms-desktop's own `Concurrency.kt#mapConcurrently`.
/// Every item is attempted regardless of an earlier failure (a non-throwing `TaskGroup`, so one
/// child returning an error never cancels its siblings); the *first* error encountered is
/// returned only once every item has been attempted, matching this app's own existing
/// attempt-everything/surface-the-first-failure convention (`deleteEntries`, `moveEntries`,
/// `shareEntries`, `restoreAllTrash`).
///
/// Added 2026-09-05: before this, every multi-item batch operation in this app ran fully
/// sequentially - one network round trip at a time, unlike the desktop client's capped 8-way
/// concurrency. For a folder with hundreds of files, that serialized hundreds of round trips
/// where a handful of concurrent slots would do. `maxConcurrency` defaults to a conservative 4-6
/// range (below desktop's 8) since iOS runs under tighter memory/networking constraints than a
/// desktop process - callers pick a value appropriate to their own operation's cost.
@discardableResult
func runConcurrently<T: Sendable>(_ items: [T], maxConcurrency: Int, action: @escaping @Sendable (T) async -> Error?) async -> Error? {
    guard !items.isEmpty else { return nil }
    let cap = max(1, maxConcurrency)

    return await withTaskGroup(of: Error?.self) { group in
        var iterator = items.makeIterator()
        var firstError: Error?

        func addNext() {
            if let item = iterator.next() {
                group.addTask { await action(item) }
            }
        }

        for _ in 0..<min(cap, items.count) {
            addNext()
        }

        while let result = await group.next() {
            if firstError == nil { firstError = result }
            addNext()
        }
        return firstError
    }
}
