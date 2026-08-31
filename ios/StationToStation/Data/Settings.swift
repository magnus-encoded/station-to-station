import Foundation

/// On-device storage for settings, Spotify tokens, and friends. Mirrors the Android
/// SettingsRepository, including its split: the ordinary settings and the friends
/// live in `UserDefaults`, and the bearer secrets live somewhere a backup does not
/// reach — [KeychainStore] here, an excluded DataStore file there.
///
/// The parity is in the property, not the mechanism. `Identities` records that the
/// setlist.fm username is *"public and is an identity, not a credential"*, so it stays
/// with the friends on both platforms; what moves is what `Credentials` names.
final class Settings {

    private let store = UserDefaults.standard

    private enum Key {
        static let setlistFmApiKey = "setlistfm_api_key"
        static let clashfinderUser = "clashfinder_user"
        static let clashfinderPrivateKey = "clashfinder_private_key"
        static let spotifyClientId = "spotify_client_id"
        static let accessToken = "spotify_access_token"
        static let refreshToken = "spotify_refresh_token"
        static let tokenExpiry = "spotify_token_expiry"
        static let scope = "spotify_scope"
        static let pkceVerifier = "pkce_verifier"
        static let mySetlistFmUser = "my_setlistfm_user"
        static let onboarded = "onboarded"
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

    // --- Clashfinder ---
    //
    // No bundled fallback the way setlist.fm has one (see `Clashfinder.swift`): a
    // shared credential would put all of this app's traffic on one account against
    // a host running active bot protection. With no account there is no programme
    // feature at all, so the empty state has to say what to get and where.

    var clashfinderUser: String? { store.string(forKey: Key.clashfinderUser)?.nilIfBlank }
    var clashfinderPrivateKey: String? { store.string(forKey: Key.clashfinderPrivateKey)?.nilIfBlank }

    var clashfinderAuth: ClashfinderAuth? {
        guard let user = clashfinderUser, let key = clashfinderPrivateKey else { return nil }
        return ClashfinderAuth(user: user, publicKey: clashfinderPublicKey(user: user, privateKey: key))
    }

    /// The public key is derived and stored alongside the credential that produced
    /// it — computed once here, on save, rather than per request.
    func saveClashfinderAccount(user: String, privateKey: String) {
        store.set(user.trimmingCharacters(in: .whitespaces), forKey: Key.clashfinderUser)
        store.set(privateKey.trimmingCharacters(in: .whitespaces), forKey: Key.clashfinderPrivateKey)
    }

    /// Whether the first-run door has been passed. False on a fresh install and
    /// nowhere else — `UserDefaults` answers false for a key it has never seen, which
    /// is exactly the answer wanted.
    var onboarded: Bool { store.bool(forKey: Key.onboarded) }
    func setOnboarded() { store.set(true, forKey: Key.onboarded) }

    var mySetlistFmUser: String? { store.string(forKey: Key.mySetlistFmUser)?.nilIfBlank }
    func saveMySetlistFmUser(_ v: String) {
        store.set(v.trimmingCharacters(in: .whitespaces), forKey: Key.mySetlistFmUser)
    }

    var friends: [Friend] { decodeFriends(store.string(forKey: Key.friends)) }
    func saveFriends(_ friends: [Friend]) {
        store.set(encodeFriends(friends), forKey: Key.friends)
    }

    // --- Spotify OAuth ---
    //
    // Everything below reads and writes the Keychain, never `store`. The expiry rides
    // with the token although it is not itself secret: an expiry restored for a token
    // that did not come back would be a lie about a credential the device does not
    // hold, and the two are only ever written together.

    /// Tokens written before the move, relocated once and erased from the plist.
    ///
    /// Leaving a copy in `UserDefaults` would keep the exact exposure this closes —
    /// the plist is in an unencrypted local backup and the Keychain item is not — and
    /// reading them across is what stops the move costing a login already done.
    ///
    /// Only fills an empty Keychain: a login writes straight there, so anything
    /// already present is newer than whatever the plist kept, and copying then would
    /// restore the token the user had just replaced. The plist copy goes either way.
    private func migrateFromDefaults() {
        let keys = [Key.accessToken, Key.refreshToken, Key.scope, Key.pkceVerifier]
        let leftBehind = keys.filter { store.string(forKey: $0) != nil }
        guard !leftBehind.isEmpty else { return }

        if keys.allSatisfy({ KeychainStore.string($0) == nil }) {
            for key in leftBehind {
                if let v = store.string(forKey: key) { KeychainStore.set(v, for: key) }
            }
            if let expiry = store.object(forKey: Key.tokenExpiry) as? Double {
                KeychainStore.set(String(expiry), for: Key.tokenExpiry)
            }
        }
        (leftBehind + [Key.tokenExpiry]).forEach(store.removeObject)
    }

    func savePkceVerifier(_ v: String) { KeychainStore.set(v, for: Key.pkceVerifier) }
    var pkceVerifier: String? {
        migrateFromDefaults()
        return KeychainStore.string(Key.pkceVerifier)
    }

    func saveTokens(access: String, refresh: String?, expiresIn: Double, scope: String?) {
        KeychainStore.set(access, for: Key.accessToken)
        // Refresh one minute early to avoid using a token that expires mid-request.
        KeychainStore.set(String(Date().timeIntervalSince1970 + (expiresIn - 60)), for: Key.tokenExpiry)
        if let refresh { KeychainStore.set(refresh, for: Key.refreshToken) }
        if let scope, !scope.isEmpty { KeychainStore.set(scope, for: Key.scope) }
    }

    /// A Spotify credential that arrived from my own other phone (#142/#143), stored
    /// where every other bearer secret is stored.
    ///
    /// Deliberately *not* `saveTokens`: there is no access token in a handover and no
    /// expiry to write, and inventing one would mean claiming a token this device does
    /// not hold. The refresh token is enough to mint an access token on first use, which
    /// is the whole point of moving it.
    func saveHandoverCredentials(refresh: String, scope: String?) {
        KeychainStore.set(refresh, for: Key.refreshToken)
        if let scope, !scope.isEmpty { KeychainStore.set(scope, for: Key.scope) }
    }

    var grantedScope: String? {
        migrateFromDefaults()
        return KeychainStore.string(Key.scope)
    }

    var refreshTokenValue: String? {
        migrateFromDefaults()
        return KeychainStore.string(Key.refreshToken)
    }

    var validAccessToken: String? {
        migrateFromDefaults()
        guard let token = KeychainStore.string(Key.accessToken),
              let expiry = KeychainStore.string(Key.tokenExpiry).flatMap(Double.init)
        else { return nil }
        return Date().timeIntervalSince1970 < expiry ? token : nil
    }

    /// Clears both, since an install that predates the move may still hold a plist
    /// copy if nothing has read it yet, and "log out" has to mean both.
    func clearSpotifyAuth() {
        let keys = [Key.accessToken, Key.refreshToken, Key.tokenExpiry, Key.scope, Key.pkceVerifier]
        keys.forEach(KeychainStore.remove)
        keys.forEach(store.removeObject)
    }
}
