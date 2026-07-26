import XCTest

/// Spotify's Feb 2026 API migration renamed POST /playlists/{id}/tracks to
/// /playlists/{id}/items; the old path 403s. This guards against silently
/// reverting the endpoint. (Source-level: the HTTP call hardcodes api.spotify.com,
/// so a behavioural test would require injecting the base URL — not worth it here.)
final class SpotifyEndpointGuardTests: XCTestCase {

    private var source: String {
        get throws {
            // Test file lives in ios/SetlistToSpotifyTests/; source in ios/SetlistToSpotify/…
            let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            let file = dir.appendingPathComponent("SetlistToSpotify/Data/Spotify/SpotifyClient.swift")
            return try String(contentsOf: file, encoding: .utf8)
        }
    }

    func testUsesItemsEndpointForAddingTracks() throws {
        XCTAssertTrue(try source.contains(#"playlists/\(playlistId)/items"#),
                      "addTracks must POST to /playlists/{id}/items")
    }

    func testDoesNotUseDeprecatedTracksWriteEndpoint() throws {
        XCTAssertFalse(try source.contains(#"playlists/\(playlistId)/tracks"#),
                       "The deprecated /playlists/{id}/tracks endpoint was reintroduced; use /items")
    }
}
