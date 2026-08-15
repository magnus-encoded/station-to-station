import Foundation
import Security

/// The bearer secrets, in the Keychain rather than the preferences plist.
///
/// The counterpart to Android's separate `credentials` DataStore, which the backup
/// rules exclude. Same property, different mechanism: `Credentials` states the rule —
/// *"never in the records manifest, never in an export, never in a backup"* — and on
/// iOS the way to keep it is `…ThisDeviceOnly`, which is what actually holds an item
/// out of an unencrypted Finder backup. `UserDefaults` cannot express that at any
/// accessibility level, which is the whole reason this file exists.
///
/// `AfterFirstUnlock` rather than `WhenUnlocked`: a token refresh can run while the
/// screen is locked, and a credential the app cannot read is a login the user has to
/// do again. `ThisDeviceOnly` is the half that matters here anyway.
///
/// Values are small strings, so there is no chunking and no data class — a failed
/// read returns nil and the caller treats it as "not connected", which is the same
/// thing it did when the key was simply absent.
enum KeychainStore {

    /// Scopes every item to this app, so a key here cannot collide with one written
    /// by anything else sharing the default keychain access group.
    private static let service = "io.github.magnusencoded.stationtostation.credentials"

    private static func query(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    static func string(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne

        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let s = String(data: data, encoding: .utf8), !s.isEmpty
        else { return nil }
        return s
    }

    /// Upsert. `SecItemAdd` fails with `errSecDuplicateItem` rather than replacing, so
    /// the delete-then-add is the whole of "set" — not a retry after a bug.
    static func set(_ value: String, for key: String) {
        SecItemDelete(query(key) as CFDictionary)
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    static func remove(_ key: String) {
        SecItemDelete(query(key) as CFDictionary)
    }
}
