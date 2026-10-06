import Foundation

/// The slice of `SpotifyClient` the login round trip touches.
protocol SpotifyLogin: AnyObject {
    @MainActor func login() async throws
}

extension SpotifyClient: SpotifyLogin {}

/// Service accounts, the Spotify login, my setlist.fm user, and the first-run door.
@MainActor
final class SettingsController {
    private let host: StateHost
    private let settings: Settings
    private let spotify: SpotifyLogin

    init(host: StateHost, settings: Settings, spotify: SpotifyLogin) {
        self.host = host
        self.settings = settings
        self.spotify = spotify
    }

    func saveSettings(apiKey: String, clientId: String) {
        settings.saveSetlistFmApiKey(apiKey)
        settings.saveSpotifyClientId(clientId)
        host.state.setlistFmApiKey = apiKey.trimmingCharacters(in: .whitespaces)
        host.state.spotifyClientId = clientId.trimmingCharacters(in: .whitespaces)
        host.state.spotifyLoginReady = settings.spotifyClientIdValue != nil
        host.state.setlistFmReady = settings.setlistFmApiKeyValue != nil
        host.state.setlistFmSharedQuotaSpent = settings.setlistFmSharedQuotaSpentNow
    }

    func saveClashfinderAccount(user: String, privateKey: String) {
        settings.saveClashfinderAccount(user: user, privateKey: privateKey)
        host.state.clashfinderUser = user.trimmingCharacters(in: .whitespaces)
        host.state.clashfinderPrivateKey = privateKey.trimmingCharacters(in: .whitespaces)
    }

    @discardableResult
    func loginSpotify() -> Task<Void, Never> {
        Task {
            do {
                try await spotify.login()
                host.state.spotifyConnected = true
                host.state.grantedScope = settings.grantedScope
            } catch {
                host.fail(error)
            }
        }
    }

    /// The first-run door has been passed — by either button.
    ///
    /// A login that failed still counts: the door was opened deliberately, and making
    /// someone answer the same splash again because Spotify was unreachable would
    /// punish them for a network they do not control. Settings has the login for a
    /// second attempt.
    func markOnboarded() {
        settings.setOnboarded()
        host.state.onboarded = true
    }

    func disconnectSpotify() {
        settings.clearSpotifyAuth()
        host.state.spotifyConnected = false
        host.state.grantedScope = nil
    }

    func saveMySetlistFmUser(_ username: String) {
        let trimmed = username.trimmingCharacters(in: .whitespaces)
        settings.saveMySetlistFmUser(trimmed)
        host.state.mySetlistFmUser = trimmed
    }
}
