import Foundation

/// Bundled credentials so users get one-tap "Log in with Spotify" and never have
/// to enter a setlist.fm API key. Injected at build time via the SPOTIFY_CLIENT_ID
/// / SETLISTFM_API_KEY build settings (see project.yml; CI overrides from repo
/// secrets) and surfaced through Info.plist. Blank counts as absent, so an unset
/// CI secret can't mask the built-in default. PKCE needs no client secret, so
/// shipping the client ID is safe.
enum Config {
    private static func info(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?.nilIfBlank
    }

    static var bundledSpotifyClientId: String? { info("SpotifyClientId") }
    static var bundledSetlistFmApiKey: String? { info("SetlistFmApiKey") }
}
