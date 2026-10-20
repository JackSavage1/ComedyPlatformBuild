import Foundation
import Security

// =============================================================================
// KeychainStore — a tiny wrapper around the iOS Keychain.
//
// The Keychain is the system's encrypted password store. We keep the
// Supabase session (access + refresh tokens) there instead of UserDefaults,
// which is a plain file anyone with the device backup could read.
// =============================================================================
struct KeychainStore: Sendable {
    /// Groups this app's items. Each item is then identified by an "account".
    let service: String

    init(service: String = "com.comedyplatform.mictracker.auth") {
        self.service = service
    }

    /// The stored bytes for `account`, or nil.
    func data(for account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    /// Saves (adds or replaces) the bytes for `account`.
    @discardableResult
    func set(_ data: Data, for account: String) -> Bool {
        let query = baseQuery(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // Readable after the first unlock following a reboot, and never
            // migrated to another device via backups.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        guard updateStatus == errSecItemNotFound else { return false }

        var addQuery = query
        for (key, value) in attributes { addQuery[key] = value }
        return SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess
    }

    /// Deletes the item for `account` (no error if it didn't exist).
    @discardableResult
    func remove(_ account: String) -> Bool {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
