import Foundation

/// Thread-safe in-memory store for `WEGInAppNotificationData` keyed by experiment ID.
/// Mirrors `InAppDataCache.kt` on Android.
internal final class InAppDataCache {
    private var store: [String: [String: Any]] = [:]
    private let lock = NSLock()

    func put(experimentId: String, data: [String: Any]) {
        lock.lock(); defer { lock.unlock() }
        store[experimentId] = data
    }

    func get(experimentId: String) -> [String: Any]? {
        lock.lock(); defer { lock.unlock() }
        return store[experimentId]
    }

    func remove(experimentId: String) {
        lock.lock(); defer { lock.unlock() }
        store.removeValue(forKey: experimentId)
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        store.removeAll()
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return store.count
    }

    var experimentIds: [String] {
        lock.lock(); defer { lock.unlock() }
        return Array(store.keys)
    }
}
