import Foundation

/// On-device storage for settings, Spotify tokens, and friends. Mirrors the
/// Android SettingsRepository (which uses a plain, unencrypted DataStore), so
/// UserDefaults is faithful parity.
/// ponytail: tokens sit in UserDefaults like Android's plaintext DataStore.
/// Move the access/refresh token to the Keychain if that threat model matters.
final class Settings {

    private let store = UserDefaults.standard

    private enum Key {
        static let setlistFmApiKey = "setlistfm_api_key"
        static let spotifyClientId = "spotify_client_id"
        static let accessToken = "spotify_access_token"
        static let refreshToken = "spotify_refresh_token"
        static let tokenExpiry = "spotify_token_expiry"
        static let scope = "spotify_scope"
        static let pkceVerifier = "pkce_verifier"
        static let mySetlistFmUser = "my_setlistfm_user"
        static let friends = "friends"
    }

    // User-entered values take precedence; otherwise fall back to credentials
    // bundled at build time (see Config).
    var setlistFmApiKey: String? { store.string(forKey: Key.setlistFmApiKey)?.nilIfBlank }
    var spotifyClientId: String? { store.string(forKey: Key.spotifyClientId)?.nilIfBlank }

    var setlistFmApiKeyValue: String? { setlistFmApiKey ?? Config.bundledSetlistFmApiKey }
    var spotifyClientIdValue: String? { spotifyClientId ?? Config.bundledSpotifyClientId }

    var hasBundledSetlistFmKey: Bool { Config.bundledSetlistFmApiKey != nil }
    var hasBundledSpotifyClientId: Bool { Config.bundledSpotifyClientId != nil }

    func saveSetlistFmApiKey(_ v: String) {
        store.set(v.trimmingCharacters(in: .whitespaces), forKey: Key.setlistFmApiKey)
    }
    func saveSpotifyClientId(_ v: String) {
        store.set(v.trimmingCharacters(in: .whitespaces), forKey: Key.spotifyClientId)
    }

    var mySetlistFmUser: String? { store.string(forKey: Key.mySetlistFmUser)?.nilIfBlank }
    func saveMySetlistFmUser(_ v: String) {
        store.set(v.trimmingCharacters(in: .whitespaces), forKey: Key.mySetlistFmUser)
    }

    var friends: [Friend] { decodeFriends(store.string(forKey: Key.friends)) }
    func saveFriends(_ friends: [Friend]) {
        store.set(encodeFriends(friends), forKey: Key.friends)
    }

    // --- Spotify OAuth ---

    func savePkceVerifier(_ v: String) { store.set(v, forKey: Key.pkceVerifier) }
    var pkceVerifier: String? { store.string(forKey: Key.pkceVerifier) }

    func saveTokens(access: String, refresh: String?, expiresIn: Double, scope: String?) {
        store.set(access, forKey: Key.accessToken)
        // Refresh one minute early to avoid using a token that expires mid-request.
        store.set(Date().timeIntervalSince1970 + (expiresIn - 60), forKey: Key.tokenExpiry)
        if let refresh { store.set(refresh, forKey: Key.refreshToken) }
        if let scope, !scope.isEmpty { store.set(scope, forKey: Key.scope) }
    }

    var grantedScope: String? { store.string(forKey: Key.scope)?.nilIfBlank }
    var refreshTokenValue: String? { store.string(forKey: Key.refreshToken)?.nilIfBlank }

    var validAccessToken: String? {
        guard let token = store.string(forKey: Key.accessToken) else { return nil }
        return Date().timeIntervalSince1970 < store.double(forKey: Key.tokenExpiry) ? token : nil
    }

    func clearSpotifyAuth() {
        [Key.accessToken, Key.refreshToken, Key.tokenExpiry, Key.scope, Key.pkceVerifier]
            .forEach(store.removeObject)
    }
}
